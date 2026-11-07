import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4200',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'pnpm exec ng build --configuration=production && node e2e/production-server.mjs',
    url: 'http://127.0.0.1:4200',
    reuseExistingServer: false,
  },
});
