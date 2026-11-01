// SPDX-License-Identifier: Apache-2.0
import type { CSSProperties } from 'vue'

/**
 * Helpers for the provisioning-profile "theme colour" — an optional #RRGGBB hex
 * string an admin sets on a profile. The admin user/group list pages and the
 * new/edit user/group modals render it as a profile tag (a soft-tinted chip),
 * and the bulk pages render it as a tint-filled "Active profile" field. These
 * helpers derive the shared tint for both themes, keep its text readable
 * whatever the colour, and pick legible foreground classes, falling back to
 * neutral styling when no colour is set.
 */

/** The page backgrounds a profile tint sits on, light and dark (`.bg-white` in each theme). */
const LIGHT_PAGE: Rgb = [255, 255, 255]
const DARK_PAGE: Rgb = [17, 24, 39] // #111827, [data-theme="dark"] .bg-white
const BLACK: Rgb = [0, 0, 0]
const WHITE: Rgb = [255, 255, 255]

/** WCAG AA for normal text: the name on a profile tag must reach this against its background. */
export const MIN_TEXT_CONTRAST = 4.5

/** One theme's tint: background, border and text, as #RRGGBB. */
export interface ThemeTint {
  background: string
  border: string
  text: string
}

/**
 * The soft tint for a profile colour on one theme's page: a light wash of the
 * colour (10% on white, 18% on the dark page) with a stronger border, and the
 * colour itself as text, darkened on light (lightened on dark) only as far as
 * needed to reach {@link MIN_TEXT_CONTRAST} against that wash. Mid and deep
 * colours keep their hue almost unchanged; a light colour, which used to wash
 * out (yellow came to about 2.3:1), gets a deeper shade of itself.
 *
 * Null for an unset or malformed colour.
 */
export function themeTint(hex: string | null | undefined, dark = false): ThemeTint | null {
  const rgb = parseHex(hex)
  if (!rgb) return null
  const page = dark ? DARK_PAGE : LIGHT_PAGE
  const background = mix(rgb, page, dark ? 0.18 : 0.10)
  const border = mix(rgb, page, dark ? 0.45 : 0.32)
  // Push the text toward black (light page) or white (dark page) in small
  // steps until it reads; the starting point is the old recipe's 20% shade.
  const toward = dark ? WHITE : BLACK
  let text = rgb
  for (let amount = dark ? 0 : 0.2; amount <= 1.0001; amount += 0.05) {
    text = mix(toward, rgb, amount)
    if (contrastRatio(text, background) >= MIN_TEXT_CONTRAST) break
  }
  return { background: toHex(background), border: toHex(border), text: toHex(text) }
}

/**
 * Inline styles for the shared profile tint (the profile pill, the bulk
 * Active-profile field): the light and dark tints as custom properties, which
 * the `.profile-tint` class in main.css applies for the current theme. Pair it
 * with that class. Null for an unset or malformed colour, so callers apply
 * their neutral styling instead.
 */
export function themeTintStyle(hex: string | null | undefined): CSSProperties | null {
  const light = themeTint(hex, false)
  const dark = themeTint(hex, true)
  if (!light || !dark) return null
  return {
    '--profile-tint-bg': light.background,
    '--profile-tint-border': light.border,
    '--profile-tint-text': light.text,
    '--profile-tint-bg-dark': dark.background,
    '--profile-tint-border-dark': dark.border,
    '--profile-tint-text-dark': dark.text,
  } as CSSProperties
}

/**
 * Advice for an admin picking a profile colour, or null when it works on both
 * themes. The name text always stays readable (see {@link themeTint}); what a
 * near-white or near-black colour loses is the tag itself: its dot and outline
 * barely show against the page, so the tag stops reading as that profile.
 */
export function themeColorAdvice(hex: string | null | undefined): string | null {
  const rgb = parseHex(hex)
  if (!rgb) return hex ? 'Use a hex colour like #2563eb.' : null
  if (contrastRatio(rgb, LIGHT_PAGE) < 1.5) {
    return 'Very light: its dot and outline barely show on a light page, so the tag hardly stands out. '
      + 'A deeper shade will be easier to tell apart.'
  }
  if (contrastRatio(rgb, DARK_PAGE) < 1.5) {
    return 'Very dark: its dot and outline barely show in dark mode, so the tag hardly stands out. '
      + 'A brighter shade will be easier to tell apart.'
  }
  return null
}

type Rgb = [number, number, number]

/** `a` weighted by `amount` (0–1) mixed with `b`, per channel in sRGB. */
function mix(a: Rgb, b: Rgb, amount: number): Rgb {
  const t = Math.min(1, Math.max(0, amount))
  return [0, 1, 2].map(i => Math.round(a[i] * t + b[i] * (1 - t))) as Rgb
}

function toHex(rgb: Rgb): string {
  return '#' + rgb.map(c => c.toString(16).padStart(2, '0')).join('')
}

/** WCAG relative luminance. */
function relativeLuminance([r, g, b]: Rgb): number {
  const lin = (c: number) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
}

/** WCAG contrast ratio between two colours, 1–21. Accepts #RRGGBB strings or RGB triples. */
export function contrastRatio(a: Rgb | string, b: Rgb | string): number {
  const x = typeof a === 'string' ? parseHex(a) : a
  const y = typeof b === 'string' ? parseHex(b) : b
  if (!x || !y) return 1
  const [hi, lo] = [relativeLuminance(x), relativeLuminance(y)].sort((m, n) => n - m)
  return (hi + 0.05) / (lo + 0.05)
}

/** Parse a #RRGGBB (or RRGGBB) hex string to [r,g,b], or null if malformed. */
function parseHex(hex: string | null | undefined): Rgb | null {
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
