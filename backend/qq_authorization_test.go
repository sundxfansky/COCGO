package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"image"
	"image/png"
	"net/http"
	"testing"
	"time"
)

func TestDeviceQQAuthorization(t *testing.T) {
	s := testServer(t)
	browser := initializeTestServer(t, s)
	device := s.token(1)
	registerTestDevice(t, s, browser, "qq-a", "Device A")
	registerTestDevice(t, s, browser, "qq-b", "Device B")
	if _, err := s.db.Exec(`INSERT INTO users(id,email,password_hash,verified) VALUES(2,'other@example.com','unused',1)`); err != nil {
		t.Fatal(err)
	}
	other := s.token(2)
	path := "/api/v1/devices/qq-a/qq-authorization"
	assertStatus := func(method, url, token string, data any, expected int) json.RawMessage {
		t.Helper()
		response := callAPI(t, s, method, url, token, data)
		if response.Code != expected {
			t.Fatalf("%s %s: got HTTP %d, want %d", method, url, response.Code, expected)
		}
		return response.Body.Bytes()
	}

	assertStatus(http.MethodGet, path, "", nil, http.StatusUnauthorized)
	assertStatus(http.MethodPost, path, other, map[string]int{"slot": 5}, http.StatusNotFound)
	assertStatus(http.MethodPost, path, browser, map[string]int{"slot": 0}, http.StatusBadRequest)

	var requested qqAuthorization
	if err := json.Unmarshal(assertStatus(http.MethodPost, path, browser, map[string]int{"slot": 5}, http.StatusCreated), &requested); err != nil ||
		requested.Status != "pending" || requested.Slot != 5 || requested.ID == "" {
		t.Fatal("authorization was not queued for the selected slot", err)
	}
	assertStatus(http.MethodPost, path, browser, map[string]int{"slot": 6}, http.StatusConflict)

	agent := path + "/agent"
	var claim struct {
		ID               string
		RemainingSeconds int
	}
	if err := json.Unmarshal(assertStatus(http.MethodPost, agent, device, nil, http.StatusOK), &claim); err != nil ||
		claim.ID != requested.ID || claim.RemainingSeconds <= 0 {
		t.Fatal("device could not claim the pending authorization", err)
	}
	if body := assertStatus(http.MethodPost, agent, device, nil, http.StatusOK); string(bytes.TrimSpace(body)) != "{}" {
		t.Fatal("the same request was claimed twice")
	}

	var pngBuffer bytes.Buffer
	if err := png.Encode(&pngBuffer, image.NewGray(image.Rect(0, 0, 128, 128))); err != nil {
		t.Fatal(err)
	}
	qr := base64.StdEncoding.EncodeToString(pngBuffer.Bytes())
	update := map[string]string{"id": requested.ID, "status": "scan", "qr": qr}
	assertStatus(http.MethodPut, agent, browser, update, http.StatusConflict)
	assertStatus(http.MethodPut, agent, device, map[string]string{"id": requested.ID, "status": "scan", "qr": "not-png"}, http.StatusBadRequest)
	assertStatus(http.MethodPut, agent, device, update, http.StatusOK)

	var visible qqAuthorization
	if err := json.Unmarshal(assertStatus(http.MethodGet, path, browser, nil, http.StatusOK), &visible); err != nil ||
		visible.QR != qr || visible.QRUntil <= time.Now().Unix() {
		t.Fatal("QR image unavailable to the owning browser", err)
	}
	var unrelated qqAuthorization
	if err := json.Unmarshal(assertStatus(http.MethodGet, "/api/v1/devices/qq-b/qq-authorization", browser, nil, http.StatusOK), &unrelated); err != nil ||
		unrelated.ID != "" || unrelated.QR != "" {
		t.Fatal("authorization leaked across devices", err)
	}
	assertStatus(http.MethodPut, agent, device, map[string]string{"id": requested.ID, "status": "completed"}, http.StatusOK)
	visible = qqAuthorization{}
	if err := json.Unmarshal(assertStatus(http.MethodGet, path, browser, nil, http.StatusOK), &visible); err != nil ||
		visible.Status != "completed" || visible.QR != "" || visible.QRUntil != 0 {
		t.Fatal("QR image remained available after completion", err)
	}
}
