// SPDX-License-Identifier: Apache-2.0
/**
 * Public base path the SPA is served under.
 *
 * Vite bakes `base` into `import.meta.env.BASE_URL` at build time from the
 * VITE_BASE_PATH env var (see vite.config.js). It is "/" for a root
 * deployment and "/idm/" when the app sits behind a path-prefixed reverse
 * proxy such as a WebSEAL junction. The router already consumes BASE_URL;
 * this module gives the rest of the app one place to build root-relative
 * URLs that must include the prefix — the API base, and the few hard
 * navigations that bypass the router.
 */

/** Normalise any operator spelling to exactly one leading and one trailing slash. */
export function normalizeBasePath(raw: string | undefined | null): string {
  const trimmed = (raw ?? '').trim().replace(/\/{2,}/g, '/')
  const core = trimmed.replace(/^\/+|\/+$/g, '')
  return core ? `/${core}/` : '/'
}

/** The base path this build was made for, e.g. "/" or "/idm/". */
export const BASE_PATH: string = normalizeBasePath(import.meta.env.BASE_URL)

/**
 * Join a root-relative path onto the base path without doubling slashes:
 * withBase('api/v1') → "/api/v1" or "/idm/api/v1"; withBase('/login') likewise.
 */
export function withBase(path: string, base: string = BASE_PATH): string {
  return base + path.replace(/^\/+/, '')
}
