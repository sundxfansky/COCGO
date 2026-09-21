package main

import (
	"encoding/json"
	"sync"
	"testing"
)

func TestConfigRejectsMissingObjectAndDatabaseFailure(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	for _, payload := range []any{nil, map[string]any{}, map[string]any{"config": nil}, map[string]any{"config": []string{}}, map[string]any{"config": map[string]int{"key": 1}}, map[string]any{"config": map[string]any{"key": nil}}} {
		if response := callAPI(t, instance, "PUT", "/api/v1/config", token, payload); response.Code != 400 {
			t.Fatalf("invalid config accepted: %s", response.Body.String())
		}
	}
	if _, err := instance.db.Exec(`CREATE TRIGGER reject_config BEFORE INSERT ON configs BEGIN SELECT RAISE(FAIL, 'disk full'); END`); err != nil {
		t.Fatal(err)
	}
	response := callAPI(t, instance, "PUT", "/api/v1/config", token, configRequest{Config: map[string]string{"key": "value"}}, map[string]string{"If-Match": "0"})
	if response.Code != 500 {
		t.Fatalf("failed write acknowledged: %s", response.Body.String())
	}
}

func TestConcurrentConfigSavesRejectStaleVersions(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	const count = 12
	codes := make(chan int, count)
	var group sync.WaitGroup
	for i := 0; i < count; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			response := callAPI(t, instance, "PUT", "/api/v1/config", token, configRequest{Config: map[string]string{"key": "value"}}, map[string]string{"If-Match": "0"})
			codes <- response.Code
		}()
	}
	group.Wait()
	close(codes)
	created := 0
	for code := range codes {
		if code == 200 {
			created++
		} else if code != 409 {
			t.Fatalf("unexpected result %d", code)
		}
	}
	if created != 1 {
		t.Fatalf("%d stale overwrites accepted", created)
	}
	response := callAPI(t, instance, "PUT", "/api/v1/config", token, configRequest{Config: map[string]string{"key": "merged"}}, map[string]string{"If-Match": "1"})
	var body struct{ Version int }
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &body) != nil || body.Version != 2 {
		t.Fatalf("merge failed: %s", response.Body.String())
	}
	if response := callAPI(t, instance, "PUT", "/api/v1/config", token, configRequest{Config: map[string]string{}}); response.Code != 428 {
		t.Fatal("missing version accepted")
	}
}

func TestVerificationDoesNotAcknowledgeFailedUpdate(t *testing.T) {
	instance := testServer(t)
	if _, err := instance.db.Exec(`INSERT INTO users(email,password_hash,verify_code,code_expires) VALUES('verify@example.com','unused','123456',4102444800); CREATE TRIGGER reject_verification BEFORE UPDATE ON users BEGIN SELECT RAISE(FAIL, 'disk full'); END`); err != nil {
		t.Fatal(err)
	}
	payload := credentials{Email: "verify@example.com", Code: "123456"}
	if response := callAPI(t, instance, "POST", "/api/v1/auth/verify", "", payload); response.Code != 500 {
		t.Fatalf("failed verification acknowledged: %s", response.Body.String())
	}
	if _, err := instance.db.Exec(`DROP TRIGGER reject_verification`); err != nil {
		t.Fatal(err)
	}
	if response := callAPI(t, instance, "POST", "/api/v1/auth/verify", "", payload); response.Code != 200 {
		t.Fatalf("verification failed: %s", response.Body.String())
	}
	if response := callAPI(t, instance, "POST", "/api/v1/auth/verify", "", payload); response.Code != 400 {
		t.Fatalf("verification code reused: %s", response.Body.String())
	}
}
