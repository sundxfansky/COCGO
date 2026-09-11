package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/smtp"
	"os"
	"strconv"
	"strings"
	"time"

	_ "modernc.org/sqlite"
	"golang.org/x/crypto/bcrypt"
)

type server struct { db *sql.DB; secret []byte }
type credentials struct { Email, Password, Code string }
type loginRequest struct { Email, Password string }
type configRequest struct { Config map[string]string `json:"config"` }

func main() {
	path := env("DB_PATH", "./data/sun.db"); _ = os.MkdirAll(dir(path), 0750)
	db, err := sql.Open("sqlite", path); if err != nil { log.Fatal(err) }
	defer db.Close(); initDB(db)
	s := &server{db: db, secret: []byte(env("JWT_SECRET", "change-me-in-production"))}
	mux := http.NewServeMux(); mux.HandleFunc("/healthz", s.health); mux.HandleFunc("/api/v1/auth/register", s.register); mux.HandleFunc("/api/v1/auth/verify", s.verify); mux.HandleFunc("/api/v1/auth/login", s.login); mux.HandleFunc("/api/v1/config", s.config)
	addr := env("HTTP_ADDR", ":8080"); log.Printf("sun cloud listening on %s", addr); log.Fatal(http.ListenAndServe(addr, cors(mux)))
}
func initDB(db *sql.DB) { _, err := db.Exec(`PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, verified INTEGER NOT NULL DEFAULT 0, verify_code TEXT, code_expires INTEGER); CREATE TABLE IF NOT EXISTS configs(user_id INTEGER PRIMARY KEY, version INTEGER NOT NULL DEFAULT 0, payload TEXT NOT NULL DEFAULT '{}', updated_at INTEGER NOT NULL, FOREIGN KEY(user_id) REFERENCES users(id));`); if err != nil { log.Fatal(err) } }
func (s *server) health(w http.ResponseWriter, _ *http.Request) { jsonOut(w, 200, map[string]string{"status":"ok"}) }
func (s *server) register(w http.ResponseWriter, r *http.Request) { var x credentials; if json.NewDecoder(r.Body).Decode(&x)!=nil || !strings.Contains(x.Email,"@") || len(x.Password)<8 { jsonOut(w,400,map[string]string{"error":"valid email and password (8+ chars) required"}); return }; h,_:=bcrypt.GenerateFromPassword([]byte(x.Password),bcrypt.DefaultCost); b:=make([]byte,3); _,_=rand.Read(b); code:=fmt.Sprintf("%06d", (int(b[0])<<16|int(b[1])<<8|int(b[2]))%1000000); _,err:=s.db.Exec(`INSERT INTO users(email,password_hash,verify_code,code_expires) VALUES(?,?,?,?)`, strings.ToLower(x.Email),h,code,time.Now().Add(15*time.Minute).Unix()); if err!=nil { jsonOut(w,409,map[string]string{"error":"email already registered"}); return }; if err:=sendVerification(strings.ToLower(x.Email),code); err!=nil { log.Printf("verification code for %s (SMTP unavailable): %s",x.Email,code) }; jsonOut(w,201,map[string]string{"message":"verification code sent to email"}) }
func sendVerification(to, code string) error { host:=os.Getenv("SMTP_HOST"); user:=os.Getenv("SMTP_USER"); pass:=os.Getenv("SMTP_PASSWORD"); from:=env("SMTP_FROM",user); if host==""||user==""||pass=="" { return fmt.Errorf("SMTP is not configured") }; auth:=smtp.PlainAuth("",user,pass,host); msg:=[]byte("To: "+to+"\r\nSubject: SUN verification code\r\n\r\nYour verification code is "+code+". It expires in 15 minutes.\r\n"); return smtp.SendMail(host+":"+env("SMTP_PORT","587"),auth,from,[]string{to},msg) }
func (s *server) verify(w http.ResponseWriter, r *http.Request) { var x credentials; _=json.NewDecoder(r.Body).Decode(&x); var id int; var exp int64; err:=s.db.QueryRow(`SELECT id,code_expires FROM users WHERE email=? AND verify_code=?`,strings.ToLower(x.Email),x.Code).Scan(&id,&exp); if err!=nil || time.Now().Unix()>exp { jsonOut(w,400,map[string]string{"error":"invalid or expired code"}); return }; _,_=s.db.Exec(`UPDATE users SET verified=1,verify_code=NULL,code_expires=NULL WHERE id=?`,id); jsonOut(w,200,map[string]string{"message":"email verified"}) }
func (s *server) login(w http.ResponseWriter, r *http.Request) { var x loginRequest; _=json.NewDecoder(r.Body).Decode(&x); var id,verified int; var hash string; err:=s.db.QueryRow(`SELECT id,password_hash,verified FROM users WHERE email=?`,strings.ToLower(x.Email)).Scan(&id,&hash,&verified); if err!=nil || verified==0 || bcrypt.CompareHashAndPassword([]byte(hash),[]byte(x.Password))!=nil { jsonOut(w,401,map[string]string{"error":"invalid credentials or unverified email"}); return }; jsonOut(w,200,map[string]string{"token":s.token(id),"email":strings.ToLower(x.Email)}) }
func (s *server) config(w http.ResponseWriter, r *http.Request) { id,ok:=s.auth(r); if !ok { jsonOut(w,401,map[string]string{"error":"unauthorized"}); return }; if r.Method=="GET" { var v int; var p string; err:=s.db.QueryRow(`SELECT version,payload FROM configs WHERE user_id=?`,id).Scan(&v,&p); if err==sql.ErrNoRows { v=0;p="{}" }; if err!=nil && err!=sql.ErrNoRows { jsonOut(w,500,map[string]string{"error":"database error"});return }; w.Header().Set("ETag",strconv.Itoa(v)); if r.Header.Get("If-None-Match")==strconv.Itoa(v) && v>0 { w.WriteHeader(http.StatusNotModified); return }; jsonOut(w,200,map[string]any{"version":v,"config":json.RawMessage(p)}) } else if r.Method=="PUT" { var x configRequest; if json.NewDecoder(r.Body).Decode(&x)!=nil {jsonOut(w,400,map[string]string{"error":"invalid JSON"});return}; p,_:=json.Marshal(x.Config); _,_=s.db.Exec(`INSERT INTO configs(user_id,version,payload,updated_at) VALUES(?,1,?,?) ON CONFLICT(user_id) DO UPDATE SET version=version+1,payload=excluded.payload,updated_at=excluded.updated_at`,id,p,time.Now().Unix()); var v int; _=s.db.QueryRow(`SELECT version FROM configs WHERE user_id=?`,id).Scan(&v); jsonOut(w,200,map[string]any{"version":v,"config":x.Config}) } else { w.WriteHeader(405) } }
func (s *server) auth(r *http.Request)(int,bool){ a:=strings.TrimPrefix(r.Header.Get("Authorization"),"Bearer "); p:=strings.Split(a,"."); if len(p)!=2{return 0,false}; mac:=hmac.New(sha256.New,s.secret); mac.Write([]byte(p[0])); if !hmac.Equal(mac.Sum(nil),decode(p[1])){return 0,false}; id,_:=strconv.Atoi(p[0]); return id,id>0 }
func (s *server) token(id int) string { p:=strconv.Itoa(id); mac:=hmac.New(sha256.New,s.secret); mac.Write([]byte(p)); return p+"."+base64.RawURLEncoding.EncodeToString(mac.Sum(nil)) }
func decode(v string)[]byte{b,_:=base64.RawURLEncoding.DecodeString(v);return b}; func jsonOut(w http.ResponseWriter,status int,v any){w.Header().Set("Content-Type","application/json");w.WriteHeader(status);_=json.NewEncoder(w).Encode(v)}; func env(k,d string)string{if v:=os.Getenv(k);v!=""{return v};return d}; func dir(p string)string{i:=strings.LastIndex(p,"/");if i<0{return "."};return p[:i]}; func cors(next http.Handler)http.Handler{return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){w.Header().Set("Access-Control-Allow-Origin",env("CORS_ORIGIN","*"));w.Header().Set("Access-Control-Allow-Headers","Authorization,Content-Type,If-None-Match");w.Header().Set("Access-Control-Allow-Methods","GET,PUT,POST,OPTIONS");if r.Method=="OPTIONS"{w.WriteHeader(204);return};next.ServeHTTP(w,r)})}
