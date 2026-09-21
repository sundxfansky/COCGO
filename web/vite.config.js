import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Match the production reverse proxy so the frontend needs no API URL setting.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://127.0.0.1:8080', '/healthz': 'http://127.0.0.1:8080' } },
});
