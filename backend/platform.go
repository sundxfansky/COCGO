package main

import (
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

type platformSettings struct {
	RegistrationEnabled bool         `json:"registrationEnabled"`
	SMTP                smtpSettings `json:"smtp"`
}

func newServer(db *sql.DB) (*server, error) {
	// Store generated secrets once, so restarts do not invalidate existing sessions.
	for _, key := range []string{"jwt_secret", "setup_token"} {
		value := ""
		if key == "jwt_secret" {
			value = os.Getenv("JWT_SECRET")
		}
		if value == "" {
			buffer := make([]byte, 32)
			if _, err := rand.Read(buffer); err != nil {
				return nil, err
			}
			value = hex.EncodeToString(buffer)
		}
		if _, err := db.Exec(`INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO NOTHING`, key, value); err != nil {
			return nil, err
		}
	}
	var secret string
	if err := db.QueryRow(`SELECT value FROM settings WHERE key='jwt_secret'`).Scan(&secret); err != nil || secret == "" {
		return nil, fmt.Errorf("invalid persisted signing secret: %v", err)
	}
	initial := platformSettings{SMTP: smtpSettings{
		Host: os.Getenv("SMTP_HOST"), Port: env("SMTP_PORT", "587"), User: os.Getenv("SMTP_USER"),
		Password: os.Getenv("SMTP_PASSWORD"), From: os.Getenv("SMTP_FROM"), Security: "starttls",
	}}
	if initial.SMTP.Port == "465" {
		initial.SMTP.Security = "tls"
	}
	initial.RegistrationEnabled = initial.SMTP.configured()
	encoded, err := json.Marshal(initial)
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(`INSERT INTO settings(key,value) VALUES('platform',?) ON CONFLICT(key) DO NOTHING`, string(encoded)); err != nil {
		return nil, err
	}
	// Give existing signed tokens one bounded migration window, never extended on restart.
	if _, err := db.Exec(`INSERT INTO settings(key,value) VALUES('legacy_token_deadline',?) ON CONFLICT(key) DO NOTHING`, strconv.FormatInt(time.Now().Add(7*24*time.Hour).Unix(), 10)); err != nil {
		return nil, err
	}
	return &server{db: db, secret: []byte(secret)}, nil
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("POST /api/v1/plugins", s.uploadPlugin)
	mux.HandleFunc("GET /api/v1/plugins/latest", s.latestPlugin)
	mux.HandleFunc("GET /api/v1/plugins/{version}", s.downloadPlugin)
	mux.HandleFunc("GET /api/v1/setup", s.setupStatus)
	mux.HandleFunc("POST /api/v1/setup", s.setup)
	mux.HandleFunc("POST /api/v1/auth/register", s.register)
	mux.HandleFunc("POST /api/v1/auth/verify", s.verify)
	mux.HandleFunc("POST /api/v1/auth/login", s.login)
	mux.HandleFunc("GET /api/v1/auth/me", s.me)
	mux.HandleFunc("POST /api/v1/auth/logout", s.logout)
	mux.HandleFunc("/api/v1/config", s.config)
	mux.HandleFunc("GET /api/v1/settings", s.settings)
	mux.HandleFunc("PUT /api/v1/settings", s.settings)
	mux.HandleFunc("POST /api/v1/settings/test-email", s.testEmail)
	if root := os.Getenv("WEB_DIR"); root != "" {
		mux.Handle("/", webHandler(root))
	}
	return cors(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		limit := int64(1 << 20)
		if r.URL.Path == "/api/v1/plugins" && r.Method == http.MethodPost {
			limit = 90 << 20
		}
		r.Body = http.MaxBytesReader(w, r.Body, limit)
		if strings.HasPrefix(r.URL.Path, "/api/v1/auth/") || (r.URL.Path == "/api/v1/setup" && r.Method == http.MethodPost) {
			if !s.allowSource(w, r) {
				return
			}
		}
		mux.ServeHTTP(w, r)
	}))
}

