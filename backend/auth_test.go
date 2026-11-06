package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"
)

func TestDeviceConnectionSessionSurvivesBrowserLogout(t *testing.T) {
	s := testServer(t)
	browserToken := initializeTestServer(t, s)
	if response := callAPI(t, s, "POST", "/api/v1/auth/device-token", "", nil); response.Code != 401 {
		t.Fatal("unauthenticated session creation accepted")
	}
	if response := callAPI(t, s, "GET", "/api/v1/auth/device-token", browserToken, nil); response.Code != 405 {
		t.Fatal("session creation must be explicit POST")
	}
	response := callAPI(t, s, "POST", "/api/v1/auth/device-token", browserToken, nil)
	var session struct {
		Token     string `json:"token"`
		ExpiresAt int64  `json:"expiresAt"`
	}
	if response.Code != 201 || json.Unmarshal(response.Body.Bytes(), &session) != nil || session.Token == "" || session.Token == browserToken {
		t.Fatal("device session was not independently created")
	}
	if session.ExpiresAt <= time.Now().Unix() || session.ExpiresAt > time.Now().Add(30*24*time.Hour).Unix() {
		t.Fatal("invalid bounded device session expiry")
	}
	if response := callAPI(t, s, "POST", "/api/v1/auth/logout", browserToken, nil); response.Code != 200 {
		t.Fatal("browser logout failed")
	}
	if response := callAPI(t, s, "GET", "/api/v1/devices", session.Token, nil); response.Code != 200 {
		t.Fatal("browser logout disconnected device session")
	}
	if response := callAPI(t, s, "POST", "/api/v1/auth/logout", session.Token, nil); response.Code != 200 {
		t.Fatal("device session revocation failed")
	}
	if response := callAPI(t, s, "GET", "/api/v1/devices", session.Token, nil); response.Code != 401 {
		t.Fatal("revoked device session remains valid")
	}
}

func TestSessionExpiryAndRevocation(t *testing.T) {
	s := testServer(t)
	token := initializeTestServer(t, s)
	if response := callAPI(t, s, "POST", "/api/v1/auth/logout", token, nil); response.Code != 200 {
		t.Fatal(response.Body.String())
	}
	if response := callAPI(t, s, "GET", "/api/v1/auth/me", token, nil); response.Code != 401 {
		t.Fatal("revoked token accepted")
	}
	token = s.token(1)
	if _, err := s.db.Exec(`UPDATE sessions SET expires=0`); err != nil {
		t.Fatal(err)
	}
	if response := callAPI(t, s, "GET", "/api/v1/auth/me", token, nil); response.Code != 401 {
		t.Fatal("expired token accepted")
	}
}
func TestLegacyTokenMigrationIsBoundedAndRevocable(t *testing.T) {
	s := testServer(t)
	initializeTestServer(t, s)
	mac := hmac.New(sha256.New, s.secret)
	mac.Write([]byte("1"))
	token := "1." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	if response := callAPI(t, s, "GET", "/api/v1/auth/me", token, nil); response.Code != 200 {
		t.Fatal("migration rejected")
	}
	if response := callAPI(t, s, "POST", "/api/v1/auth/logout", token, nil); response.Code != 200 {
		t.Fatal("legacy logout failed")
	}
	if response := callAPI(t, s, "GET", "/api/v1/auth/me", token, nil); response.Code != 401 {
		t.Fatal("legacy revocation ignored")
	}
	if _, err := s.db.Exec(`DELETE FROM revoked_legacy_tokens; UPDATE settings SET value='0' WHERE key='legacy_token_deadline'`); err != nil {
		t.Fatal(err)
	}
	if response := callAPI(t, s, "GET", "/api/v1/auth/me", token, nil); response.Code != 401 {
		t.Fatal("legacy deadline ignored")
	}
}
func TestVerificationAttemptBudgetPersists(t *testing.T) {
	s := testServer(t)
	if _, err := s.db.Exec(`INSERT INTO users(email,password_hash,verify_code,code_expires) VALUES('limited@example.com','unused','123456',4102444800)`); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 5; i++ {
		if response := callAPI(t, s, "POST", "/api/v1/auth/verify", "", credentials{Email: "limited@example.com", Code: "000000"}); response.Code != 400 {
			t.Fatal(response.Body.String())
		}
	}
	restarted, err := newServer(s.db)
	if err != nil {
		t.Fatal(err)
	}
	if response := callAPI(t, restarted, "POST", "/api/v1/auth/verify", "", credentials{Email: "limited@example.com", Code: "123456"}); response.Code != 429 {
		t.Fatal("attempt budget reset on restart")
	}
}
func TestRateLimiterBoundsAttempts(t *testing.T) {
	var limiter authLimiter
	for i := 0; i < 10; i++ {
		if !limiter.allow("account", 10) {
			t.Fatal("premature limit")
		}
	}
	if limiter.allow("account", 10) {
		t.Fatal("unlimited attempts")
	}
	if !limiter.allow("another", 10) {
		t.Fatal("unrelated account blocked")
	}
}
