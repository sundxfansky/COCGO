package main

import (
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"regexp"
	"strconv"
)

var pluginNamePattern = regexp.MustCompile(`^encrypted_([0-9]+)\.jar$`)

func (s *server) uploadPlugin(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	var input struct {
		FileName  string `json:"fileName"`
		Payload   []byte `json:"payload"`
		Signature []byte `json:"signature"`
	}
	if json.NewDecoder(r.Body).Decode(&input) != nil || len(input.Payload) < 13 || len(input.Payload) > 64*1024*1024 {
		jsonOut(w, 400, map[string]string{"error": "invalid plugin"})
		return
	}
	match := pluginNamePattern.FindStringSubmatch(input.FileName)
	if match == nil {
		jsonOut(w, 400, map[string]string{"error": "invalid plugin filename"})
		return
	}
	version, err := strconv.ParseInt(match[1], 10, 64)
	if err != nil || version <= 0 {
		jsonOut(w, 400, map[string]string{"error": "invalid plugin version"})
		return
	}
	publicBytes, err := base64.StdEncoding.DecodeString(os.Getenv("PLUGIN_PUBLIC_KEY"))
	if err != nil || len(publicBytes) == 0 {
		jsonOut(w, 503, map[string]string{"error": "PLUGIN_PUBLIC_KEY is not configured"})
		return
	}
	key, err := x509.ParsePKIXPublicKey(publicBytes)
	if err != nil {
		jsonOut(w, 503, map[string]string{"error": "invalid server public key"})
		return
	}
	rsaKey, ok := key.(*rsa.PublicKey)
	if !ok || rsaKey.N.BitLen() < 2048 {
		jsonOut(w, 503, map[string]string{"error": "RSA public key must be at least 2048 bits"})
		return
	}
	signed := append([]byte(input.FileName+"\x00"), input.Payload...)
	digest := sha256.Sum256(signed)
	if rsa.VerifyPKCS1v15(rsaKey, crypto.SHA256, digest[:], input.Signature) != nil {
		jsonOut(w, 400, map[string]string{"error": "invalid signature"})
		return
	}
	result, err := s.db.Exec(`INSERT INTO plugins(version,name,payload,signature) SELECT ?,?,?,? WHERE ?>COALESCE((SELECT MAX(version) FROM plugins),0)`, version, input.FileName, input.Payload, input.Signature, version)
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "database error"})
		return
	}
	if count, _ := result.RowsAffected(); count != 1 {
		jsonOut(w, 409, map[string]string{"error": "version must be newer than the published plugin"})
		return
	}
	// Keep a previous release available for diagnostics without unbounded database growth.
	_, _ = s.db.Exec(`DELETE FROM plugins WHERE version NOT IN(SELECT version FROM plugins ORDER BY version DESC LIMIT 2)`)
	jsonOut(w, 201, map[string]any{"version": version, "fileName": input.FileName})
}
func (s *server) latestPlugin(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.auth(r); !ok {
		jsonOut(w, 401, map[string]string{"error": "unauthorized"})
		return
	}
	var name string
	var payload, signature []byte
	var version int64
	err := s.db.QueryRow(`SELECT version,name,payload,signature FROM plugins ORDER BY version DESC LIMIT 1`).Scan(&version, &name, &payload, &signature)
	if err == sql.ErrNoRows {
		jsonOut(w, 404, map[string]string{"error": "no plugin published"})
		return
	}
	if err != nil {
		jsonOut(w, 500, map[string]string{"error": "database error"})
		return
	}
	hash := sha256.Sum256(payload)
	jsonOut(w, 200, map[string]any{"version": version, "fileName": name, "sha256": hex.EncodeToString(hash[:]), "signature": base64.StdEncoding.EncodeToString(signature)})
}
func (s *server) downloadPlugin(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.auth(r); !ok {
		jsonOut(w, 401, map[string]string{"error": "unauthorized"})
		return
	}
	version, err := strconv.ParseInt(r.PathValue("version"), 10, 64)
	if err != nil {
		w.WriteHeader(400)
		return
	}
	var payload []byte
	err = s.db.QueryRow(`SELECT payload FROM plugins WHERE version=?`, version).Scan(&payload)
	if err == sql.ErrNoRows {
		w.WriteHeader(404)
		return
	}
	if err != nil {
		w.WriteHeader(500)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Write(payload)
}
