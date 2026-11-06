package main

import (
	"database/sql"
	"net/http"
)

// configVersion lets clients poll without loading or transferring the settings payload.
func (s *server) configVersion(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	id, ok := s.auth(r)
	if !ok {
		jsonOut(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	s.serveConfigVersion(w, r, id, legacyDeviceID)
}

func (s *server) serveConfigVersion(w http.ResponseWriter, r *http.Request, id int, deviceID string) {
	var version int
	var err error
	if deviceID == legacyDeviceID {
		err = s.db.QueryRow(`SELECT version FROM configs WHERE user_id=?`, id).Scan(&version)
	} else {
		err = s.db.QueryRow(`SELECT version FROM device_configs WHERE user_id=? AND device_id=?`, id, deviceID).Scan(&version)
	}
	if err != nil && err != sql.ErrNoRows {
		jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "database error"})
		return
	}
	// A new account has version zero until its first successful save.
	jsonOut(w, http.StatusOK, map[string]int{"version": version})
}
