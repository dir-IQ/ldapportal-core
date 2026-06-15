// SPDX-License-Identifier: Apache-2.0
import { ref } from 'vue'
import { readPrefsHint, setPrefsHint } from '@/utils/prefsHint'
import { usePreferencesStore } from '@/stores/preferences'

/**
 * App theme (light / dark / system). The authoritative value lives in the
 * server-side preferences document (namespace `appearance`, key `theme`); this
 * composable applies it to `<html>` and keeps the pre-paint FOUC hint cookie in
 * sync. Nothing is stored in localStorage.
 *
 * Initial value comes from the hint cookie so the toggle reflects the right
 * state before `/auth/me` resolves; `syncFromAccount` then reconciles with the
 * server value on login.
 */
const VALID = ['light', 'dark', 'system']

function initialTheme() {
  const hint = readPrefsHint().theme
  return hint && VALID.includes(hint) ? hint : 'light'
}

const theme = ref(initialTheme())

function applyTheme(value) {
  const root = document.documentElement
  if (value === 'system') {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    root.setAttribute('data-theme', prefersDark ? 'dark' : 'light')
  } else {
    root.setAttribute('data-theme', value)
  }
}

export function useTheme() {
  function setTheme(value) {
    if (!VALID.includes(value)) return
    theme.value = value
    applyTheme(value)
    setPrefsHint({ theme: value })
    usePreferencesStore().write('appearance', 'theme', value)
  }

  /** Reconcile with the server-side preference (called after login / init). */
  function syncFromAccount(serverTheme) {
    if (serverTheme && VALID.includes(serverTheme)) {
      theme.value = serverTheme
      applyTheme(serverTheme)
      setPrefsHint({ theme: serverTheme })
    }
  }

  return { theme, setTheme, syncFromAccount }
}

// Apply on module load. The inline script in index.html already set the
// attribute pre-boot from the hint cookie; this keeps the reactive ref and the
// attribute authoritative once the app is running.
applyTheme(theme.value)

// Track system preference changes while in "system" mode (one listener for the
// whole app, registered at module scope). Guarded for non-browser / test
// environments that may not implement matchMedia.
if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (theme.value === 'system') applyTheme('system')
  })
}
