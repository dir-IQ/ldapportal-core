// SPDX-License-Identifier: Apache-2.0
/**
 * The "signed out" marker: a session cookie set when the user logs out, read
 * only by this SPA. While it's set, the app doesn't sign the user straight
 * back in from WebSEAL's `iv-user` header; the login page says they've signed
 * out and offers "Sign in again" instead.
 *
 * Why: with certificate (or other credential-free) WebSEAL sign-in, the
 * browser keeps presenting the client certificate after `/pkmslogout`, so
 * WebSEAL silently creates a new session on the next request and the boot-time
 * probe would land the user back in the app as if logout had done nothing.
 *
 * A session cookie (no Max-Age) rather than sessionStorage, so the marker holds
 * across tabs and ends when the browser closes, which is also what ends a
 * certificate session. Cleared by any successful sign-in.
 */

const COOKIE = 'ldapportal-signed-out'

/** Whether the user logged out in this browser session and hasn't signed in since. */
export function isSignedOut(): boolean {
  if (typeof document === 'undefined') return false
  return document.cookie.split('; ').some((c) => c === `${COOKIE}=1`)
}

/** Remember an explicit logout until the browser closes or the user signs in again. */
export function markSignedOut(): void {
  if (typeof document === 'undefined') return
  const secure = location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `${COOKIE}=1; Path=/; SameSite=Strict${secure}`
}

/** Forget the logout: the user signed in, or asked to. */
export function clearSignedOut(): void {
  if (typeof document === 'undefined') return
  document.cookie = `${COOKIE}=; Path=/; Max-Age=0; SameSite=Strict`
}
