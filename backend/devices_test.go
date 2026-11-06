package main

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"testing"
)

func registerTestDevice(t *testing.T, instance *server, token, id, name string) deviceInfo {
	t.Helper()
	response := callAPI(t, instance, http.MethodPost, "/api/v1/devices", token, map[string]string{"id": id, "name": name})
	var body struct{ Device deviceInfo }
	if (response.Code != http.StatusCreated && response.Code != http.StatusOK) || json.Unmarshal(response.Body.Bytes(), &body) != nil || body.Device.ID == "" {
		t.Fatalf("register device: %d %s", response.Code, response.Body.String())
	}
	return body.Device
}

func testDeviceVersion(t *testing.T, instance *server, token, id string, expected int) {
	t.Helper()
	response := callAPI(t, instance, http.MethodGet, "/api/v1/devices/"+id+"/config/version", token, nil)
	var body map[string]int
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &body) != nil || len(body) != 1 || body["version"] != expected {
		t.Fatalf("device version: %d %s", response.Code, response.Body.String())
	}
	if response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("device version may be cached: %v", response.Header())
	}
}

func TestDeviceConfigurationsAndVersionsAreIndependent(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	registerTestDevice(t, instance, token, "phone-a", "手机 A")
	registerTestDevice(t, instance, token, "phone-b", "手机 B")
	for _, id := range []string{"phone-a", "phone-b"} {
		testDeviceVersion(t, instance, token, id, 0)
		response := callAPI(t, instance, http.MethodGet, "/api/v1/devices/"+id+"/config", token, nil)
		var body struct {
			Config map[string]string
		}
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &body) != nil || body.Config == nil || len(body.Config) != 0 {
			t.Fatalf("new device did not start empty: %d %s", response.Code, response.Body.String())
		}
	}
	for revision := 0; revision < 2; revision++ {
		response := callAPI(t, instance, http.MethodPut, "/api/v1/devices/phone-a/config", token, configRequest{Config: map[string]string{"device_remark": "A"}}, map[string]string{"If-Match": strconv.Itoa(revision)})
		if response.Code != http.StatusOK {
			t.Fatalf("save A: %d %s", response.Code, response.Body.String())
		}
		testDeviceVersion(t, instance, token, "phone-a", revision+1)
		testDeviceVersion(t, instance, token, "phone-b", 0)
	}
	response := callAPI(t, instance, http.MethodPut, "/api/v1/devices/phone-b/config", token, configRequest{Config: map[string]string{"device_remark": "B"}}, map[string]string{"If-Match": "0"})
	if response.Code != http.StatusOK {
		t.Fatalf("save B: %d %s", response.Code, response.Body.String())
	}
	for id, expected := range map[string]string{"phone-a": "A", "phone-b": "B"} {
		response := callAPI(t, instance, http.MethodGet, "/api/v1/devices/"+id+"/config", token, nil)
		var body struct{ Config map[string]string }
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &body) != nil || body.Config["device_remark"] != expected {
			t.Fatalf("device settings leaked: %d %s", response.Code, response.Body.String())
		}
	}
	response = callAPI(t, instance, http.MethodGet, "/api/v1/devices/phone-a/config", token, nil, map[string]string{"If-None-Match": "2"})
	if response.Code != http.StatusNotModified || response.Body.Len() != 0 {
		t.Fatalf("conditional get: %d %s", response.Code, response.Body.String())
	}
	for _, headers := range []map[string]string{nil, {"If-Match": "1"}} {
		response := callAPI(t, instance, http.MethodPut, "/api/v1/devices/phone-a/config", token, configRequest{Config: map[string]string{}}, headers)
		expected := http.StatusConflict
		if headers == nil {
			expected = http.StatusPreconditionRequired
		}
		if response.Code != expected {
			t.Fatalf("unsafe save accepted: %d %s", response.Code, response.Body.String())
		}
	}
	response = callAPI(t, instance, http.MethodGet, "/api/v1/devices", token, nil)
	var list struct{ Devices []deviceInfo }
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &list) != nil || len(list.Devices) != 2 {
		t.Fatalf("device list: %d %s", response.Code, response.Body.String())
	}
	for _, device := range list.Devices {
		expected := 1
		if device.ID == "phone-a" {
			expected = 2
		}
		if device.Version != expected || device.UpdatedAt <= 0 {
			t.Fatalf("incorrect device summary: %+v", device)
		}
	}
	// Named devices must not silently publish settings through the legacy default endpoint.
	response = callAPI(t, instance, http.MethodGet, "/api/v1/config/version", token, nil)
	if response.Body.String() != "{\"version\":0}\n" {
		t.Fatalf("named device contaminated legacy config: %s", response.Body.String())
	}
}

