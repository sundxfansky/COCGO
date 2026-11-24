package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"sync"
	"time"
)

const (
	controlOnlineWindow = 18 * time.Second
	controlDeliveryTTL  = 15 * time.Second
	controlExecutionTTL = 120 * time.Second
)

type deviceRuntimeState struct {
	Ready           bool `json:"ready"`
	Playing         bool `json:"playing"`
	AutoRun         bool `json:"autoRun"`
	Busy            bool `json:"busy"`
	PreviousAccount int  `json:"previousAccount,omitempty"`
	NextAccount     int  `json:"nextAccount,omitempty"`
}

type controlRequest struct {
	Action  string `json:"action"`
	Account int    `json:"account,omitempty"`
}

type deviceCommand struct {
	controlRequest
	ID      string `json:"id"`
	Status  string `json:"status"`
	Message string `json:"message,omitempty"`

	deadline time.Time
	claimant string
}

type deviceControl struct {
	Runtime   deviceRuntimeState `json:"runtime"`
	Command   *deviceCommand     `json:"command,omitempty"`
	Online    bool               `json:"online"`
	lastSeen  time.Time
	updatedAt time.Time
}

type deviceControlKey struct {
	owner  int
	device string
}

type deviceControls struct {
	mu      sync.Mutex
	devices map[deviceControlKey]deviceControl
}

func readControlInput(r *http.Request, value any) bool {
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	return decoder.Decode(value) == nil && decoder.Decode(&struct{}{}) == io.EOF
}

func (s *server) handleDeviceControl(w http.ResponseWriter, r *http.Request) {
	s.controlDevice(w, r, false)
}

func (s *server) handleDeviceControlAgent(w http.ResponseWriter, r *http.Request) {
	s.controlDevice(w, r, true)
}

func (s *server) controlDevice(w http.ResponseWriter, r *http.Request, agent bool) {
	owner, device, ok := s.authorizeDevice(w, r)
	if !ok {
		return
	}
	var request controlRequest
	var runtime deviceRuntimeState
	var result struct {
		ID      string             `json:"id"`
		Status  string             `json:"status"`
		Message string             `json:"message"`
		Runtime deviceRuntimeState `json:"runtime"`
	}
	valid := true
	switch {
	case agent && r.Method == http.MethodPut:
		valid = readControlInput(r, &result) && len(result.ID) == 32 && len(result.Message) <= 400 &&
			(result.Status == "completed" || result.Status == "failed") && result.Runtime.valid()
	case agent:
		valid = readControlInput(r, &runtime) && runtime.valid()
	case r.Method == http.MethodPost:
		valid = readControlInput(r, &request) && request.valid()
	}
	if !valid {
		jsonOut(w, http.StatusBadRequest, map[string]string{"error": "设备控制参数无效"})
		return
	}

	state := &s.deviceControls
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.devices == nil {
		state.devices = make(map[deviceControlKey]deviceControl)
	}
	now := time.Now()
	key := deviceControlKey{owner, device}
	current := state.devices[key]
	command := current.Command
	if command.active() && !now.Before(command.deadline) {
		command.Status = "expired"
	}
	current.Online = now.Sub(current.lastSeen) <= controlOnlineWindow

	switch {
	case agent && r.Method == http.MethodPost:
		if current.updatedAt.IsZero() {
			// Keep status only for connected clients and discard long-idle entries.
			for key, entry := range state.devices {
				if now.Sub(entry.updatedAt) > 24*time.Hour {
					delete(state.devices, key)
				}
			}
		}
		current.lastSeen, current.updatedAt = now, now
		current.Runtime = runtime
		state.devices[key] = current
		if command != nil && command.Status == "pending" {
			// Delivery is single-claim. A lost response must never replay a switch or exit.
			command.Status = "running"
			command.claimant = tokenHash(bearer(r))
			command.deadline = now.Add(controlExecutionTTL)
			jsonOut(w, http.StatusOK, map[string]any{"command": command})
		} else {
			jsonOut(w, http.StatusOK, map[string]any{})
		}
	case agent:
		if command == nil || command.ID != result.ID || command.claimant != tokenHash(bearer(r)) {
			jsonOut(w, http.StatusConflict, map[string]string{"error": "操作已过期或已结束"})
			return
		}
		if command.Status == result.Status {
			// A retry can acknowledge a result that was stored before its reply was lost.
			jsonOut(w, http.StatusOK, map[string]bool{"saved": true})
			return
		}
		if command.Status != "running" {
			jsonOut(w, http.StatusConflict, map[string]string{"error": "操作已过期或已结束"})
			return
		}
		command.Status, command.Message = result.Status, result.Message
		current.lastSeen, current.updatedAt = now, now
		current.Runtime = result.Runtime
		if command.Action == "end" && result.Status == "completed" {
			// The agent exits immediately after receiving the acknowledgement.
			current.lastSeen = time.Time{}
		}
		state.devices[key] = current
		jsonOut(w, http.StatusOK, map[string]bool{"saved": true})
	case r.Method == http.MethodPost:
		if !current.Online || !current.Runtime.Ready || current.Runtime.Busy || command.active() {
			jsonOut(w, http.StatusConflict, map[string]string{"error": "设备离线、未就绪或正在执行其他操作"})
			return
		}
		if request.Action == "switch" && request.Account != current.Runtime.PreviousAccount && request.Account != current.Runtime.NextAccount {
			jsonOut(w, http.StatusConflict, map[string]string{"error": "目标账号当前不可快速切换"})
			return
		}
		var nonce [16]byte
		if _, err := rand.Read(nonce[:]); err != nil {
			jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "无法发送设备操作"})
			return
		}
		current.Command = &deviceCommand{controlRequest: request, ID: hex.EncodeToString(nonce[:]),
			Status: "pending", deadline: now.Add(controlDeliveryTTL)}
		state.devices[key] = current
		jsonOut(w, http.StatusAccepted, current)
	default:
		jsonOut(w, http.StatusOK, current)
	}
}

func (command *deviceCommand) active() bool {
	return command != nil && (command.Status == "pending" || command.Status == "running")
}

func (request controlRequest) valid() bool {
	switch request.Action {
	case "start", "pause", "end":
		return request.Account == 0
	case "switch":
		return request.Account >= 1 && request.Account <= 10000
	}
	return false
}

func (runtime deviceRuntimeState) valid() bool {
	return runtime.PreviousAccount >= 0 && runtime.PreviousAccount <= 10000 &&
		runtime.NextAccount >= 0 && runtime.NextAccount <= 10000
}
