package main

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"golang.org/x/crypto/bcrypt"
)

func testServer(test *testing.T) *server {
	test.Helper()
	for _, key := range []string{"JWT_SECRET", "SMTP_HOST", "SMTP_PORT", "SMTP_USER", "SMTP_PASSWORD", "SMTP_FROM"} {
		test.Setenv(key, "")
	}
	database, err := sql.Open("sqlite", filepath.Join(test.TempDir(), "sun.db"))
	if err != nil {
		test.Fatal(err)
	}
	database.SetMaxOpenConns(1)
	test.Cleanup(func() { database.Close() })
	initDB(database)
	instance, err := newServer(database)
	if err != nil {
		test.Fatal(err)
	}
	return instance
}

func callAPI(test *testing.T, instance *server, method, path, token string, payload any) *httptest.ResponseRecorder {
	test.Helper()
	encoded, err := json.Marshal(payload)
	if err != nil {
		test.Fatal(err)
	}
	request := httptest.NewRequest(method, path, bytes.NewReader(encoded))
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response := httptest.NewRecorder()
	instance.routes().ServeHTTP(response, request)
	return response
}

func setupPayload(test *testing.T, instance *server) map[string]string {
	test.Helper()
	var setupToken string
	if err := instance.db.QueryRow(`SELECT value FROM settings WHERE key='setup_token'`).Scan(&setupToken); err != nil {
		test.Fatal(err)
	}
	return map[string]string{"setupToken": setupToken, "email": "admin@example.com", "password": "setup-password"}
}

func initializeTestServer(test *testing.T, instance *server) string {
	test.Helper()
	response := callAPI(test, instance, "POST", "/api/v1/setup", "", setupPayload(test, instance))
	if response.Code != http.StatusCreated {
		test.Fatalf("setup: %d %s", response.Code, response.Body.String())
	}
	var body struct{ Token string }
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil || body.Token == "" {
		test.Fatalf("setup token missing: %s", response.Body.String())
	}
	return body.Token
}

func TestSetupRequiresTokenAndRunsOnce(test *testing.T) {
	instance := testServer(test)
	response := callAPI(test, instance, "GET", "/api/v1/setup", "", nil)
	if response.Code != 200 || strings.Contains(response.Body.String(), "setupToken") || !strings.Contains(response.Body.String(), `"initialized":false`) {
		test.Fatalf("unexpected public status: %s", response.Body.String())
	}
	payload := setupPayload(test, instance)
	payload["setupToken"] = "invalid"
	if response := callAPI(test, instance, "POST", "/api/v1/setup", "", payload); response.Code != 403 {
		test.Fatalf("bad setup token accepted: %d", response.Code)
	}
	token := initializeTestServer(test, instance)
	if response := callAPI(test, instance, "GET", "/api/v1/auth/me", token, nil); response.Code != 200 || !strings.Contains(response.Body.String(), `"isAdmin":true`) {
		test.Fatalf("administrator missing: %s", response.Body.String())
	}
	if response := callAPI(test, instance, "POST", "/api/v1/setup", "", payload); response.Code != 409 {
		test.Fatalf("setup ran twice: %d", response.Code)
	}
	var setupToken string
	if err := instance.db.QueryRow(`SELECT value FROM settings WHERE key='setup_token'`).Scan(&setupToken); err != nil || setupToken != "" {
		test.Fatal("installation token was not consumed")
	}
}

func TestSecretsSurviveRestart(test *testing.T) {
	instance := testServer(test)
	token := initializeTestServer(test, instance)
	var databasePath string
	var sequence int
	var databaseName string
	if err := instance.db.QueryRow(`PRAGMA database_list`).Scan(&sequence, &databaseName, &databasePath); err != nil {
		test.Fatal(err)
	}
	if err := instance.db.Close(); err != nil {
		test.Fatal(err)
	}
	reopened, err := sql.Open("sqlite", databasePath)
	if err != nil {
		test.Fatal(err)
	}
	defer reopened.Close()
	reopened.SetMaxOpenConns(1)
	initDB(reopened)
	test.Setenv("JWT_SECRET", "must-not-replace-existing-secret")
	restarted, err := newServer(reopened)
	if err != nil {
		test.Fatal(err)
	}
	if !bytes.Equal(instance.secret, restarted.secret) {
		test.Fatal("signing key changed across restart")
	}
	if response := callAPI(test, restarted, "GET", "/api/v1/auth/me", token, nil); response.Code != 200 {
		test.Fatal("existing token invalid after restart")
	}
}

