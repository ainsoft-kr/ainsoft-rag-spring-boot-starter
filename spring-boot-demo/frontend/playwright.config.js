import { defineConfig } from '@playwright/test';

const useEmbeddedWebServer = !process.env.PLAYWRIGHT_EXTERNAL_SERVER;

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: {
    timeout: 10_000
  },
  fullyParallel: false,
  use: {
    baseURL: 'http://127.0.0.1:18080',
    trace: 'retain-on-failure'
  },
  webServer: useEmbeddedWebServer
    ? {
        command: 'npm run test:e2e:server',
        url: 'http://127.0.0.1:18080',
        reuseExistingServer: true,
        timeout: 180_000
      }
    : undefined
});
