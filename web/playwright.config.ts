// Browser end-to-end suite (web/e2e/, R12): run by scripts/e2e-web.sh against a server it starts
// on a temporary data directory, or against a real server (see e2e/README.md).
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  // one server, accounts created as the suite goes: in order, one worker
  workers: 1,
  fullyParallel: false,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.CHORUS_E2E_BASE ?? 'http://127.0.0.1:5399',
    browserName: 'chromium',
    viewport: { width: 420, height: 900 },
    trace: 'retain-on-failure',
  },
  outputDir: './e2e/results',
});
