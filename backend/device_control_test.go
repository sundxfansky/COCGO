package main

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"
)

func TestDeviceCommandsRequireOnlineOwnedDeviceAndAreClaimedOnce(t *testing.T) {
	instance := testServer(t)
	web := initializeTestServer(t, instance)
	agent := instance.token(1)
	if _, err := instance.db.Exec(`INSERT INTO users(id,email,password_hash,verified) VALUES(2,'other@example.com','unused',1)`); err != nil {
		t.Fatal(err)
	}
	other := instance.token(2)
	registerTestDevice(t, instance, web, "phone", "Phone")
	const controls = "/api/v1/devices/phone/control"
	const heartbeat = controls + "/agent"
	call := func(method, path, token string, input any, status int) []byte {
		t.Helper()
		result := callAPI(t, instance, method, path, token, input)
		if result.Code != status {
			t.Fatalf("%s %s: got %d, want %d (%s)", method, path, result.Code, status, result.Body)
		}
		return result.Body.Bytes()
	}
	readState := func(body []byte) deviceControl {
		t.Helper()
		var value deviceControl
		if err := json.Unmarshal(body, &value); err != nil {
			t.Fatal(err)
		}
		return value
	}

	start := controlRequest{Action: "start"}
	call(http.MethodGet, controls, "", nil, http.StatusUnauthorized)
	call(http.MethodGet, controls, other, nil, http.StatusNotFound)
	call(http.MethodPost, controls, web, start, http.StatusConflict)
	ready := deviceRuntimeState{Ready: true, NextAccount: 2}
	call(http.MethodPost, heartbeat, agent, ready, http.StatusOK)
	call(http.MethodPost, controls, web, controlRequest{Action: "switch", Account: 3}, http.StatusConflict)
	command := readState(call(http.MethodPost, controls, web, controlRequest{Action: "switch", Account: 2}, http.StatusAccepted)).Command
	if command == nil || command.Status != "pending" {
		t.Fatalf("command not pending: %+v", command)
	}
	call(http.MethodPost, controls, web, start, http.StatusConflict)
	claimed := readState(call(http.MethodPost, heartbeat, agent, ready, http.StatusOK)).Command
	if claimed == nil || claimed.ID != command.ID || claimed.Status != "running" {
		t.Fatalf("command not claimed: %+v", claimed)
	}
	if replay := readState(call(http.MethodPost, heartbeat, agent, ready, http.StatusOK)); replay.Command != nil {
		t.Fatal("agent received a command twice")
	}
	result := map[string]any{"id": claimed.ID, "status": "completed", "runtime": ready}
	call(http.MethodPut, heartbeat, web, result, http.StatusConflict)
	call(http.MethodPut, heartbeat, agent, result, http.StatusOK)
	call(http.MethodPut, heartbeat, agent, result, http.StatusOK)
	state := readState(call(http.MethodGet, controls, web, nil, http.StatusOK))
	if !state.Online || state.Command.Status != "completed" {
		t.Fatalf("device did not report completion: %+v", state)
	}
	testDeviceVersion(t, instance, web, "phone", 0)

	// An expired command can never start automation after connectivity returns.
	queued := readState(call(http.MethodPost, controls, web, start, http.StatusAccepted))
	key := deviceControlKey{owner: 1, device: "phone"}
	instance.deviceControls.devices[key].Command.deadline = time.Now().Add(-time.Second)
	if expired := readState(call(http.MethodPost, heartbeat, agent, ready, http.StatusOK)); expired.Command != nil {
		t.Fatal("expired command was delivered")
	}
	expired := readState(call(http.MethodGet, controls, web, nil, http.StatusOK)).Command
	if expired.ID != queued.Command.ID || expired.Status != "expired" {
		t.Fatalf("command did not expire: %+v", expired)
	}
}
