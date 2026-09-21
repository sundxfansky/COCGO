package main

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"testing"
)

func TestSignedPluginPublicationAndVersionBinding(t *testing.T) {
	instance := testServer(t)
	token := initializeTestServer(t, instance)
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	public, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("PLUGIN_PUBLIC_KEY", base64.StdEncoding.EncodeToString(public))
	payload := []byte("a signed encrypted test payload")
	name := "encrypted_123.jar"
	hash := sha256.Sum256(append([]byte(name+"\x00"), payload...))
	signature, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, hash[:])
	if err != nil {
		t.Fatal(err)
	}
	request := map[string]any{"fileName": name, "payload": payload, "signature": signature}
	if response := callAPI(t, instance, "POST", "/api/v1/plugins", "", request); response.Code != 403 {
		t.Fatal("anonymous publication accepted")
	}
	if response := callAPI(t, instance, "POST", "/api/v1/plugins", token, request); response.Code != 201 {
		t.Fatal(response.Body.String())
	}
	if response := callAPI(t, instance, "GET", "/api/v1/plugins/123", token, nil); response.Code != 200 || response.Body.String() != string(payload) {
		t.Fatal("download differs from signed artifact")
	}
	request["fileName"] = "encrypted_124.jar"
	if response := callAPI(t, instance, "POST", "/api/v1/plugins", token, request); response.Code != 400 {
		t.Fatal("renamed signed version accepted")
	}
	request["fileName"] = name
	if response := callAPI(t, instance, "POST", "/api/v1/plugins", token, request); response.Code != 409 {
		t.Fatal("published version overwritten")
	}
	if response := callAPI(t, instance, "GET", "/api/v1/plugins/latest", token, nil); response.Code != 200 {
		t.Fatal(response.Body.String())
	}
}
