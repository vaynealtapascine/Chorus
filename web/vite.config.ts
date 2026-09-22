/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// Dev: Vite on 5252, API/sync on 5251 (docs/OPS.md §8).
const api = `http://127.0.0.1:${process.env.CHORUS_PORT ?? 5251}`;

export default defineConfig({
  plugins: [svelte()],
  server: {
    port: 5252,
    strictPort: true,
    proxy: {
      '/api': { target: api, ws: true, changeOrigin: false },
    },
  },
  build: { target: 'es2022' },
  test: { include: ['src/**/*.test.ts'], environment: 'node' },
});
