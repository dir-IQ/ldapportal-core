import { Page } from '@playwright/test'

/**
 * Helpers for working with `DirectoryConnection` rows in E2E specs.
 *
 * ## Why no `createTestDirectory` / `deleteTestDirectory` here?
 *
 * The OpenLDAP Testcontainer used by the backend integration / E2E stack
 * exposes its LDAP port on a *dynamically mapped* host port (Testcontainers
 * picks a free one — it is **not** 1389). Any helper that POSTs a
 * `DirectoryConnectionRequest` from the browser context would have to learn
 * that port out-of-band, plus satisfy the full ~30-field DTO with its
 * `@Min`/`@Max` validation. That is not worth carrying in a thin helper.
 *
 * The supported pattern is:
 *
 *   1. The backend test profile's `ApplicationRunner` (see SP4 Task 7)
 *      bridges the running `OpenLdapContainer` into a single
 *      `DirectoryConnection` row at startup, with a stable display name.
 *   2. Specs call {@link seededDirectoryId} to look that row up by name
 *      and then drive the UI / API against it.
 *
 * If a future spec genuinely needs ad-hoc directory creation, add a
 * fuller helper at that point — don't pre-build a half-broken one here.
 */

export interface SeededDirectory {
  id: string
  displayName: string
}

/**
 * Returns the id of the auto-seeded test `DirectoryConnection`, looked up
 * by its display name.
 *
 * Throws an actionable error if no row matches — that almost always means
 * the backend ApplicationRunner that bridges the OpenLDAP container into a
 * `DirectoryConnection` row is missing or has not run yet.
 */
export async function seededDirectoryId(
  page: Page,
  displayName = 'E2E LDAP (auto)',
): Promise<string> {
  const list = await page.request.get('/api/v1/superadmin/directories')
  if (!list.ok()) {
    throw new Error(`Listing directories failed: ${list.status()} ${list.statusText()}`)
  }
  const all = (await list.json()) as SeededDirectory[]
  const found = all.find((d) => d.displayName === displayName)
  if (!found) {
    throw new Error(
      `No seeded directory with displayName="${displayName}" found. ` +
        `Add an ApplicationRunner to TestcontainersConfiguration that creates a ` +
        `DirectoryConnection with that displayName pointing at OpenLdapContainer.getInstance().`,
    )
  }
  return found.id
}