func TestDeviceOwnershipIsCheckedForEveryOperation(t *testing.T) {
	instance := testServer(t)
	first := initializeTestServer(t, instance)
	if _, err := instance.db.Exec(`INSERT INTO users(id,email,password_hash,verified) VALUES(2,'second@example.com','unused',1)`); err != nil {
		t.Fatal(err)
	}
	second := instance.token(2)
	registerTestDevice(t, instance, first, "same-id", "Owner one")
	for _, call := range []struct{ method, path string }{
		{http.MethodGet, "/api/v1/devices/same-id/config"},
		{http.MethodGet, "/api/v1/devices/same-id/config/version"},
		{http.MethodPut, "/api/v1/devices/same-id/config"},
		{http.MethodPatch, "/api/v1/devices/same-id"},
	} {
		if response := callAPI(t, instance, call.method, call.path, second, nil); response.Code != http.StatusNotFound {
			t.Fatalf("another owner accessed %s: %d %s", call.path, response.Code, response.Body.String())
		}
		if response := callAPI(t, instance, call.method, call.path, "", nil); response.Code != http.StatusUnauthorized {
			t.Fatalf("anonymous device access: %d", response.Code)
		}
	}
	response := callAPI(t, instance, http.MethodGet, "/api/v1/devices", second, nil)
	if response.Body.String() != "{\"devices\":[]}\n" {
		t.Fatalf("device list leaked ownership: %s", response.Body.String())
	}
	registerTestDevice(t, instance, second, "same-id", "Owner two")
	response = callAPI(t, instance, http.MethodPut, "/api/v1/devices/same-id/config", second, configRequest{Config: map[string]string{"owner": "two"}}, map[string]string{"If-Match": "0"})
	if response.Code != http.StatusOK {
		t.Fatalf("second owner save: %d %s", response.Code, response.Body.String())
	}
	testDeviceVersion(t, instance, first, "same-id", 0)
	testDeviceVersion(t, instance, second, "same-id", 1)
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		if response := callAPI(t, instance, method, "/api/v1/devices", "", nil); response.Code != http.StatusUnauthorized {
			t.Fatalf("anonymous device collection access: %d", response.Code)
		}
	}
}

func TestDeviceRegistrationValidationIdempotenceAndRename(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	for _, input := range []map[string]string{
		{"id": "bad/id", "name": "Phone"}, {"id": strings.Repeat("a", 65), "name": "Phone"},
		{"id": "phone", "name": "  "}, {"id": "phone", "name": strings.Repeat("机", 81)},
		{"id": "phone", "name": "A\x00B"}, {"id": "phone", "name": "A\nB"},
	} {
		if response := callAPI(t, instance, http.MethodPost, "/api/v1/devices", token, input); response.Code != http.StatusBadRequest {
			t.Fatalf("invalid device accepted: %+v: %d", input, response.Code)
		}
	}
	device := registerTestDevice(t, instance, token, "", "  手机  ")
	if !deviceIDPattern.MatchString(device.ID) || device.Name != "手机" {
		t.Fatalf("invalid generated device: %+v", device)
	}
	device = registerTestDevice(t, instance, token, device.ID, "New connection name")
	if device.Name != "手机" {
		t.Fatal("reconnect replaced the saved name")
	}
	response := callAPI(t, instance, http.MethodPatch, "/api/v1/devices/"+device.ID, token, map[string]string{"name": "改名后的手机"})
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "改名后的手机") {
		t.Fatalf("rename failed: %d %s", response.Code, response.Body.String())
	}
	testDeviceVersion(t, instance, token, device.ID, 0)
	if response := callAPI(t, instance, http.MethodPatch, "/api/v1/devices/"+device.ID, token, map[string]string{"name": ""}); response.Code != http.StatusBadRequest {
		t.Fatal("empty name accepted")
	}
	for _, request := range []struct{ method, path string }{
		{http.MethodDelete, "/api/v1/devices"},
		{http.MethodPut, "/api/v1/devices/" + device.ID},
		{http.MethodPost, "/api/v1/devices/" + device.ID + "/config"},
		{http.MethodPut, "/api/v1/devices/" + device.ID + "/config/version"},
	} {
		if response := callAPI(t, instance, request.method, request.path, token, nil); response.Code != http.StatusMethodNotAllowed {
			t.Fatalf("unsupported method accepted: %s %s: %d", request.method, request.path, response.Code)
		}
	}
}

