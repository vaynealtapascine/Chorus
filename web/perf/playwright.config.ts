// Open-time measurement (R18, CLIENTS.md §4.3): the built app served by `vite preview`, a
// replica seeded straight into IndexedDB, and the `chorus:*` performance marks read back.
//   cd web && npm run build && npx playwright test -c perf/playwright.config.ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  workers: 1,
  timeout: 600_000,
  reporter: [['list']],
  use: { baseURL: 'http://127.0.0.1:4179', browserName: 'chromium', serviceWorkers: 'block' },
  webServer: { command: 'npx vite preview --port 4179 --strictPort', port: 4179, reuseExistingServer: true, cwd: '..' },
  outputDir: './results',
});
