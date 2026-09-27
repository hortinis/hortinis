import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: 'integrated-sync.spec.ts',
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4200',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command:
      'pnpm exec ng build --configuration=production && HORTINIS_E2E_API_ORIGIN=http://127.0.0.1:8080 node e2e/production-server.mjs',
    url: 'http://127.0.0.1:4200',
    reuseExistingServer: false,
  },
});
