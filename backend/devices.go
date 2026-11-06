package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

const legacyDeviceID = "default"

var deviceIDPattern = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)

type deviceInfo struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Version   int    `json:"version"`
	UpdatedAt int64  `json:"updatedAt"`
}

// Keep legacy rows as the default device's canonical storage, so older clients cannot fork its history.
func initDevices(db *sql.DB) error {
	_, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS devices(
			user_id INTEGER NOT NULL,
			id TEXT NOT NULL,
			name TEXT NOT NULL,
			updated_at INTEGER NOT NULL,
			PRIMARY KEY(user_id,id),
			FOREIGN KEY(user_id) REFERENCES users(id)
		);
		CREATE TABLE IF NOT EXISTS device_configs(
			user_id INTEGER NOT NULL,
			device_id TEXT NOT NULL,
			version INTEGER NOT NULL DEFAULT 0,
			payload TEXT NOT NULL DEFAULT '{}',
			updated_at INTEGER NOT NULL,
			PRIMARY KEY(user_id,device_id),
			FOREIGN KEY(user_id,device_id) REFERENCES devices(user_id,id)
		);
		INSERT OR IGNORE INTO devices(user_id,id,name,updated_at)
			SELECT user_id,'default','默认设备',updated_at FROM configs;
		CREATE TRIGGER IF NOT EXISTS register_legacy_config_device AFTER INSERT ON configs
		BEGIN
			INSERT OR IGNORE INTO devices(user_id,id,name,updated_at)
				VALUES(NEW.user_id,'default','默认设备',NEW.updated_at);
		END;`)
	return err
}

// This projection intentionally excludes payloads from the device switcher and version checks.
const deviceProjection = `SELECT d.id,d.name,
	CASE WHEN d.id='default' THEN COALESCE(c.version,0) ELSE COALESCE(dc.version,0) END,
	MAX(d.updated_at,CASE WHEN d.id='default' THEN COALESCE(c.updated_at,0) ELSE COALESCE(dc.updated_at,0) END)
	FROM devices d
	LEFT JOIN configs c ON c.user_id=d.user_id AND d.id='default'
	LEFT JOIN device_configs dc ON dc.user_id=d.user_id AND dc.device_id=d.id
	WHERE d.user_id=?`

func (s *server) readDevice(owner int, id string) (deviceInfo, error) {
	var device deviceInfo
	err := s.db.QueryRow(deviceProjection+` AND d.id=?`, owner, id).
		Scan(&device.ID, &device.Name, &device.Version, &device.UpdatedAt)
	return device, err
}

func validDeviceName(name string) bool {
	if name == "" || utf8.RuneCountInString(name) > 80 {
		return false
	}
	for _, char := range name {
		if unicode.IsControl(char) {
			return false
		}
	}
	return true
}

func (s *server) devices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		w.Header().Set("Allow", "GET, POST")
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	owner, ok := s.auth(r)
	if !ok {
		jsonOut(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	if r.Method == http.MethodGet {
		rows, err := s.db.Query(deviceProjection+` ORDER BY d.updated_at,d.id`, owner)
		if err != nil {
			jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
			return
		}
		defer rows.Close()
		devices := make([]deviceInfo, 0)
		for rows.Next() {
			var device deviceInfo
			if err := rows.Scan(&device.ID, &device.Name, &device.Version, &device.UpdatedAt); err != nil {
				jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
				return
			}
			devices = append(devices, device)
		}
		if rows.Err() != nil {
			jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
			return
		}
		jsonOut(w, http.StatusOK, map[string]any{"devices": devices})
		return
	}
	var input struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	}
	if json.NewDecoder(r.Body).Decode(&input) != nil {
		jsonOut(w, http.StatusBadRequest, map[string]string{"error": "invalid device"})
		return
	}
	input.Name = strings.TrimSpace(input.Name)
	if !validDeviceName(input.Name) || (input.ID != "" && !deviceIDPattern.MatchString(input.ID)) {
		jsonOut(w, http.StatusBadRequest, map[string]string{"error": "设备名称须为 1–80 个字符，设备标识仅可使用字母、数字、下划线或连字符（最多 64 个）"})
		return
	}
	if input.ID == "" {
		buffer := make([]byte, 16)
		if _, err := rand.Read(buffer); err != nil {
			jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "device ID generation failed"})
			return
		}
		input.ID = hex.EncodeToString(buffer)
	}
	// Reconnecting an installation registers it idempotently without replacing a user-chosen name.
	result, err := s.db.Exec(`INSERT INTO devices(user_id,id,name,updated_at) VALUES(?,?,?,?) ON CONFLICT(user_id,id) DO NOTHING`, owner, input.ID, input.Name, time.Now().Unix())
	if err != nil {
		jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
		return
	}
	device, err := s.readDevice(owner, input.ID)
	if err != nil {
		jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
		return
	}
	status := http.StatusOK
	if inserted, _ := result.RowsAffected(); inserted == 1 {
		status = http.StatusCreated
	}
	jsonOut(w, status, map[string]any{"device": device})
}

func (s *server) renameDevice(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPatch {
		w.Header().Set("Allow", http.MethodPatch)
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	owner, deviceID, ok := s.authorizeDevice(w, r)
	if !ok {
		return
	}
	var input struct{ Name string }
	if json.NewDecoder(r.Body).Decode(&input) != nil {
		jsonOut(w, http.StatusBadRequest, map[string]string{"error": "invalid device name"})
		return
	}
	input.Name = strings.TrimSpace(input.Name)
	if !validDeviceName(input.Name) {
		jsonOut(w, http.StatusBadRequest, map[string]string{"error": "设备名称须为 1–80 个字符"})
		return
	}
	if _, err := s.db.Exec(`UPDATE devices SET name=?,updated_at=? WHERE user_id=? AND id=?`, input.Name, time.Now().Unix(), owner, deviceID); err != nil {
		jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
		return
	}
	device, err := s.readDevice(owner, deviceID)
	if err != nil {
		jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
		return
	}
	jsonOut(w, http.StatusOK, map[string]any{"device": device})
}

// Scope existence checks by the authenticated owner; another owner's ID is indistinguishable from a missing ID.
func (s *server) authorizeDevice(w http.ResponseWriter, r *http.Request) (int, string, bool) {
	owner, ok := s.auth(r)
	if !ok {
		jsonOut(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return 0, "", false
	}
	deviceID := r.PathValue("device")
	if !deviceIDPattern.MatchString(deviceID) {
		jsonOut(w, http.StatusNotFound, map[string]string{"error": "device not found"})
		return 0, "", false
	}
	var found int
	err := s.db.QueryRow(`SELECT 1 FROM devices WHERE user_id=? AND id=?`, owner, deviceID).Scan(&found)
	if err == sql.ErrNoRows {
		jsonOut(w, http.StatusNotFound, map[string]string{"error": "device not found"})
		return 0, "", false
	}
	if err != nil {
		jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
		return 0, "", false
	}
	return owner, deviceID, true
}

func (s *server) deviceConfig(w http.ResponseWriter, r *http.Request) {
	owner, deviceID, ok := s.authorizeDevice(w, r)
	if ok {
		s.serveConfig(w, r, owner, deviceID)
	}
}

func (s *server) deviceConfigVersion(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	owner, deviceID, ok := s.authorizeDevice(w, r)
	if ok {
		s.serveConfigVersion(w, r, owner, deviceID)
	}
}
