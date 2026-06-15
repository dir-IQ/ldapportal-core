import { chromium, FullConfig, request } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'

/**
 * Global setup runs ONCE before any test. Logs in via the real UI for each
 * role we want pre-authenticated cookies for, and saves the resulting
 * storageState to a per-role file under `.auth/`. Tests then declare which
 * role they want via fixtures (`fixtures.ts`) and skip the per-test login
 * cost (which would be ~1-2s x N tests).
 *
 * One test (login.spec.ts in Task 5) deliberately does NOT use storageState
 * — it exercises the real login form to keep that codepath covered.
 *
 * Role gap note: this codebase's `AccountRole` enum has only SUPERADMIN and
 * ADMIN. There is no READ_ONLY role — read-only access is enforced through
 * feature-permission overrides on an ADMIN account, not as a distinct role.
 * So this setup creates two storage states (superadmin, admin); a `readonly`
 * fixture is intentionally absent.
 */
export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0].use.baseURL ?? 'http://localhost:5173'
  // `__dirname` is undefined in ESM (package.json has `"type": "module"`).
  // `import.meta.dirname` is the ESM-native equivalent (Node >= 20.11).
  const authDir = join(import.meta.dirname, '.auth')
  await mkdir(authDir, { recursive: true })

  // Test-only credentials. SUPERADMIN is bootstrapped on first boot via
  // `application-e2e.yml`. ADMIN doesn't exist out of the box — we
  // provision it below using the SUPERADMIN's session against the
  // admin-management API.
  const SUPERADMIN = { username: 'superadmin', password: 'test-superadmin-pw' }
  const E2E_ADMIN = { username: 'e2e-admin', password: 'e2e-admin-pw' }

  const browser = await chromium.launch()
  try {
    // 1) Log in superadmin via the real UI. On a fresh CI database the
    //    bootstrap superadmin is the only account and the app is in setup
    //    mode — login completes (cookie set), but the SPA router redirects
    //    `/` to `/setup` until the wizard is marked complete. So we accept
    //    any post-login URL that isn't /login.
    const superCtx = await browser.newContext()
    const superPage = await superCtx.newPage()
    await superPage.goto(`${baseURL}/login`)
    await superPage.getByLabel(/username/i).fill(SUPERADMIN.username)
    await superPage.getByLabel(/password/i).fill(SUPERADMIN.password)
    await superPage.getByRole('button', { name: /^sign in$/i }).click()
    await superPage.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10_000 })

    // 2) Mark setup complete so subsequent UI navigation isn't trapped at
    //    /setup. This is a no-op on a re-run where setup is already done
    //    (the endpoint is idempotent — toggles the flag to true unconditionally).
    //    On CI this is the difference between every UI test landing on
    //    /dashboard vs /setup. Local dev usually has a pre-populated DB
    //    where setup is already complete, hence why this gap took until
    //    CI to surface.
    // Absolute URL because `browser.newContext()` doesn't inherit baseURL
    // from playwright.config.ts — that only happens via the test runner's
    // fixture chain. page.request with a relative path here throws
    // "Invalid URL".
    const completeResp = await superPage.request.post(`${baseURL}/api/v1/settings/complete-setup`)
    if (!completeResp.ok()) {
      throw new Error(
        `Failed to mark setup complete: ${completeResp.status()} ${await completeResp.text()}`,
      )
    }

    await superCtx.storageState({ path: join(authDir, 'superadmin.json') })
    await superCtx.close()

    // 3) Provision the e2e-admin account via the admin-management API,
    //    using the superadmin storageState for auth. 409 = already exists,
    //    which is fine for idempotent re-runs.
    const apiRequest = await request.newContext({
      baseURL,
      storageState: join(authDir, 'superadmin.json'),
    })
    const created = await apiRequest.post('/api/v1/superadmin/admins', {
      data: {
        username: E2E_ADMIN.username,
        password: E2E_ADMIN.password,
        role: 'ADMIN',
        authType: 'LOCAL',
        active: true,
      },
    })
    if (!created.ok() && created.status() !== 409) {
      throw new Error(
        `Failed to create e2e-admin: ${created.status()} ${await created.text()}`,
      )
    }
    await apiRequest.dispose()

    // 4) Log in the e2e-admin via UI. With setup complete (step 2), this
    //    lands on /no-access (ADMIN with no profile assignments) rather
    //    than /setup or /login. The cookie is what matters; the destination
    //    page is irrelevant for our purposes.
    const adminCtx = await browser.newContext()
    const adminPage = await adminCtx.newPage()
    await adminPage.goto(`${baseURL}/login`)
    await adminPage.getByLabel(/username/i).fill(E2E_ADMIN.username)
    await adminPage.getByLabel(/password/i).fill(E2E_ADMIN.password)
    await adminPage.getByRole('button', { name: /^sign in$/i }).click()
    await adminPage.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10_000 })

    // 5) Verify the admin actually authenticated. waitForURL succeeds even
    //    if login lands on /no-access (an authenticated state) OR somehow
    //    on a different non-/login route without a real cookie set. Probe
    //    /api/v1/auth/me to confirm the JWT cookie is valid before we
    //    persist storageState. If this returns 401, the storageState we'd
    //    save is empty/invalid and roles.spec.ts will fail later with a
    //    confusing 401-not-403 message.
    const meResp = await adminPage.request.get(`${baseURL}/api/v1/auth/me`)
    if (!meResp.ok()) {
      throw new Error(
        `Admin login probe failed: GET /api/v1/auth/me returned ${meResp.status()} ` +
        `${await meResp.text()}. The e2e-admin's UI login appeared to succeed ` +
        `(URL navigated away from /login) but the JWT cookie isn't valid. ` +
        `Likely causes: bad password hash, account inactive, or cookie scoping issue.`,
      )
    }

    await adminCtx.storageState({ path: join(authDir, 'admin.json') })
    await adminCtx.close()
  } finally {
    await browser.close()
  }
}