func TestLegacyDeviceMigrationSharesOneVersionHistory(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	// Simulate a database written by a release predating the devices table and trigger.
	if _, err := instance.db.Exec(`DROP TRIGGER register_legacy_config_device; INSERT INTO configs(user_id,version,payload,updated_at) VALUES(1,7,'{"setting":"old"}',123)`); err != nil {
		t.Fatal(err)
	}
	if err := initDevices(instance.db); err != nil {
		t.Fatal(err)
	}
	response := callAPI(t, instance, http.MethodGet, "/api/v1/devices", token, nil)
	var list struct{ Devices []deviceInfo }
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &list) != nil || len(list.Devices) != 1 || list.Devices[0].ID != "default" || list.Devices[0].Version != 7 {
		t.Fatalf("legacy device missing: %d %s", response.Code, response.Body.String())
	}
	for _, path := range []string{"/api/v1/config", "/api/v1/devices/default/config"} {
		response := callAPI(t, instance, http.MethodGet, path, token, nil)
		if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"setting":"old"`) {
			t.Fatalf("legacy contents lost: %d %s", response.Code, response.Body.String())
		}
	}
	for index, path := range []string{"/api/v1/devices/default/config", "/api/v1/config"} {
		response := callAPI(t, instance, http.MethodPut, path, token, configRequest{Config: map[string]string{"setting": "new"}}, map[string]string{"If-Match": strconv.Itoa(7 + index)})
		if response.Code != http.StatusOK {
			t.Fatalf("legacy write: %d %s", response.Code, response.Body.String())
		}
		testDeviceVersion(t, instance, token, "default", 8+index)
		response = callAPI(t, instance, http.MethodGet, "/api/v1/config/version", token, nil)
		if response.Body.String() != "{\"version\":"+strconv.Itoa(8+index)+"}\n" {
			t.Fatalf("version histories diverged: %s", response.Body.String())
		}
	}
	response = callAPI(t, instance, http.MethodPatch, "/api/v1/devices/default", token, map[string]string{"name": "旧手机"})
	if response.Code != http.StatusOK {
		t.Fatal(response.Body.String())
	}
	if err := initDevices(instance.db); err != nil {
		t.Fatal(err)
	}
	device, err := instance.readDevice(1, "default")
	if err != nil || device.Name != "旧手机" || device.Version != 9 {
		t.Fatalf("repeated migration reset settings: %+v %v", device, err)
	}
}

func TestConcurrentDeviceSavesAndRegistrations(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	const count = 8
	codes := make(chan int, count)
	var group sync.WaitGroup
	for index := 0; index < count; index++ {
		group.Add(1)
		go func() {
			defer group.Done()
			response := callAPI(t, instance, http.MethodPost, "/api/v1/devices", token, map[string]string{"id": "stable-installation", "name": "Phone"})
			codes <- response.Code
		}()
	}
	group.Wait()
	close(codes)
	created := 0
	for code := range codes {
		if code == http.StatusCreated {
			created++
		} else if code != http.StatusOK {
			t.Fatalf("registration failed: %d", code)
		}
	}
	if created != 1 {
		t.Fatalf("duplicate installation registrations: %d", created)
	}
	codes = make(chan int, count)
	for index := 0; index < count; index++ {
		group.Add(1)
		go func() {
			defer group.Done()
			response := callAPI(t, instance, http.MethodPut, "/api/v1/devices/stable-installation/config", token, configRequest{Config: map[string]string{"setting": "value"}}, map[string]string{"If-Match": "0"})
			codes <- response.Code
		}()
	}
	group.Wait()
	close(codes)
	saved := 0
	for code := range codes {
		if code == http.StatusOK {
			saved++
		} else if code != http.StatusConflict {
			t.Fatalf("concurrent save failed: %d", code)
		}
	}
	if saved != 1 {
		t.Fatalf("accepted stale concurrent writes: %d", saved)
	}
	testDeviceVersion(t, instance, token, "stable-installation", 1)
}

func TestDeviceConfigPayloadLimitMatchesClient(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	registerTestDevice(t, instance, token, "many-accounts", "多账号设备")
	path := "/api/v1/devices/many-accounts/config"
	// Configurations can exceed the general 1 MiB API limit without changing the limits of other routes.
	large := configRequest{Config: map[string]string{"setting": strings.Repeat("a", 2<<20)}}
	response := callAPI(t, instance, http.MethodPut, path, token, large, map[string]string{"If-Match": "0"})
	if response.Code != http.StatusOK {
		t.Fatalf("valid multi-account payload rejected: %d %s", response.Code, response.Body.String())
	}
	tooLarge := configRequest{Config: map[string]string{"setting": strings.Repeat("a", 8<<20)}}
	response = callAPI(t, instance, http.MethodPut, path, token, tooLarge, map[string]string{"If-Match": "1"})
	if response.Code != http.StatusBadRequest {
		t.Fatalf("oversized config accepted: %d", response.Code)
	}
	testDeviceVersion(t, instance, token, "many-accounts", 1)
}
