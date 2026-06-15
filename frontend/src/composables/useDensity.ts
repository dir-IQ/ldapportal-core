// SPDX-License-Identifier: Apache-2.0
/**
 * Display-density preference (Comfortable vs. Compact).
 *
 * Compact mode reduces the vertical footprint of form controls, buttons, and
 * headings sitewide so power users can see more content at once. The
 * authoritative value lives in the server-side preferences document (namespace
 * `appearance`, key `density`) — it follows the user across devices rather than
 * being pinned per-browser. Nothing is stored in localStorage.
 *
 * Mirrors `useTheme.js`: a top-level `applyDensity()` runs on module load so
 * the attribute is on `<html>` (the inline script in index.html already set it
 * pre-boot from the hint cookie), and the composable returns `setDensity` /
 * `syncFromAccount` for the prefs UI.
 *
 * Density is implemented in CSS via `[data-density="compact"]` selectors on
 * `:root` (see `assets/main.css`). No JavaScript runtime cost beyond setting
 * the attribute.
 */
import { ref } from 'vue'
import { readPrefsHint, setPrefsHint } from '@/utils/prefsHint'
import { usePreferencesStore } from '@/stores/preferences'

export type Density = 'comfortable' | 'compact'

const VALID: ReadonlySet<Density> = new Set<Density>(['comfortable', 'compact'])

function initialDensity(): Density {
  const hint = readPrefsHint().density
  return hint && VALID.has(hint as Density) ? (hint as Density) : 'comfortable'
}

const density = ref<Density>(initialDensity())

function applyDensity(value: Density): void {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  if (value === 'compact') {
    root.setAttribute('data-density', 'compact')
  } else {
    root.removeAttribute('data-density')
  }
}

export interface UseDensity {
  density: typeof density
  setDensity: (value: Density) => void
  syncFromAccount: (serverValue: string | null | undefined) => void
}

export function useDensity(): UseDensity {
  function setDensity(value: Density): void {
    if (!VALID.has(value)) return
    density.value = value
    applyDensity(value)
    setPrefsHint({ density: value })
    usePreferencesStore().write('appearance', 'density', value)
  }

  /** Reconcile with the server-side preference (called after login / init). */
  function syncFromAccount(serverValue: string | null | undefined): void {
    if (serverValue && VALID.has(serverValue as Density)) {
      density.value = serverValue as Density
      applyDensity(serverValue as Density)
      setPrefsHint({ density: serverValue })
    }
  }

  return { density, setDensity, syncFromAccount }
}

// Apply immediately on module load so first paint matches.
applyDensity(density.value)
