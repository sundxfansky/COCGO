import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Match the production reverse proxy so the frontend needs no API URL setting.
export default defineConfig({
  // Share one bundle between the server and Android's nested asset origin.
  base: './',
  build: { target: 'es2020' },
  plugins: [react()],
  server: { proxy: { '/api': 'http://127.0.0.1:8080', '/healthz': 'http://127.0.0.1:8080' } },
});
