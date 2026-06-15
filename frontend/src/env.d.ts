// SPDX-License-Identifier: Apache-2.0
/// <reference types="vite/client" />

/**
 * Build-time constants injected via `vite.config.js`'s `define` block.
 * Treated as literal string replacements at bundle time, so reading
 * them is free at runtime — no module imports, no API roundtrip.
 */

/** Git short-SHA of the commit that produced this bundle. "dev" when
 *  the build couldn't read .git (release tarball, etc.). Used by the
 *  deployment-skew banner to compare against the backend's reported SHA. */
declare const __BUILD_SHA__: string
