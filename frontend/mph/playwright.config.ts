import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';

const frontendRoot = __dirname;
const repositoryRoot = resolve(frontendRoot, '..', '..');
const settingsDirectory = resolve(frontendRoot, 'e2e', '.runtime', 'settings');
const applicationJar = resolve(repositoryRoot, 'target', 'mph.jar');
const port = 18080;

export default defineConfig({
  testDir: './e2e/specs',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: {
    timeout: 15_000,
  },
  reporter: [
    ['line'],
    ['html', { open: 'never' }],
  ],
  outputDir: 'test-results/playwright',
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: `java "-Dmph.settings.directory=${settingsDirectory}" -jar "${applicationJar}" --server.port=${port}`,
    cwd: frontendRoot,
    url: `http://127.0.0.1:${port}/api/system/info`,
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
