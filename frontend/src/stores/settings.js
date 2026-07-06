// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getBranding } from '@/api/settings'

// App default brand colours, by role:
//   • Primary  = "chrome" — the dominant brand surface (sidebar/nav, login
//     header, app name). Tailwind slate-800: a dark, cool blue-gray.
//   • Secondary = "accent" — interactive emphasis (primary CTAs, links,
//     focus rings, active states). Tailwind blue-600: a saturated sibling of
//     the slate chrome so the two read as one coordinated palette.
// Exported so the settings form falls back to the same values the app
// actually renders when no custom colour is saved (otherwise the branding
// pickers show colours that don't match the UI).
export const DEFAULT_PRIMARY_COLOUR   = '#1e293b' // slate-800 (chrome)
export const DEFAULT_SECONDARY_COLOUR = '#2563eb' // blue-600 (accent)
const DEFAULT_PRIMARY   = DEFAULT_PRIMARY_COLOUR
const DEFAULT_SECONDARY = DEFAULT_SECONDARY_COLOUR

/**
 * Pick a readable text colour (white or near-black slate-900) for a given
 * background, using WCAG relative luminance so branded surfaces never end up
 * with unreadable text. Falls back to white for malformed input.
 */
function readableOn(hex) {
  const c = String(hex || '').trim().replace('#', '')
  if (c.length !== 6) return '#ffffff'
  const channel = (i) => {
    const v = parseInt(c.slice(i, i + 2), 16) / 255
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)
  }
  const L = 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
  const contrastWhite = 1.05 / (L + 0.05)
  const contrastBlack = (L + 0.05) / 0.05
  return contrastWhite >= contrastBlack ? '#ffffff' : '#0f172a'
}

export const useSettingsStore = defineStore('settings', () => {
  const appName          = ref('LDAP Portal')
  const logoUrl          = ref(null)
  const primaryColour    = ref(DEFAULT_PRIMARY)
  const secondaryColour  = ref(DEFAULT_SECONDARY)
  const enabledAuthTypes = ref(['LOCAL'])
  const loaded           = ref(false)

  /**
   * Fetch branding from the public endpoint and apply CSS custom properties.
   * Safe to call multiple times — only fetches once.
   */
  async function init() {
    if (loaded.value) return
    try {
      const { data } = await getBranding()
      appName.value          = data.appName || 'LDAP Portal'
      logoUrl.value          = data.logoUrl || null
      primaryColour.value    = data.primaryColour  || DEFAULT_PRIMARY
      secondaryColour.value  = data.secondaryColour || DEFAULT_SECONDARY
      enabledAuthTypes.value = data.enabledAuthTypes || ['LOCAL']
    } catch (e) {
      console.warn('Failed to load branding settings:', e)
    }
    applyColours()
    loaded.value = true
  }

  /** Re-apply after settings are saved from SettingsView. */
  function apply(branding) {
    appName.value         = branding.appName || 'LDAP Portal'
    logoUrl.value         = branding.logoUrl || null
    primaryColour.value   = branding.primaryColour  || DEFAULT_PRIMARY
    secondaryColour.value = branding.secondaryColour || DEFAULT_SECONDARY
    // Refresh the enabled auth methods too, so changes saved in Settings →
    // Authentication (e.g. turning on WEBSEAL) take effect immediately. Without
    // this, init()'s fetch-once guard leaves the cached boot-time list in place
    // until a full page reload — so the new method is missing from the login
    // page and the admin-user "Auth type" dropdown. Only overwrite when the
    // caller supplies the list, so a branding-only apply() doesn't blank it.
    if (Array.isArray(branding.enabledAuthTypes)) {
      enabledAuthTypes.value = [...branding.enabledAuthTypes]
    }
    applyColours()
  }

  function applyColours() {
    const root = document.documentElement
    // Chrome + accent, plus the readable text colour to use on top of each.
    // Hover/tint shades are derived in CSS via color-mix() from these bases.
    root.style.setProperty('--color-primary', primaryColour.value)
    root.style.setProperty('--color-secondary', secondaryColour.value)
    root.style.setProperty('--color-primary-on', readableOn(primaryColour.value))
    root.style.setProperty('--color-secondary-on', readableOn(secondaryColour.value))
    document.title = appName.value
  }

  return { appName, logoUrl, primaryColour, secondaryColour, enabledAuthTypes, loaded, init, apply }
})