func TestLegacySecretImportedVerbatim(test *testing.T) {
	for _, secret := range []string{"legacy-not-hex-secret", "0123456789abcdef"} {
		test.Run(secret, func(test *testing.T) {
			instance := testServer(test)
			if _, err := instance.db.Exec(`DELETE FROM settings WHERE key='jwt_secret'`); err != nil {
				test.Fatal(err)
			}
			test.Setenv("JWT_SECRET", secret)
			imported, err := newServer(instance.db)
			if err != nil || string(imported.secret) != secret {
				test.Fatal("legacy key was modified")
			}
			restarted, err := newServer(instance.db)
			if err != nil || string(restarted.secret) != secret {
				test.Fatal("legacy key was modified on restart")
			}
		})
	}
}

func TestSettingsAuthorizationPersistenceAndRedaction(test *testing.T) {
	instance := testServer(test)
	token := initializeTestServer(test, instance)
	if _, err := instance.db.Exec(`INSERT INTO users(email,password_hash,verified) VALUES('user@example.com','unused',1)`); err != nil {
		test.Fatal(err)
	}
	for _, credential := range []string{"", instance.token(2)} {
		for _, method := range []string{"GET", "PUT"} {
			if response := callAPI(test, instance, method, "/api/v1/settings", credential, nil); response.Code != 403 {
				test.Fatalf("settings accessible to non-admin: %d", response.Code)
			}
		}
		if response := callAPI(test, instance, "POST", "/api/v1/settings/test-email", credential, nil); response.Code != 403 {
			test.Fatal("non-admin could send test email")
		}
	}
	settings := platformSettings{RegistrationEnabled: true, SMTP: smtpSettings{Host: "smtp.example.com", Port: "465", User: "mailer@example.com", Password: "secret-password", Security: "tls"}}
	response := callAPI(test, instance, "PUT", "/api/v1/settings", token, settings)
	if response.Code != 200 || strings.Contains(response.Body.String(), "secret-password") {
		test.Fatalf("settings save/redaction failed: %s", response.Body.String())
	}
	settings.SMTP.Password = ""
	settings.SMTP.Port = "587"
	settings.SMTP.Security = "starttls"
	if response := callAPI(test, instance, "PUT", "/api/v1/settings", token, settings); response.Code != 200 {
		test.Fatalf("empty password did not preserve secret: %s", response.Body.String())
	}
	restarted, err := newServer(instance.db)
	if err != nil {
		test.Fatal(err)
	}
	saved, err := restarted.readSettings()
	if err != nil || saved.SMTP.Password != "secret-password" || saved.SMTP.Port != "587" || !saved.RegistrationEnabled {
		test.Fatal("settings did not persist")
	}
	response = callAPI(test, restarted, "GET", "/api/v1/settings", token, nil)
	if strings.Contains(response.Body.String(), "secret-password") || !strings.Contains(response.Body.String(), `"smtpPasswordSet":true`) {
		test.Fatal("stored password exposed or missing")
	}
	settings.SMTP.Port = "invalid"
	if response := callAPI(test, instance, "PUT", "/api/v1/settings", token, settings); response.Code != 400 {
		test.Fatal("invalid port accepted")
	}
	saved, err = instance.readSettings()
	if err != nil || saved.SMTP.Port != "587" {
		test.Fatal("invalid update changed saved settings")
	}
}

