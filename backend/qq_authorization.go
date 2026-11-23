package main

import (
	"bytes"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"image/png"
	"net/http"
	"sync"
	"time"
)

const qqAuthorizationDuration = 5 * time.Minute

type qqAuthorizationKey struct {
	owner  int
	device string
}

type qqAuthorization struct {
	ID        string `json:"id"`
	Slot      int    `json:"slot"`
	Status    string `json:"status"`
	ExpiresAt int64  `json:"expiresAt"`
	QR        string `json:"qr,omitempty"`
	QRUntil   int64  `json:"qrUntil,omitempty"`
	claimant  string
}

func (a qqAuthorization) active() bool {
	return a.Status == "pending" || a.Status == "running"
}

// Authorization QR codes are short-lived and never become part of a saved configuration.
type qqAuthorizations struct {
	mu       sync.Mutex
	requests map[qqAuthorizationKey]qqAuthorization
}

func (s *server) qqAuthorization(w http.ResponseWriter, r *http.Request) {
	s.handleQqAuthorization(w, r, false)
}

func (s *server) qqAuthorizationAgent(w http.ResponseWriter, r *http.Request) {
	s.handleQqAuthorization(w, r, true)
}

func (s *server) handleQqAuthorization(w http.ResponseWriter, r *http.Request, agent bool) {
	owner, ok := s.auth(r)
	if !ok {
		jsonOut(w, http.StatusUnauthorized, map[string]string{"error": "请重新登录"})
		return
	}
	device := r.PathValue("device")
	if _, err := s.readDevice(owner, device); err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, sql.ErrNoRows) {
			status = http.StatusNotFound
		}
		jsonOut(w, status, map[string]string{"error": "设备不可用"})
		return
	}

	var input struct {
		ID     string `json:"id"`
		Slot   int    `json:"slot"`
		Status string `json:"status"`
		QR     string `json:"qr"`
	}
	if (!agent && r.Method == http.MethodPost) || r.Method == http.MethodPut {
		if json.NewDecoder(r.Body).Decode(&input) != nil {
			jsonOut(w, http.StatusBadRequest, map[string]string{"error": "无效的授权请求"})
			return
		}
		if (!agent && (input.Slot < 1 || input.Slot > 10000)) ||
			(agent && !validQqAuthorizationUpdate(input.Status, input.QR)) {
			jsonOut(w, http.StatusBadRequest, map[string]string{"error": "无效的授权参数"})
			return
		}
	}

	state := &s.qqAuthorizations
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.requests == nil {
		state.requests = make(map[qqAuthorizationKey]qqAuthorization)
	}
	now := time.Now()
	key := qqAuthorizationKey{owner, device}
	current := state.requests[key]
	if current.active() && current.ExpiresAt <= now.Unix() {
		current.Status, current.QR, current.QRUntil = "expired", "", 0
		state.requests[key] = current
	}
	if agent {
		claimant := tokenHash(bearer(r))
		if r.Method == http.MethodPost {
			if current.Status != "pending" {
				jsonOut(w, http.StatusOK, map[string]any{})
				return
			}
			current.Status, current.claimant = "running", claimant
			state.requests[key] = current
			jsonOut(w, http.StatusOK, map[string]any{
				"id": current.ID, "slot": current.Slot, "remainingSeconds": current.ExpiresAt - now.Unix(),
			})
			return
		}
		if current.ID != input.ID || current.Status != "running" || current.claimant != claimant {
			jsonOut(w, http.StatusConflict, map[string]string{"error": "授权请求已结束或已被替换"})
			return
		}
		if input.Status == "scan" {
			current.QR = input.QR
			current.QRUntil = min(now.Add(75*time.Second).Unix(), current.ExpiresAt)
			deadline := current.QRUntil
			id := current.ID
			time.AfterFunc(time.Until(time.Unix(deadline, 0)), func() {
				state.mu.Lock()
				defer state.mu.Unlock()
				request := state.requests[key]
				if request.ID == id && request.QRUntil == deadline {
					request.QR = ""
					state.requests[key] = request
				}
			})
		} else {
			current.Status, current.QR, current.QRUntil = input.Status, "", 0
		}
		state.requests[key] = current
		jsonOut(w, http.StatusOK, map[string]any{})
		return
	}

	if r.Method == http.MethodPost {
		if current.active() {
			jsonOut(w, http.StatusConflict, map[string]string{"error": "这台设备已有正在进行的 QQ 授权"})
			return
		}
		for oldKey, old := range state.requests {
			if old.ExpiresAt+300 <= now.Unix() {
				delete(state.requests, oldKey)
			}
		}
		if len(state.requests) >= 1024 {
			jsonOut(w, http.StatusServiceUnavailable, map[string]string{"error": "授权请求过多，请稍后重试"})
			return
		}
		var nonce [16]byte
		if _, err := rand.Read(nonce[:]); err != nil {
			jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "无法建立授权请求"})
			return
		}
		current = qqAuthorization{
			ID: hex.EncodeToString(nonce[:]), Slot: input.Slot, Status: "pending",
			ExpiresAt: now.Add(qqAuthorizationDuration).Unix(),
		}
		state.requests[key] = current
		jsonOut(w, http.StatusCreated, current)
		return
	}
	if current.QR != "" && current.QRUntil <= now.Unix() {
		current.QR = ""
		state.requests[key] = current
	}
	jsonOut(w, http.StatusOK, current)
}

func validQqAuthorizationUpdate(status, qr string) bool {
	switch status {
	case "completed", "failed":
		return qr == ""
	case "scan":
		if len(qr) == 0 || len(qr) > 32<<10 {
			return false
		}
		data, err := base64.StdEncoding.DecodeString(qr)
		if err != nil {
			return false
		}
		image, err := png.DecodeConfig(bytes.NewReader(data))
		return err == nil && image.Width == image.Height && image.Width >= 96 && image.Width <= 512
	default:
		return false
	}
}
