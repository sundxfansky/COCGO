package main

import (
	"crypto/rand"
	"database/sql"
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
	db      *sql.DB
	secret  []byte
	limiter authLimiter
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

	// Sessions and verification budgets survive restarts and are revocable.
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS plugins(version INTEGER PRIMARY KEY,name TEXT NOT NULL,payload BLOB NOT NULL,signature BLOB NOT NULL);
 CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY,user_id INTEGER NOT NULL,expires INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS sessions_expires ON sessions(expires);
        CREATE TABLE IF NOT EXISTS revoked_legacy_tokens(token_hash TEXT PRIMARY KEY);
        CREATE TABLE IF NOT EXISTS verification_attempts(email TEXT PRIMARY KEY,attempts INTEGER NOT NULL DEFAULT 0);`); err != nil {
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

	if !s.allowAuth(w, r, "register", x.Email, 3) {
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
	if _, err := s.db.Exec(`DELETE FROM verification_attempts WHERE email=?`, email); err != nil {
		jsonOut(w, 500, map[string]string{"error": "database error"})
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
	if json.NewDecoder(r.Body).Decode(&x) != nil || !validEmail(x.Email) || len(x.Code) != 6 {
		jsonOut(w, 400, map[string]string{"error": "invalid verification request"})
		return
	}
	if !s.allowAuth(w, r, "verify", x.Email, 10) {
		return
	}
	var attempts int
	// An atomic, persisted attempt budget prevents parallel guesses and restart bypasses.
	err := s.db.QueryRow(`INSERT INTO verification_attempts(email,attempts) VALUES(?,1) ON CONFLICT(email) DO UPDATE SET attempts=attempts+1 RETURNING attempts`, strings.ToLower(x.Email)).Scan(&attempts)
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "database error"})
		return
	}
	if attempts > 5 {
		jsonOut(w, 429, map[string]string{"error": "验证码尝试次数过多，请重新发送"})
		return
	}
	var id int
	var expires int64
	err = s.db.QueryRow(`SELECT id,code_expires FROM users WHERE email=? AND verify_code=?`, strings.ToLower(x.Email), x.Code).Scan(&id, &expires)
	if err != nil || time.Now().Unix() > expires {
		jsonOut(w, http.StatusBadRequest, map[string]string{"error": "invalid or expired code"})
		return
	}
	// Consume the same code atomically so a resend cannot validate an obsolete code.
	result, err := s.db.Exec(`UPDATE users SET verified=1,verify_code=NULL,code_expires=NULL WHERE id=? AND verify_code=? AND code_expires>=?`, id, x.Code, time.Now().Unix())
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "database error"})
		return
	}
	if count, err := result.RowsAffected(); err != nil || count != 1 {
		jsonOut(w, 400, map[string]string{"error": "invalid or expired code"})
		return
	}
	jsonOut(w, http.StatusOK, map[string]string{"message": "email verified"})
}

func (s *server) login(w http.ResponseWriter, r *http.Request) {
	var x loginRequest
	if json.NewDecoder(r.Body).Decode(&x) != nil || !validEmail(x.Email) || len(x.Password) > 72 {
		jsonOut(w, 400, map[string]string{"error": "invalid login request"})
		return
	}
	if !s.allowAuth(w, r, "login", x.Email, 10) {
		return
	}
	var id, verified, admin int
	var hash string
	err := s.db.QueryRow(`SELECT id,password_hash,verified,is_admin FROM users WHERE email=?`, strings.ToLower(x.Email)).Scan(&id, &hash, &verified, &admin)
	if err != nil || verified == 0 || bcrypt.CompareHashAndPassword([]byte(hash), []byte(x.Password)) != nil {
		jsonOut(w, http.StatusUnauthorized, map[string]string{"error": "invalid credentials or unverified email"})
		return
	}
	token := s.token(id)
	if token == "" {
		jsonOut(w, 500, map[string]string{"error": "创建会话失败"})
		return
	}
	jsonOut(w, http.StatusOK, map[string]any{"token": token, "email": strings.ToLower(x.Email), "isAdmin": admin == 1})
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
		var input struct {
			Config map[string]json.RawMessage `json:"config"`
		}
		if json.NewDecoder(r.Body).Decode(&input) != nil || input.Config == nil {
			jsonOut(w, http.StatusBadRequest, map[string]string{"error": "config must be a JSON object with string values"})
			return
		}
		x := configRequest{Config: make(map[string]string, len(input.Config))}
		// encoding/json otherwise silently converts null map values into empty strings.
		for key, raw := range input.Config {
			var value string
			if string(raw) == "null" || json.Unmarshal(raw, &value) != nil {
				jsonOut(w, http.StatusBadRequest, map[string]string{"error": "config values must be strings"})
				return
			}
			x.Config[key] = value
		}
		payload, _ := json.Marshal(x.Config)
		// Require the version the editor actually read; stale clients cannot overwrite newer data.
		expected, err := strconv.Atoi(strings.Trim(r.Header.Get("If-Match"), "\""))
		if err != nil || expected < 0 {
			jsonOut(w, 428, map[string]string{"error": "请先读取配置，再携带 If-Match 版本保存"})
			return
		}
		var version int
		if expected == 0 {
			err = s.db.QueryRow(`INSERT INTO configs(user_id,version,payload,updated_at) VALUES(?,1,?,?)
                ON CONFLICT(user_id) DO UPDATE SET version=version+1,payload=excluded.payload,updated_at=excluded.updated_at WHERE configs.version=0 RETURNING version`, id, payload, time.Now().Unix()).Scan(&version)
		} else {
			err = s.db.QueryRow(`UPDATE configs SET version=version+1,payload=?,updated_at=? WHERE user_id=? AND version=? RETURNING version`, payload, time.Now().Unix(), id, expected).Scan(&version)
		}
		if err == sql.ErrNoRows {
			jsonOut(w, 409, map[string]string{"error": "配置已被其他设备修改，请读取最新版本后合并"})
			return
		}
		if err != nil {
			jsonOut(w, 500, map[string]string{"error": "database error"})
			return
		}
		jsonOut(w, http.StatusOK, map[string]any{"version": version, "config": x.Config})
		return
	}
	w.WriteHeader(http.StatusMethodNotAllowed)
}

func (s *server) isAdmin(id int) bool {
	var admin int
	return s.db.QueryRow(`SELECT is_admin FROM users WHERE id=?`, id).Scan(&admin) == nil && admin == 1
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
		w.Header().Set("Access-Control-Allow-Headers", "Authorization,Content-Type,If-None-Match,If-Match")
		w.Header().Set("Access-Control-Allow-Methods", "GET,PUT,POST,OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}
