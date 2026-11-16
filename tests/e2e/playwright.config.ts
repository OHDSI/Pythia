import { defineConfig } from '@playwright/test'

// Live end-to-end suite: needs the compose stack up (ATLAS on :443 with the
// Pythia plugin in frontend-assets/, WebAPI on :8080) and Bedrock credentials
// in the agent's environment. See tests/e2e/README.md.
export default defineConfig({
  testDir: '.',
  testMatch: /.*\.e2e\.ts$/,
  // Scenarios find "their" cohort as the newest one saved since they started,
  // so they must not overlap.
  workers: 1,
  fullyParallel: false,
  // One agent build is several minutes of model time.
  timeout: 20 * 60_000,
  expect: { timeout: 15_000 },
  retries: 0,
  outputDir: './test-results',
  reporter: [
    ['list'],
    ['junit', { outputFile: './test-results/junit.xml' }],
    ['html', { outputFolder: './playwright-report', open: 'never' }],
  ],
  use: {
    baseURL: process.env.PYTHIA_E2E_ATLAS_URL || 'https://localhost',
    ignoreHTTPSErrors: true, // Caddy's `tls internal` dev certificate
    viewport: { width: 1600, height: 1000 },
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
