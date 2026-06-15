import { test as base, Page } from '@playwright/test'
import { join } from 'node:path'

// `__dirname` is undefined in ESM (package.json has `"type": "module"`).
// `import.meta.dirname` is the ESM-native equivalent (Node >= 20.11).
const authDir = join(import.meta.dirname, '.auth')

/**
 * Role-based fixtures: each test declares which role's session to use.
 *
 * ```
 * test('superadmin sees the directory list', async ({ superadmin }) => {
 *   await superadmin.goto('/superadmin/directories')
 *   ...
 * })
 * ```
 *
 * Each fixture spawns a fresh browser context loaded with the role's cookies
 * (set up by `global-setup.ts`) and a Page in that context. No login round-
 * trip per test — auth is "free" because the cookies are already valid.
 *
 * Roles: only `superadmin` and `admin`. The codebase has no READ_ONLY role
 * (`AccountRole` has only SUPERADMIN and ADMIN); read-only behaviour is
 * driven by feature-permission overrides on an ADMIN account. If a future
 * test needs that variant, build it on top of the `admin` fixture by
 * stripping permissions, rather than introducing a third storage state.
 */
export const test = base.extend<{
  superadmin: Page
  admin: Page
}>({
  superadmin: async ({ browser }, use) => {
    const ctx = await browser.newContext({
      storageState: join(authDir, 'superadmin.json'),
    })
    const page = await ctx.newPage()
    try {
      await use(page)
    } finally {
      await ctx.close()
    }
  },
  admin: async ({ browser }, use) => {
    const ctx = await browser.newContext({
      storageState: join(authDir, 'admin.json'),
    })
    const page = await ctx.newPage()
    try {
      await use(page)
    } finally {
      await ctx.close()
    }
  },
})

export { expect } from '@playwright/test'
