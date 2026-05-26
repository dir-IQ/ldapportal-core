// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getBranding } from '@/api/settings'

// App primary brand color — Tailwind slate-800. Cool, blue-gray
// chrome that stays distinct from the saturated blue used on
// primary CTAs.
const DEFAULT_PRIMARY   = '#1e293b'
const DEFAULT_SECONDARY = '#1e293b'

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
    applyColours()
  }

  function applyColours() {
    const root = document.documentElement
    root.style.setProperty('--color-primary', primaryColour.value)
    root.style.setProperty('--color-secondary', secondaryColour.value)
    document.title = appName.value
  }

  return { appName, logoUrl, primaryColour, secondaryColour, enabledAuthTypes, loaded, init, apply }
})
