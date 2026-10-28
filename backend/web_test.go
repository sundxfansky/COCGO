package main

import (
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWebDirServesFrontendWithSPAFallback(test *testing.T) {
	parent := test.TempDir()
	root := filepath.Join(parent, "web")
	if err := os.WriteFile(filepath.Join(parent, "secret.txt"), []byte("secret"), 0640); err != nil {
		test.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "assets"), 0750); err != nil {
		test.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "index.html"), []byte("<html>app</html>"), 0640); err != nil {
		test.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "assets", "main.js"), []byte("console.log(1)"), 0640); err != nil {
		test.Fatal(err)
	}
	test.Setenv("WEB_DIR", root)
	handler := testServer(test).routes()
	get := func(path string) *httptest.ResponseRecorder {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("GET", path, nil))
		return response
	}

	for _, path := range []string{"/", "/config/center"} {
		if response := get(path); response.Code != 200 || !strings.Contains(response.Body.String(), "app") {
			test.Fatalf("%s: %d %s", path, response.Code, response.Body.String())
		}
	}
	if response := get("/../secret.txt"); response.Body.String() == "secret" {
		test.Fatalf("path traversal escaped WEB_DIR: %s", response.Body.String())
	}
	if response := get("/assets/main.js"); response.Code != 200 || !strings.Contains(response.Header().Get("Cache-Control"), "immutable") {
		test.Fatalf("asset: %d %v", response.Code, response.Header())
	}
	if response := get("/api/v1/missing"); response.Code != 404 || strings.Contains(response.Body.String(), "app") {
		test.Fatalf("unknown api fell back to index: %d %s", response.Code, response.Body.String())
	}
	if response := get("/healthz"); response.Code != 200 || !strings.Contains(response.Body.String(), "ok") {
		test.Fatalf("health: %d %s", response.Code, response.Body.String())
	}
}

func TestWithoutWebDirRootIsNotServed(test *testing.T) {
	test.Setenv("WEB_DIR", "")
	response := httptest.NewRecorder()
	testServer(test).routes().ServeHTTP(response, httptest.NewRequest("GET", "/", nil))
	if response.Code != 404 {
		test.Fatalf("root served without WEB_DIR: %d", response.Code)
	}
}
