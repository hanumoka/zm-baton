import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Dev server on a fixed free port (8080 is taken on the owner's PC); /api goes to the
// compat server, so the page and the API share one origin in dev as in production.
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 15173,
    strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:18081' },
  },
  build: { outDir: 'dist', emptyOutDir: true },
  test: { environment: 'node' },
});
