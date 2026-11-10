// SPDX-License-Identifier: Apache-2.0
/**
 * Links out of the membership inventory. They open in a new tab, so following
 * one never replaces the inventory (and its tab, filter, page and selection).
 */

/** An app URL for `path` and `query`, under the app's base, for an `<a target="_blank">`. */
export function appHref(path: string, query: Record<string, string> = {}): string {
  const base = (import.meta.env.BASE_URL ?? '/').replace(/\/$/, '')
  const qs = new URLSearchParams(query).toString()
  return `${base}${path.startsWith('/') ? path : `/${path}`}${qs ? `?${qs}` : ''}`
}

/** The Directory Browser at an entry. */
export function browserHref(directoryId: string, dn: string): string {
  return appHref('/superadmin/directory-browser', { dir: directoryId, dn })
}

/** Attributes whose values are never shown (password material), lower-cased. Mirrors the server's list. */
export const SECRET_ATTRIBUTES = new Set([
  'userpassword', 'authpassword', 'unicodepwd', 'pwdhistory', 'sambantpassword', 'sambalmpassword',
  'clearpassword', 'supplementalcredentials', 'dbcspwd', 'lmpwdhistory', 'ntpwdhistory',
])

/** Whether `name` (with or without options, e.g. `userPassword;binary`) is password material. */
export function isSecret(name: string): boolean {
  return SECRET_ATTRIBUTES.has(name.split(';')[0].toLowerCase())
}
