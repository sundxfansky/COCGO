package main

import (
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// webHandler serves the built frontend so a single process can replace the nginx container,
// e.g. for a local Windows deployment without Docker. Only page routes fall back to index.html.
func webHandler(root string) http.Handler {
	files := http.FileServer(http.Dir(root))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			jsonOut(w, http.StatusNotFound, map[string]string{"error": "not found"})
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		cleanPath := path.Clean("/" + r.URL.Path)
		name := filepath.Join(root, filepath.FromSlash(strings.TrimPrefix(cleanPath, "/")))
		if info, err := os.Stat(name); err != nil || info.IsDir() {
			// Missing bundles must fail visibly, never return HTML with an immutable cache lifetime.
			if strings.HasPrefix(cleanPath, "/assets/") || cleanPath == "/assets" || path.Ext(cleanPath) != "" {
				http.NotFound(w, r)
				return
			}
			if index, err := os.Stat(filepath.Join(root, "index.html")); err != nil || index.IsDir() {
				http.NotFound(w, r)
				return
			}
			r = r.Clone(r.Context())
			r.URL.Path = "/"
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-cache")
		if strings.HasPrefix(r.URL.Path, "/assets/") {
			// Vite emits content-hashed asset names, so they can be cached indefinitely.
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		}
		files.ServeHTTP(w, r)
	})
}
