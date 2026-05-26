// SPDX-License-Identifier: Apache-2.0
import { ref, onMounted } from 'vue'

const STORAGE_KEY = 'ldapportal-theme'

const theme = ref(localStorage.getItem(STORAGE_KEY) || 'system')

function applyTheme(value) {
  const root = document.documentElement
  if (value === 'system') {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    root.setAttribute('data-theme', prefersDark ? 'dark' : 'light')
  } else {
    root.setAttribute('data-theme', value)
  }
}

/**
 * Composable for managing the app theme (light / dark / system).
 * Persists in both localStorage (for immediate load) and the user's
 * account preferences (via the auth store / API).
 */
export function useTheme() {
  function setTheme(value) {
    theme.value = value
    localStorage.setItem(STORAGE_KEY, value)
    applyTheme(value)
  }

  /** Sync from server-side preference (called after login / init). */
  function syncFromAccount(serverTheme) {
    if (serverTheme && ['light', 'dark', 'system'].includes(serverTheme)) {
      theme.value = serverTheme
      localStorage.setItem(STORAGE_KEY, serverTheme)
      applyTheme(serverTheme)
    }
  }

  onMounted(() => {
    applyTheme(theme.value)

    // Listen for system preference changes when in "system" mode
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      if (theme.value === 'system') applyTheme('system')
    })
  })

  return { theme, setTheme, syncFromAccount }
}

// Apply theme immediately on module load so there's no flash
applyTheme(theme.value)
