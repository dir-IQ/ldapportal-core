import { defineConfig, devices } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const isCI = !!process.env.CI
// Absolutise the wrapper path so it doesn't depend on Playwright's cwd.
// Playwright today launches webServer commands from the config directory,
// but that's not contractual — pinning the absolute path is belt-and-braces.
const here = path.dirname(fileURLToPath(import.meta.url))
const e2eServerScript = path.join(here, 'scripts', 'run-e2e-server.mjs')

export default defineConfig({
  testDir: './tests/e2e/spec',
  globalSetup: './tests/e2e/global-setup.ts',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,            // Storage state files mutate; serial is safe
  forbidOnly: isCI,                 // .only() not allowed in CI
  retries: isCI ? 1 : 0,            // Single retry on CI for transient flakes
  workers: 1,                       // Match fullyParallel: false
  reporter: [
    ['html', { open: 'never' }],
    ['github'],
    ['list'],
  ],
  use: {
    baseURL: 'http://localhost:5173',
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
  webServer: [
    {
      // Spring Boot via test-run main. Long timeout for first-time image pulls.
      //
      // Why a Node wrapper instead of running mvnw directly:
      //   On Windows, Playwright invokes `cmd.exe /d /s /c "<command>"`.
      //   cmd.exe's path resolution for `.cmd` files depends on cwd being
      //   the active drive's current directory — but the `cwd` option on
      //   Playwright's webServer is passed as a separate spawn option,
      //   NOT applied via `cd /d` first. So both `mvnw.cmd` and
      //   `./mvnw.cmd` fail in different ways:
      //     - Bare `mvnw.cmd` → "not recognized" (cwd not searched)
      //     - `./mvnw.cmd`    → cmd.exe parses `.` as a separate token
      //
      //   The Node wrapper at scripts/run-e2e-server.mjs takes the cwd
      //   problem out of the picture: it computes the absolute path,
      //   spawns mvnw / mvnw.cmd from that directory, forwards signals,
      //   and prints the e2e test credentials on startup. Same script is
      //   exposed as `npm run e2e:server` for manual long-running use.
      //
      //   On Linux/macOS this wrapper does the same thing — minor
      //   redundancy but identical command shape across platforms.
      command: `node "${e2eServerScript}"`,
      url: 'http://localhost:8080/actuator/health',
      timeout: 180_000,
      reuseExistingServer: !isCI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: 'npm run dev',
      url: 'http://localhost:5173',
      timeout: 60_000,
      reuseExistingServer: !isCI,
    },
  ],
})
