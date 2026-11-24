package main

import (
	"encoding/json"
	"net/http"
	"testing"
)

func TestVillageSnapshotsAreScopedAndDoNotChangeConfiguration(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	if _, err := instance.db.Exec(`INSERT INTO users(id,email,password_hash,verified) VALUES(2,'second@example.com','unused',1)`); err != nil {
		t.Fatal(err)
	}
	other := instance.token(2)
	registerTestDevice(t, instance, token, "phone", "Phone")
	registerTestDevice(t, instance, other, "phone", "Other phone")
	const collection = "/api/v1/devices/phone/villages"
	path := collection + "/global/1"
	do := func(method, path, auth string, payload any, expected int) []byte {
		t.Helper()
		response := callAPI(t, instance, method, path, auth, payload)
		if response.Code != expected {
			t.Fatalf("%s %s: got %d, want %d. %s", method, path, response.Code, expected, response.Body.String())
		}
		return response.Body.Bytes()
	}
	snapshot := json.RawMessage(`{"tag":"#TEST123","townHall":17,"items":[{"group":"buildings","id":1000001,"level":17,"count":1}]}`)
	do(http.MethodGet, path, token, nil, http.StatusNotFound)
	do(http.MethodPut, path, "", snapshot, http.StatusUnauthorized)
	do(http.MethodPut, path, token, snapshot, http.StatusOK)
	do(http.MethodPut, collection+"/cn/2", token, snapshot, http.StatusOK)
	do(http.MethodPut, path, token, json.RawMessage(`{"townHall":99,"items":[],"session":"private"}`), http.StatusBadRequest)
	do(http.MethodGet, path, other, nil, http.StatusNotFound)
	do(http.MethodGet, collection+"/global/0", token, nil, http.StatusNotFound)

	var selected villageSnapshot
	if err := json.Unmarshal(do(http.MethodGet, path, token, nil, http.StatusOK), &selected); err != nil || selected.TownHall != 17 {
		t.Fatalf("unexpected snapshot: %+v (%v)", selected, err)
	}
	var list struct{ Exports []villageSlot }
	if err := json.Unmarshal(do(http.MethodGet, collection, token, nil, http.StatusOK), &list); err != nil ||
		len(list.Exports) != 2 || list.Exports[0] != (villageSlot{Region: "global", Account: 1}) {
		t.Fatalf("unexpected list: %+v (%v)", list, err)
	}
	if err := json.Unmarshal(do(http.MethodGet, collection, other, nil, http.StatusOK), &list); err != nil || len(list.Exports) != 0 {
		t.Fatalf("unexpected other owner's list: %+v (%v)", list, err)
	}
	testDeviceVersion(t, instance, token, "phone", 0)
}
