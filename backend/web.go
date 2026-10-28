package main

import (
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// webHandler serves the built frontend so a single process can replace the nginx container,
// e.g. for a local Windows deployment without Docker. Unknown routes fall back to index.html.
func webHandler(root string) http.Handler {
	files := http.FileServer(http.Dir(root))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			jsonOut(w, http.StatusNotFound, map[string]string{"error": "not found"})
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		name := filepath.Join(root, filepath.FromSlash(path.Clean("/"+r.URL.Path)))
		if info, err := os.Stat(name); err != nil || info.IsDir() {
			r.URL.Path = "/"
		}
		if strings.HasPrefix(r.URL.Path, "/assets/") {
			// Vite emits content-hashed asset names, so they can be cached indefinitely.
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		}
		files.ServeHTTP(w, r)
	})
}
