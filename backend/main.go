package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
)

type server struct {
	db     *sql.DB
	secret []byte
}

type credentials struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Code     string `json:"code"`
}

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type configRequest struct {
	Config map[string]string `json:"config"`
}

func main() {
	path := env("DB_PATH", "./data/sun.db")
	if err := os.MkdirAll(dir(path), 0750); err != nil {
		log.Fatal(err)
	}

	db, err := sql.Open("sqlite", path)
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	initDB(db)

	s, err := newServer(db)
	if err != nil {
		log.Fatal(err)
	}
	if initialized, err := s.initialized(); err != nil {
		log.Fatal(err)
	} else if !initialized {
		var setupToken string
		if err := db.QueryRow(`SELECT value FROM settings WHERE key='setup_token'`).Scan(&setupToken); err != nil {
			log.Fatal(err)
		}
		log.Printf("首次安装码（仅管理员使用）: %s", setupToken)
	}

	addr := env("HTTP_ADDR", ":8080")
	log.Printf("sun cloud listening on %s", addr)
	httpServer := &http.Server{Addr: addr, Handler: s.routes(), ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(httpServer.ListenAndServe())
}

func initDB(db *sql.DB) {
	_, err := db.Exec(`
		PRAGMA journal_mode=WAL;
		PRAGMA busy_timeout=5000;
		CREATE TABLE IF NOT EXISTS users(
			id INTEGER PRIMARY KEY,
			email TEXT UNIQUE NOT NULL,
			password_hash TEXT NOT NULL,
			verified INTEGER NOT NULL DEFAULT 0,
			is_admin INTEGER NOT NULL DEFAULT 0,
			verify_code TEXT,
			code_expires INTEGER
		);
		CREATE TABLE IF NOT EXISTS configs(
			user_id INTEGER PRIMARY KEY,
			version INTEGER NOT NULL DEFAULT 0,
			payload TEXT NOT NULL DEFAULT '{}',
			updated_at INTEGER NOT NULL,
			FOREIGN KEY(user_id) REFERENCES users(id)
		);
		CREATE TABLE IF NOT EXISTS settings(
			key TEXT PRIMARY KEY,
			value TEXT NOT NULL DEFAULT ''
		);`)
	if err != nil {
		log.Fatal(err)
	}

	// Keep databases created by older versions compatible with the admin setting.
	if _, err = db.Exec(`ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0`); err != nil && !strings.Contains(err.Error(), "duplicate column") {
		log.Fatal(err)
	}
}

func (s *server) health(w http.ResponseWriter, _ *http.Request) {
	jsonOut(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *server) register(w http.ResponseWriter, r *http.Request) {
	settings, err := s.readSettings()
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "读取平台设置失败"})
		return
	}
	initialized, err := s.initialized()
	if err != nil || !initialized || !settings.RegistrationEnabled || !settings.SMTP.configured() {
		jsonOut(w, 403, map[string]string{"error": "管理员尚未开放注册或配置邮件服务"})
		return
	}
	var x credentials
	if json.NewDecoder(r.Body).Decode(&x) != nil || !validEmail(x.Email) || len(x.Password) < 8 || len(x.Password) > 72 {
		jsonOut(w, http.StatusBadRequest, map[string]string{"error": "valid email and password (8+ chars) required"})
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(x.Password), bcrypt.DefaultCost)
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "密码处理失败"})
		return
	}
	bytes := make([]byte, 3)
	if _, err := rand.Read(bytes); err != nil {
		jsonOut(w, 500, map[string]string{"error": "验证码生成失败"})
		return
	}
	code := fmt.Sprintf("%06d", (int(bytes[0])<<16|int(bytes[1])<<8|int(bytes[2]))%1000000)
	email := strings.ToLower(x.Email)

	// Allow an unverified account to retry delivery without exposing verification codes.
	result, err := s.db.Exec(`INSERT INTO users(email,password_hash,verify_code,code_expires) VALUES(?,?,?,?) ON CONFLICT(email) DO UPDATE SET password_hash=excluded.password_hash,verify_code=excluded.verify_code,code_expires=excluded.code_expires WHERE users.verified=0`, email, hash, code, time.Now().Add(15*time.Minute).Unix())
	if err != nil {
		jsonOut(w, http.StatusConflict, map[string]string{"error": "email already registered"})
		return
	}

	if count, err := result.RowsAffected(); err != nil || count == 0 {
		jsonOut(w, 409, map[string]string{"error": "邮箱已注册"})
		return
	}
	if err := sendMail(settings.SMTP, email, "SUN verification code", "Your verification code is "+code+". It expires in 15 minutes."); err != nil {
		jsonOut(w, 502, map[string]string{"error": "邮件发送失败，请联系管理员检查 SMTP 设置后重新注册"})
		return
	}
	jsonOut(w, http.StatusCreated, map[string]string{"message": "验证码已发送，请查收邮箱"})
}

