// SPDX-License-Identifier: Apache-2.0
/**
 * The "FOUC hint" cookie — a tiny, non-httpOnly cookie carrying just
 * "{@code <theme>.<density>}" so the inline script in index.html can set
 * `data-theme` / `data-density` on `<html>` before the SPA boots.
 *
 * The authoritative preference lives in the database (the user-preferences
 * document); this cookie is a disposable render hint, never a source of truth.
 * The server writes it at login and clears it at logout; the app keeps it in
 * sync whenever the user changes theme/density so the next reload paints
 * correctly without a flash. We must NOT keep preferences in localStorage, but
 * a transient render hint in a cookie is fair game.
 *
 * Keep the parsing logic here in sync with the inline script in index.html.
 */

const COOKIE = 'prefs-hint'

export interface PrefsHint {
  theme?: string
  density?: string
}

/** Read the current theme/density hint from the cookie, if present. */
export function readPrefsHint(): PrefsHint {
  if (typeof document === 'undefined') return {}
  const match = document.cookie
    .split('; ')
    .find((c) => c.startsWith(COOKIE + '='))
  if (!match) return {}
  const raw = decodeURIComponent(match.slice(COOKIE.length + 1))
  const [theme, density] = raw.split('.')
  return { theme: theme || undefined, density: density || undefined }
}

/**
 * Update one or both fields of the hint cookie, preserving the field not
 * passed. Written with a long max-age and root path so the inline script
 * (which runs before any JS module) can read it on the next load.
 */
export function setPrefsHint(next: PrefsHint): void {
  if (typeof document === 'undefined') return
  const current = readPrefsHint()
  const theme = next.theme ?? current.theme ?? 'light'
  const density = next.density ?? current.density ?? 'comfortable'
  const value = encodeURIComponent(`${theme}.${density}`)
  const oneYear = 60 * 60 * 24 * 365
  const secure = location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `${COOKIE}=${value}; Path=/; Max-Age=${oneYear}; SameSite=Strict${secure}`
}
