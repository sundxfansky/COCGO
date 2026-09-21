package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type rateEntry struct {
	count int
	until time.Time
}
type authLimiter struct {
	mu      sync.Mutex
	entries map[string]rateEntry
}

func (l *authLimiter) allow(key string, limit int) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.entries == nil {
		l.entries = make(map[string]rateEntry)
	}
	now := time.Now()
	for k, v := range l.entries {
		if !now.Before(v.until) {
			delete(l.entries, k)
		}
	}
	entry := l.entries[key]
	if entry.until.IsZero() {
		// Bound memory without evicting active limits under an attacker-controlled key flood.
		if len(l.entries) >= 10000 {
			return false
		}
		entry.until = now.Add(15 * time.Minute)
	}
	entry.count++
	l.entries[key] = entry
	return entry.count <= limit
}
func (s *server) allowSource(w http.ResponseWriter, r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	// Trust the TCP peer only; forwarded headers are spoofable without a configured proxy boundary.
	return s.checkLimit(w, "source:"+host, 300)
}
func (s *server) allowAuth(w http.ResponseWriter, r *http.Request, action, email string, limit int) bool {
	return s.checkLimit(w, action+":"+strings.ToLower(email), limit)
}
func (s *server) checkLimit(w http.ResponseWriter, key string, limit int) bool {
	if s.limiter.allow(key, limit) {
		return true
	}
	w.Header().Set("Retry-After", "900")
	jsonOut(w, 429, map[string]string{"error": "尝试过于频繁，请稍后重试"})
	return false
}
func tokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
func (s *server) token(id int) string {
	bytes := make([]byte, 32)
	if _, err := rand.Read(bytes); err != nil {
		return ""
	}
	token := "v2." + base64.RawURLEncoding.EncodeToString(bytes)
	// Store only the token digest; a database read alone cannot impersonate a session.
	_, err := s.db.Exec(`INSERT INTO sessions(token_hash,user_id,expires) VALUES(?,?,?)`, tokenHash(token), id, time.Now().Add(30*24*time.Hour).Unix())
	if err != nil {
		return ""
	}
	_, _ = s.db.Exec(`DELETE FROM sessions WHERE expires<?`, time.Now().Unix())
	return token
}
func bearer(r *http.Request) string {
	value := r.Header.Get("Authorization")
	if !strings.HasPrefix(value, "Bearer ") {
		return ""
	}
	return strings.TrimPrefix(value, "Bearer ")
}
func (s *server) auth(r *http.Request) (int, bool) {
	token := bearer(r)
	if token == "" {
		return 0, false
	}
	if strings.HasPrefix(token, "v2.") {
		var id int
		err := s.db.QueryRow(`SELECT s.user_id FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires>? AND u.verified=1`, tokenHash(token), time.Now().Unix()).Scan(&id)
		return id, err == nil
	}
	// Legacy HMAC tokens expire at a persisted migration deadline and can be revoked immediately.
	var deadline int64
	if s.db.QueryRow(`SELECT value FROM settings WHERE key='legacy_token_deadline'`).Scan(&deadline) != nil || time.Now().Unix() >= deadline {
		return 0, false
	}
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return 0, false
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return 0, false
	}
	mac := hmac.New(sha256.New, s.secret)
	mac.Write([]byte(parts[0]))
	if !hmac.Equal(mac.Sum(nil), signature) {
		return 0, false
	}
	id, err := strconv.Atoi(parts[0])
	if err != nil {
		return 0, false
	}
	var verified int
	err = s.db.QueryRow(`SELECT verified FROM users WHERE id=? AND NOT EXISTS(SELECT 1 FROM revoked_legacy_tokens WHERE token_hash=?)`, id, tokenHash(token)).Scan(&verified)
	return id, err == nil && verified == 1
}
func (s *server) logout(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.auth(r); !ok {
		jsonOut(w, 401, map[string]string{"error": "请重新登录"})
		return
	}
	token := bearer(r)
	var err error
	if strings.HasPrefix(token, "v2.") {
		_, err = s.db.Exec(`DELETE FROM sessions WHERE token_hash=?`, tokenHash(token))
	} else {
		_, err = s.db.Exec(`INSERT OR IGNORE INTO revoked_legacy_tokens(token_hash) VALUES(?)`, tokenHash(token))
	}
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "注销失败"})
		return
	}
	jsonOut(w, 200, map[string]string{"message": "已注销"})
}
