// SPDX-License-Identifier: Apache-2.0
/**
 * Map a failed-login error to a user-facing message.
 *
 * Two deliberately different cases:
 *   - **Authentication failure** (4xx — wrong password, unknown user, disabled
 *     account): a single generic message. We never reveal *why* it failed, so an
 *     attacker can't enumerate which usernames exist or are locked.
 *   - **System error** (no HTTP response — backend/network down/timeout — or a
 *     5xx, e.g. LDAP/DB unreachable): a distinct "service unavailable" message so
 *     an outage isn't mistaken for bad credentials (and nobody resets a password
 *     that was never the problem). The backend's error body is intentionally NOT
 *     surfaced, so internal detail (e.g. "LDAP server unreachable: …") never
 *     leaks to the sign-in page.
 */
export interface HttpLikeError {
  response?: { status?: number } | null
}

export const INVALID_CREDENTIALS_MESSAGE = 'Invalid username or password.'
export const SERVICE_UNAVAILABLE_MESSAGE =
  'The sign-in service is temporarily unavailable. Please try again in a moment.'

/** @returns true when the error is a system/transport failure, not an auth rejection. */
export function isSystemError(err: unknown): boolean {
  const status = (err as HttpLikeError)?.response?.status
  // No response at all → network error, timeout, or backend unreachable.
  if (status == null) return true
  // 5xx → server-side failure (incl. LDAP/DB unreachable mapped to 502/503/500).
  return status >= 500
}

/** @returns the message to show for a failed login. */
export function loginErrorMessage(err: unknown): string {
  return isSystemError(err) ? SERVICE_UNAVAILABLE_MESSAGE : INVALID_CREDENTIALS_MESSAGE
}
