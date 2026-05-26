import { test, expect } from '@playwright/test'

// Note: this file uses Playwright's BASE `test`, not the role-fixture extended one.
// The whole point is exercising the real login form — no pre-auth storage state.
//
// Selector notes:
// - The login form has a single "Username" label (no email alternative) and a
//   submit button rendered as plain "Sign in" text. Anchored regexes match both.
// - The logout affordance (per `frontend/src/components/AppLayout.vue`) is a
//   plain button labeled "Logout" in the sidebar — there is no user-menu
//   dropdown or `menuitem` role to traverse.

const SUPERADMIN_USERNAME = 'superadmin'
const SUPERADMIN_PASSWORD = 'test-superadmin-pw'

test.describe('Login flow', () => {
  test('successful login navigates away from /login', async ({ page }) => {
    await page.goto('/login')
    await expect(page).toHaveURL(/\/login/)
    await page.getByLabel(/username/i).fill(SUPERADMIN_USERNAME)
    await page.getByLabel(/password/i).fill(SUPERADMIN_PASSWORD)
    await page.getByRole('button', { name: /^sign in$/i }).click()
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10_000 })
    expect(page.url()).not.toContain('/login')
  })

  test('bad password shows an error and stays on /login', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel(/username/i).fill(SUPERADMIN_USERNAME)
    await page.getByLabel(/password/i).fill('definitely-wrong-password')
    await page.getByRole('button', { name: /^sign in$/i }).click()
    // No hard-coded sleep: the toBeVisible assertion below retries up to 5s,
    // which is plenty of time for the auth response to render the error.
    // Backend throws BadCredentialsException with "Bad credentials" detail
    // (Spring's standard wording). Frontend falls back to "Invalid username
    // or password" if no detail/message on the response. Regex covers both
    // plus other plausible synonyms for future-proofing.
    await expect(
      page.getByText(/invalid|incorrect|fail|bad credential|denied|unauthor/i).first(),
    ).toBeVisible({ timeout: 5_000 })
    await expect(page).toHaveURL(/\/login/)
  })

  test('logout returns to /login', async ({ page }) => {
    // Log in first.
    await page.goto('/login')
    await page.getByLabel(/username/i).fill(SUPERADMIN_USERNAME)
    await page.getByLabel(/password/i).fill(SUPERADMIN_PASSWORD)
    await page.getByRole('button', { name: /^sign in$/i }).click()
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10_000 })

    // The logout affordance is a single plain button labeled "Logout" in the
    // sidebar (see AppLayout.vue) — not a dropdown menuitem. Anchor the regex
    // to avoid matching unrelated buttons whose names contain "logout".
    await page.getByRole('button', { name: /^logout$/i }).click()

    await page.waitForURL(/\/login/, { timeout: 5_000 })
    expect(page.url()).toContain('/login')
  })

  test('@smoke API token auth works as an alternative to cookies', async ({ playwright, page }) => {
    // Log in via UI to mint a fresh API token, then exercise that token
    // against /api/v1/auth/me from a NO-COOKIE context — proves the
    // ApiTokenAuthenticationFilter accepts `Authorization: Bearer ldap_pat_*`
    // and resolves the same principal as the cookie session.
    await page.goto('/login')
    await page.getByLabel(/username/i).fill(SUPERADMIN_USERNAME)
    await page.getByLabel(/password/i).fill(SUPERADMIN_PASSWORD)
    await page.getByRole('button', { name: /^sign in$/i }).click()
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10_000 })

    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString()
    const createResp = await page.request.post('/api/v1/superadmin/api-tokens', {
      data: { name: `e2e-login-token-${Date.now()}`, description: 'login.spec', expiresAt },
    })
    expect(createResp.status()).toBe(201)
    const { token: tokenMeta, plaintext } = await createResp.json()
    expect(plaintext).toMatch(/^ldap_pat_/)

    // Fresh request context — no cookies — auth via header only.
    const apiCtx = await playwright.request.newContext({
      baseURL: 'http://localhost:8080',
      extraHTTPHeaders: { Authorization: `Bearer ${plaintext}` },
    })
    try {
      const meResp = await apiCtx.get('/api/v1/auth/me')
      expect(meResp.ok()).toBe(true)
      const me = await meResp.json()
      expect(me.username).toBe(SUPERADMIN_USERNAME)
    } finally {
      await apiCtx.dispose()
    }

    // Cleanup — revoke the token. Must use the cookie session because
    // ApiTokenController rejects token-authenticated callers from
    // mutating endpoints (defense-in-depth).
    await page.request.delete(`/api/v1/superadmin/api-tokens/${tokenMeta.id}`)
  })
})