func (s *server) initialized() (bool, error) {
	var count int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM users WHERE is_admin=1 AND verified=1`).Scan(&count)
	return count > 0, err
}

func (s *server) setupStatus(w http.ResponseWriter, r *http.Request) {
	initialized, err := s.initialized()
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "读取初始化状态失败"})
		return
	}
	settings, err := s.readSettings()
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "读取平台设置失败"})
		return
	}
	jsonOut(w, 200, map[string]bool{"initialized": initialized, "registrationEnabled": initialized && settings.RegistrationEnabled && settings.SMTP.configured()})
}

func (s *server) setup(w http.ResponseWriter, r *http.Request) {
	var request struct {
		credentials
		SetupToken string `json:"setupToken"`
	}
	if json.NewDecoder(r.Body).Decode(&request) != nil || !validEmail(request.Email) || len(request.Password) < 8 || len(request.Password) > 72 {
		jsonOut(w, 400, map[string]string{"error": "请输入有效邮箱和 8–72 字节的密码"})
		return
	}
	transaction, err := s.db.Begin()
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "初始化失败"})
		return
	}
	defer transaction.Rollback()
	var count int
	if err := transaction.QueryRow(`SELECT COUNT(*) FROM users WHERE is_admin=1 AND verified=1`).Scan(&count); err != nil {
		jsonOut(w, 500, map[string]string{"error": "初始化失败"})
		return
	}
	if count > 0 {
		jsonOut(w, 409, map[string]string{"error": "平台已初始化"})
		return
	}
	var expected string
	if err := transaction.QueryRow(`SELECT value FROM settings WHERE key='setup_token'`).Scan(&expected); err != nil || expected == "" || subtle.ConstantTimeCompare([]byte(expected), []byte(request.SetupToken)) != 1 {
		jsonOut(w, 403, map[string]string{"error": "安装码错误，请查看后端容器日志"})
		return
	}
	email := strings.ToLower(request.Email)
	var id int
	var existingHash string
	err = transaction.QueryRow(`SELECT id,password_hash FROM users WHERE email=?`, email).Scan(&id, &existingHash)
	if err == nil {
		// Upgrade an existing account only after checking its original password.
		if bcrypt.CompareHashAndPassword([]byte(existingHash), []byte(request.Password)) != nil {
			jsonOut(w, 403, map[string]string{"error": "已有账号密码不正确"})
			return
		}
		_, err = transaction.Exec(`UPDATE users SET is_admin=1,verified=1,verify_code=NULL,code_expires=NULL WHERE id=?`, id)
	} else if err == sql.ErrNoRows {
		var hash []byte
		hash, err = bcrypt.GenerateFromPassword([]byte(request.Password), bcrypt.DefaultCost)
		if err == nil {
			var result sql.Result
			result, err = transaction.Exec(`INSERT INTO users(email,password_hash,verified,is_admin) VALUES(?,?,1,1)`, email, hash)
			if err == nil {
				var inserted int64
				inserted, err = result.LastInsertId()
				id = int(inserted)
			}
		}
	}
	if err == nil {
		_, err = transaction.Exec(`UPDATE settings SET value='' WHERE key='setup_token'`)
	}
	if err == nil {
		err = transaction.Commit()
	}
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "初始化失败，请重试"})
		return
	}
	token := s.token(id)
	if token == "" {
		jsonOut(w, 500, map[string]string{"error": "创建会话失败，请登录"})
		return
	}
	jsonOut(w, 201, map[string]any{"token": token, "email": email, "isAdmin": true})
}

func (s *server) me(w http.ResponseWriter, r *http.Request) {
	id, ok := s.auth(r)
	if !ok {
		jsonOut(w, 401, map[string]string{"error": "请重新登录"})
		return
	}
	jsonOut(w, 200, map[string]bool{"isAdmin": s.isAdmin(id)})
}

func (s *server) readSettings() (platformSettings, error) {
	var encoded string
	var settings platformSettings
	if err := s.db.QueryRow(`SELECT value FROM settings WHERE key='platform'`).Scan(&encoded); err != nil {
		return settings, err
	}
	err := json.Unmarshal([]byte(encoded), &settings)
	return settings, err
}

func (s *server) requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	id, ok := s.auth(r)
	if !ok || !s.isAdmin(id) {
		jsonOut(w, 403, map[string]string{"error": "仅管理员可修改部署设置"})
		return false
	}
	return true
}

func (s *server) settings(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	current, err := s.readSettings()
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "读取平台设置失败"})
		return
	}
	if r.Method == http.MethodPut {
		var updated platformSettings
		if json.NewDecoder(r.Body).Decode(&updated) != nil {
			jsonOut(w, 400, map[string]string{"error": "设置格式错误"})
			return
		}
		if updated.SMTP.Password == "" {
			updated.SMTP.Password = current.SMTP.Password
		}
		if err := updated.SMTP.validate(); err != nil {
			jsonOut(w, 400, map[string]string{"error": err.Error()})
			return
		}
		if updated.RegistrationEnabled && !updated.SMTP.configured() {
			jsonOut(w, 400, map[string]string{"error": "开放注册前请先配置完整的 SMTP 服务"})
			return
		}
		encoded, err := json.Marshal(updated)
		if err == nil {
			_, err = s.db.Exec(`UPDATE settings SET value=? WHERE key='platform'`, string(encoded))
		}
		if err != nil {
			jsonOut(w, 500, map[string]string{"error": "保存设置失败"})
			return
		}
		current = updated
	}
	configured := current.SMTP.configured()
	passwordSet := current.SMTP.Password != ""
	current.SMTP.Password = ""
	jsonOut(w, 200, map[string]any{"settings": current, "smtpConfigured": configured, "smtpPasswordSet": passwordSet})
}

func (s *server) testEmail(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	settings, err := s.readSettings()
	if err != nil || !settings.SMTP.configured() {
		jsonOut(w, 400, map[string]string{"error": "请先保存完整的 SMTP 设置"})
		return
	}
	id, _ := s.auth(r)
	var email string
	if err := s.db.QueryRow(`SELECT email FROM users WHERE id=?`, id).Scan(&email); err != nil {
		jsonOut(w, 500, map[string]string{"error": "读取管理员邮箱失败"})
		return
	}
	if err := sendMail(settings.SMTP, email, "SUN SMTP test", "Your SUN cloud mail settings are working."); err != nil {
		jsonOut(w, 502, map[string]string{"error": "测试邮件发送失败，请检查主机、端口、加密方式和账号凭据"})
		return
	}
	jsonOut(w, 200, map[string]string{"message": "测试邮件已发送到管理员邮箱"})
}
