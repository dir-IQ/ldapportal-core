// SPDX-License-Identifier: Apache-2.0
/**
 * Helpers for the provisioning-profile "theme colour" — an optional #RRGGBB hex
 * string an admin sets on a profile. The admin user/group list pages and the
 * new/edit user/group modals render it as a band behind the page header; these
 * helpers pick legible foreground classes for content sitting on that band and
 * otherwise fall back to the default styling.
 */

/** Parse a #RRGGBB (or RRGGBB) hex string to [r,g,b], or null if malformed. */
function parseHex(hex: string | null | undefined): [number, number, number] | null {
  if (!hex) return null
  const m = /^#?([0-9a-fA-F]{6})$/.exec(hex.trim())
  if (!m) return null
  const n = parseInt(m[1], 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

/**
 * Whether a colour is "light" by perceived (sRGB) luminance, so callers can put
 * dark text on it. The 0.6 threshold keeps mid-tone brand colours (blues,
 * greens, reds) on the dark side → white text. An empty/unparseable value is
 * treated as light (default dark text), the safe fallback.
 */
export function isLightColor(hex: string | null | undefined): boolean {
  const rgb = parseHex(hex)
  if (!rgb) return true
  const [r, g, b] = rgb
  const luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
  return luminance > 0.6
}

/** Foreground class for a page/modal title sitting on a theme band. */
export function onThemeTitleClass(hex: string | null | undefined): string {
  return isLightColor(hex) ? 'text-gray-900' : 'text-white'
}

/** Foreground class for muted/secondary copy sitting on a theme band. */
export function onThemeMutedClass(hex: string | null | undefined): string {
  return isLightColor(hex) ? 'text-gray-700' : 'text-white/80'
}
