import { Page } from '@playwright/test'

/**
 * Thin LDAP-user helpers for E2E specs.
 *
 * Intentionally minimal — SP5 will add specific helpers (groups, campaigns,
 * HR workflows, alerting, etc.) as the matching specs land. The shape that
 * is easy to share now is: locate the table row for a `cn`, and wait for it.
 */

export interface SeededUser {
  cn: string
  dn: string
  email: string
}

/**
 * Locator for the table row that represents the user with the given `cn`.
 *
 * The cn is matched case-insensitively as a regex; special characters
 * are escaped automatically so callers can pass arbitrary cn strings.
 */
export function userRow(page: Page, cn: string) {
  return page.getByRole('row', { name: new RegExp(escapeRegex(cn), 'i') })
}

/**
 * Wait (up to 5s) for a user row to appear in the visible viewport.
 */
export async function waitForUserVisible(page: Page, cn: string): Promise<void> {
  await userRow(page, cn).waitFor({ state: 'visible', timeout: 5000 })
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
