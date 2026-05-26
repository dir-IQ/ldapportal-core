import { test, expect } from '@playwright/test'

/**
 * Regression coverage for the auth/setup-wizard redirect bugs from
 * 2026-04-26: login() once skipped /auth/setup-status entirely (so the
 * wizard didn't fire on first login but did on subsequent reload),
 * and there's an ongoing concern that the two auth-bootstrap paths
 * (init() and login()) can drift in what they fetch.
 *
 * These tests intercept /auth/setup-status with Playwright's request
 * routing so we can pin the response shape independent of whatever the
 * backend's actual state happens to be (which is non-deterministic in a
 * shared test container — other smokes create directories that flip
 * the self-heal flag). Both the login flow AND the cold-page-load flow
 * must agree on the same redirect.
 *
 * Uses the BASE Playwright `test`, not the role-fixture extended one,
 * because the whole point is exercising the actual login path.
 */

const SUPERADMIN_USERNAME = 'superadmin'
const SUPERADMIN_PASSWORD = 'test-superadmin-pw'

test.describe('Setup wizard redirect @smoke', () => {
  test('superadmin with setupCompleted=false is redirected to /setup after login', async ({ page }) => {
    // Pin the setup-status response BEFORE any auth call. Both the post-
    // login refresh and any subsequent init() call go through the same
    // route — the route handler stays installed for the whole page.
    await page.route('**/api/v1/auth/setup-status', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ setupCompleted: false }) }),
    )

    await page.goto('/login')
    await page.getByLabel(/username/i).fill(SUPERADMIN_USERNAME)
    await page.getByLabel(/password/i).fill(SUPERADMIN_PASSWORD)
    await page.getByRole('button', { name: /^sign in$/i }).click()

    // The router guard should redirect to /setup as soon as the auth
    // store knows setupPending=true. Was the regression: this redirect
    // was being skipped on first-login because login() didn't call
    // setup-status at all.
    await page.waitForURL(/\/setup/, { timeout: 10_000 })
    expect(page.url()).toContain('/setup')
  })

  test('superadmin with setupCompleted=true lands on the dashboard after login', async ({ page }) => {
    await page.route('**/api/v1/auth/setup-status', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ setupCompleted: true }) }),
    )

    await page.goto('/login')
    await page.getByLabel(/username/i).fill(SUPERADMIN_USERNAME)
    await page.getByLabel(/password/i).fill(SUPERADMIN_PASSWORD)
    await page.getByRole('button', { name: /^sign in$/i }).click()

    // Wait until we're past /login. Then assert we are NOT on /setup
    // (the negative case — guard didn't spuriously redirect).
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10_000 })
    expect(page.url()).not.toContain('/setup')
    expect(page.url()).toMatch(/\/superadmin\/dashboard|\//)
  })

  test('cold reload (init path) honours the same setupCompleted=false', async ({ page }) => {
    // First log in so the JWT cookie is set, with setup completed so we
    // don't get caught by the wizard redirect mid-login.
    await page.route('**/api/v1/auth/setup-status', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ setupCompleted: true }) }),
    )
    await page.goto('/login')
    await page.getByLabel(/username/i).fill(SUPERADMIN_USERNAME)
    await page.getByLabel(/password/i).fill(SUPERADMIN_PASSWORD)
    await page.getByRole('button', { name: /^sign in$/i }).click()
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10_000 })

    // Now flip the mock to incomplete and reload. The router guard should
    // call init(), see setupPending=true, and redirect.
    await page.unroute('**/api/v1/auth/setup-status')
    await page.route('**/api/v1/auth/setup-status', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ setupCompleted: false }) }),
    )
    await page.reload()

    await page.waitForURL(/\/setup/, { timeout: 10_000 })
    expect(page.url()).toContain('/setup')
  })
})
