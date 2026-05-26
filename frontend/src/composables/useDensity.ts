// SPDX-License-Identifier: Apache-2.0
/**
 * Display-density preference (Comfortable vs. Compact).
 *
 * Compact mode reduces the vertical footprint of form controls, buttons,
 * and headings sitewide so power users can see more content at once. The
 * preference is persisted to localStorage (per-device) — different
 * devices may have different screens and density expectations.
 *
 * Mirrors the shape of `useTheme.js`: a top-level `applyDensity()` runs
 * on module load so the class is on `<html>` before first paint, and the
 * composable returns `setDensity` / `syncFromAccount` for the prefs UI.
 *
 * Density is implemented in CSS via `[data-density="compact"]` selectors
 * on `:root` (see `assets/main.css`). No JavaScript runtime cost beyond
 * setting the attribute.
 */
import { ref } from 'vue'

const STORAGE_KEY = 'ldapportal-density'

export type Density = 'comfortable' | 'compact'

const VALID: ReadonlySet<Density> = new Set<Density>(['comfortable', 'compact'])

function readPersisted(): Density {
  if (typeof localStorage === 'undefined') return 'comfortable'
  const raw = localStorage.getItem(STORAGE_KEY)
  return raw && VALID.has(raw as Density) ? (raw as Density) : 'comfortable'
}

const density = ref<Density>(readPersisted())

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
    try { localStorage.setItem(STORAGE_KEY, value) } catch { /* ignore quota / disabled */ }
    applyDensity(value)
  }

  /** Sync from server-side preference. Will be called by the auth store
   *  after login when the backend serves `densityPreference` (currently
   *  unwired — `UpdatePreferencesRequest` lacks the field). When wired,
   *  the server's value wins on this device, overwriting any prior local
   *  choice — appropriate because login is the moment the user expresses
   *  intent on a remembered account. Kept symmetrical with useTheme so
   *  future migration is a one-line change at the call site. */
  function syncFromAccount(serverValue: string | null | undefined): void {
    if (serverValue && VALID.has(serverValue as Density)) {
      density.value = serverValue as Density
      try { localStorage.setItem(STORAGE_KEY, serverValue) } catch { /* ignore */ }
      applyDensity(serverValue as Density)
    }
  }

  return { density, setDensity, syncFromAccount }
}

// Apply density immediately on module load so first paint matches.
applyDensity(density.value)