func TestRegistrationRequiresSMTPAndNeverReturnsCode(test *testing.T) {
	instance := testServer(test)
	token := initializeTestServer(test, instance)
	credentials := credentials{Email: "user@example.com", Password: "user-password"}
	if response := callAPI(test, instance, "POST", "/api/v1/auth/register", "", credentials); response.Code != 403 {
		test.Fatal("registration open before SMTP setup")
	}
	settings, err := instance.readSettings()
	if err != nil {
		test.Fatal(err)
	}
	settings.RegistrationEnabled = true
	if response := callAPI(test, instance, "PUT", "/api/v1/settings", token, settings); response.Code != 400 {
		test.Fatal("registration enabled without SMTP")
	}
	settings.SMTP = smtpSettings{Host: "127.0.0.1", Port: "1", User: "mailer@example.com", Password: "secret", Security: "starttls"}
	if response := callAPI(test, instance, "PUT", "/api/v1/settings", token, settings); response.Code != 200 {
		test.Fatalf("SMTP save failed: %s", response.Body.String())
	}
	for attempt := 0; attempt < 2; attempt++ {
		response := callAPI(test, instance, "POST", "/api/v1/auth/register", "", credentials)
		if response.Code != 502 {
			test.Fatalf("delivery failure/retry unexpectedly succeeded: %s", response.Body.String())
		}
		var code string
		if err := instance.db.QueryRow(`SELECT verify_code FROM users WHERE email=?`, credentials.Email).Scan(&code); err != nil {
			test.Fatal(err)
		}
		if strings.Contains(response.Body.String(), code) || strings.Contains(response.Body.String(), "verificationCode") {
			test.Fatal("verification code leaked to caller")
		}
	}
}

func TestConcurrentSetupCreatesSingleAdmin(test *testing.T) {
	instance := testServer(test)
	payload := setupPayload(test, instance)
	var group sync.WaitGroup
	codes := make(chan int, 2)
	for attempt := 0; attempt < 2; attempt++ {
		group.Add(1)
		go func() {
			defer group.Done()
			codes <- callAPI(test, instance, "POST", "/api/v1/setup", "", payload).Code
		}()
	}
	group.Wait()
	close(codes)
	created := 0
	for code := range codes {
		if code == 201 {
			created++
		} else if code != 409 {
			test.Fatalf("unexpected concurrent setup result: %d", code)
		}
	}
	if created != 1 {
		test.Fatalf("created %d administrators", created)
	}
}

func TestLegacyDatabaseMigrationPreservesAccountAndConfig(test *testing.T) {
	instance := testServer(test)
	_, err := instance.db.Exec(`DROP TABLE users; CREATE TABLE users(id INTEGER PRIMARY KEY,email TEXT UNIQUE NOT NULL,password_hash TEXT NOT NULL,verified INTEGER NOT NULL DEFAULT 0,verify_code TEXT,code_expires INTEGER)`)
	if err != nil {
		test.Fatal(err)
	}
	hash, err := bcrypt.GenerateFromPassword([]byte("original-password"), bcrypt.MinCost)
	if err != nil {
		test.Fatal(err)
	}
	if _, err := instance.db.Exec(`INSERT INTO users(id,email,password_hash,verified) VALUES(7,'existing@example.com',?,1); INSERT INTO configs(user_id,version,payload,updated_at) VALUES(7,3,'{"key":"value"}',1)`, hash); err != nil {
		test.Fatal(err)
	}
	initDB(instance.db)
	initDB(instance.db)
	payload := setupPayload(test, instance)
	payload["email"] = "existing@example.com"
	if response := callAPI(test, instance, "POST", "/api/v1/setup", "", payload); response.Code != 403 {
		test.Fatal("existing account promoted without original password")
	}
	payload["password"] = "original-password"
	if response := callAPI(test, instance, "POST", "/api/v1/setup", "", payload); response.Code != 201 {
		test.Fatalf("legacy setup failed: %s", response.Body.String())
	}
	response := callAPI(test, instance, "GET", "/api/v1/config", instance.token(7), nil)
	if response.Code != 200 || !strings.Contains(response.Body.String(), `"version":3`) || !strings.Contains(response.Body.String(), `"key":"value"`) || !instance.isAdmin(7) {
		test.Fatalf("legacy data lost: %s", response.Body.String())
	}
}

func TestHTTPMethodsAndSMTPValidation(test *testing.T) {
	instance := testServer(test)
	for _, path := range []string{"/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/verify"} {
		if response := callAPI(test, instance, "GET", path, "", nil); response.Code != 405 {
			test.Fatalf("GET accepted for %s", path)
		}
	}
	for _, settings := range []smtpSettings{
		{Port: "0", Security: "tls"},
		{Port: "65536", Security: "tls"},
		{Port: "587", Security: "none"},
		{Host: "smtp.example.com\r\n", Port: "587", Security: "starttls"},
		{Port: "587", Security: "starttls", From: "user@example.com\r\nBcc: victim@example.com"},
	} {
		if settings.validate() == nil {
			test.Fatal("invalid SMTP settings accepted")
		}
	}
}
