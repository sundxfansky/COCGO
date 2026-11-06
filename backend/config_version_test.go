package main

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"testing"
)

func TestConfigVersionPollsOnlyTheCurrentRevision(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	checkVersion := func(expected int) {
		t.Helper()
		response := callAPI(t, instance, http.MethodGet, "/api/v1/config/version", token, nil)
		var body map[string]int
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &body) != nil || len(body) != 1 || body["version"] != expected {
			t.Fatalf("version poll: %d %s", response.Code, response.Body.String())
		}
		if response.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("version may be cached: %v", response.Header())
		}
	}
	checkVersion(0)
	checkVersion(0)
	var count int
	if err := instance.db.QueryRow(`SELECT COUNT(*) FROM configs`).Scan(&count); err != nil || count != 0 {
		t.Fatalf("poll created settings: count=%d error=%v", count, err)
	}
	for version := 1; version <= 2; version++ {
		payload := configRequest{Config: map[string]string{"large_setting": strings.Repeat("setting", 10000)}}
		response := callAPI(t, instance, http.MethodPut, "/api/v1/config", token, payload, map[string]string{"If-Match": strconv.Itoa(version - 1)})
		if response.Code != http.StatusOK {
			t.Fatalf("save: %d %s", response.Code, response.Body.String())
		}
		checkVersion(version)
		checkVersion(version)
	}
	// A rejected stale save must not advance the revision seen by polling clients.
	response := callAPI(t, instance, http.MethodPut, "/api/v1/config", token, configRequest{Config: map[string]string{}}, map[string]string{"If-Match": "1"})
	if response.Code != http.StatusConflict {
		t.Fatalf("stale save accepted: %d", response.Code)
	}
	checkVersion(2)
}

func TestConfigVersionRequiresSessionAndIsolatesUsers(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	for _, credential := range []string{"", "invalid"} {
		if response := callAPI(t, instance, http.MethodGet, "/api/v1/config/version", credential, nil); response.Code != http.StatusUnauthorized {
			t.Fatalf("unauthenticated version exposed: %d %s", response.Code, response.Body.String())
		}
	}
	if _, err := instance.db.Exec(`INSERT INTO users(id,email,password_hash,verified) VALUES(2,'second@example.com','unused',1); INSERT INTO configs(user_id,version,payload,updated_at) VALUES(1,7,'{}',123),(2,3,'{}',456)`); err != nil {
		t.Fatal(err)
	}
	for credential, expected := range map[string]int{token: 7, instance.token(2): 3} {
		response := callAPI(t, instance, http.MethodGet, "/api/v1/config/version?user_id=1", credential, nil)
		var body struct{ Version int }
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &body) != nil || body.Version != expected {
			t.Fatalf("wrong account revision: %d %s", response.Code, response.Body.String())
		}
	}
	if response := callAPI(t, instance, http.MethodPost, "/api/v1/auth/logout", token, nil); response.Code != http.StatusOK {
		t.Fatal("could not revoke test session")
	}
	if response := callAPI(t, instance, http.MethodGet, "/api/v1/config/version", token, nil); response.Code != http.StatusUnauthorized {
		t.Fatal("revoked session can still poll")
	}
}

func TestConfigVersionRejectsWritesAndDoesNotHideDatabaseErrors(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete, http.MethodPatch} {
		response := callAPI(t, instance, method, "/api/v1/config/version", token, nil)
		if response.Code != http.StatusMethodNotAllowed || response.Header().Get("Allow") != http.MethodGet {
			t.Fatalf("invalid method %s: %d", method, response.Code)
		}
	}
	if _, err := instance.db.Exec(`DROP TABLE configs`); err != nil {
		t.Fatal(err)
	}
	if response := callAPI(t, instance, http.MethodGet, "/api/v1/config/version", token, nil); response.Code != http.StatusInternalServerError {
		t.Fatalf("database error reported as revision zero: %d %s", response.Code, response.Body.String())
	}
}