func (s *server) verify(w http.ResponseWriter, r *http.Request) {
	var x credentials
	_ = json.NewDecoder(r.Body).Decode(&x)
	var id int
	var expires int64
	err := s.db.QueryRow(`SELECT id,code_expires FROM users WHERE email=? AND verify_code=?`, strings.ToLower(x.Email), x.Code).Scan(&id, &expires)
	if err != nil || time.Now().Unix() > expires {
		jsonOut(w, http.StatusBadRequest, map[string]string{"error": "invalid or expired code"})
		return
	}
	_, _ = s.db.Exec(`UPDATE users SET verified=1,verify_code=NULL,code_expires=NULL WHERE id=?`, id)
	jsonOut(w, http.StatusOK, map[string]string{"message": "email verified"})
}

func (s *server) login(w http.ResponseWriter, r *http.Request) {
	var x loginRequest
	_ = json.NewDecoder(r.Body).Decode(&x)
	var id, verified, admin int
	var hash string
	err := s.db.QueryRow(`SELECT id,password_hash,verified,is_admin FROM users WHERE email=?`, strings.ToLower(x.Email)).Scan(&id, &hash, &verified, &admin)
	if err != nil || verified == 0 || bcrypt.CompareHashAndPassword([]byte(hash), []byte(x.Password)) != nil {
		jsonOut(w, http.StatusUnauthorized, map[string]string{"error": "invalid credentials or unverified email"})
		return
	}
	jsonOut(w, http.StatusOK, map[string]any{"token": s.token(id), "email": strings.ToLower(x.Email), "isAdmin": admin == 1})
}

func (s *server) config(w http.ResponseWriter, r *http.Request) {
	id, ok := s.auth(r)
	if !ok {
		jsonOut(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	if r.Method == http.MethodGet {
		var version int
		var payload string
		err := s.db.QueryRow(`SELECT version,payload FROM configs WHERE user_id=?`, id).Scan(&version, &payload)
		if err == sql.ErrNoRows {
			version, payload = 0, "{}"
		}
		if err != nil && err != sql.ErrNoRows {
			jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
			return
		}
		w.Header().Set("ETag", strconv.Itoa(version))
		if r.Header.Get("If-None-Match") == strconv.Itoa(version) && version > 0 {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		var config map[string]string
		_ = json.Unmarshal([]byte(payload), &config)
		jsonOut(w, http.StatusOK, map[string]any{"version": version, "config": config})
		return
	}
	if r.Method == http.MethodPut {
		var x configRequest
		if json.NewDecoder(r.Body).Decode(&x) != nil {
			jsonOut(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON"})
			return
		}
		payload, _ := json.Marshal(x.Config)
		_, _ = s.db.Exec(`INSERT INTO configs(user_id,version,payload,updated_at) VALUES(?,1,?,?) ON CONFLICT(user_id) DO UPDATE SET version=version+1,payload=excluded.payload,updated_at=excluded.updated_at`, id, payload, time.Now().Unix())
		var version int
		_ = s.db.QueryRow(`SELECT version FROM configs WHERE user_id=?`, id).Scan(&version)
		jsonOut(w, http.StatusOK, map[string]any{"version": version, "config": x.Config})
		return
	}
	w.WriteHeader(http.StatusMethodNotAllowed)
}

func (s *server) isAdmin(id int) bool {
	var admin int
	return s.db.QueryRow(`SELECT is_admin FROM users WHERE id=?`, id).Scan(&admin) == nil && admin == 1
}

func (s *server) auth(r *http.Request) (int, bool) {
	auth := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	parts := strings.Split(auth, ".")
	if len(parts) != 2 {
		return 0, false
	}
	mac := hmac.New(sha256.New, s.secret)
	mac.Write([]byte(parts[0]))
	if !hmac.Equal(mac.Sum(nil), decode(parts[1])) {
		return 0, false
	}
	id, _ := strconv.Atoi(parts[0])
	var verified int
	err := s.db.QueryRow(`SELECT verified FROM users WHERE id=?`, id).Scan(&verified)
	return id, err == nil && verified == 1
}

func (s *server) token(id int) string {
	part := strconv.Itoa(id)
	mac := hmac.New(sha256.New, s.secret)
	mac.Write([]byte(part))
	return part + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func decode(value string) []byte {
	decoded, _ := base64.RawURLEncoding.DecodeString(value)
	return decoded
}

func jsonOut(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func dir(path string) string {
	index := strings.LastIndex(path, "/")
	if index < 0 {
		return "."
	}
	return path[:index]
}

func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if origin := os.Getenv("CORS_ORIGIN"); origin != "" {
			w.Header().Set("Access-Control-Allow-Origin", origin)
		}
		w.Header().Set("Access-Control-Allow-Headers", "Authorization,Content-Type,If-None-Match")
		w.Header().Set("Access-Control-Allow-Methods", "GET,PUT,POST,OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}
