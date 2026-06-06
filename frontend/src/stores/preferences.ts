// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getPreferences,
  patchPreferences,
  type PreferencesDocument,
} from '@/api/preferences'

/**
 * The single client-side home for every UI customization the user makes —
 * theme, display density, table column state, saved filter views, search
 * history, modal sizes, sidebar state, and anything a future feature wants to
 * remember. These used to be scattered across browser localStorage; they now
 * live in one server-side document (see the backend's {@code UserPreferences})
 * so they follow the user across browsers and devices.
 *
 * Composables and components don't talk to the API directly — they read/write
 * through {@link read} / {@link write}, scoped by `namespace` (e.g. `tables`,
 * `filters`, `appearance`) and `key` (e.g. a table id, a page id). Writes are
 * applied to the in-memory document immediately (so reads are consistent) and
 * flushed to the server as a debounced, coalesced merge-patch — dragging a
 * column width fires one request, not one per pixel.
 */

/** Top-level namespaces, mirrored from the backend's allow-list. */
export type PreferenceNamespace =
  | 'appearance'
  | 'tables'
  | 'filters'
  | 'search'
  | 'modals'
  | 'sidebar'

const FLUSH_DELAY_MS = 350

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/** Deep-merge `src` into `dst` (objects merge, everything else replaces). */
function deepMerge(
  dst: Record<string, unknown>,
  src: Record<string, unknown>,
): Record<string, unknown> {
  for (const [k, v] of Object.entries(src)) {
    if (isPlainObject(v) && isPlainObject(dst[k])) {
      deepMerge(dst[k] as Record<string, unknown>, v)
    } else {
      dst[k] = v
    }
  }
  return dst
}

function clone<T>(value: T): T {
  return value === undefined ? value : (JSON.parse(JSON.stringify(value)) as T)
}

export const usePreferencesStore = defineStore('preferences', () => {
  const doc = ref<PreferencesDocument>({})
  const ready = ref(false)

  // Coalesced, debounced outbound merge-patch.
  let pending: Record<string, unknown> = {}
  let timer: ReturnType<typeof setTimeout> | null = null

  /**
   * Load the document from the server. Resilient by design: any failure
   * (offline, or a self-service principal who gets a 403 because they don't
   * persist UI prefs) leaves an empty document and a `ready` store rather than
   * blocking app start. Runs the one-time localStorage migration after load.
   */
  async function hydrate(): Promise<void> {
    try {
      const { data } = await getPreferences()
      doc.value = isPlainObject(data) ? (data as PreferencesDocument) : {}
    } catch {
      doc.value = {}
    } finally {
      ready.value = true
    }
    await migrateLegacyLocalStorage()
  }

  /** Reset on logout so the next user doesn't inherit this document. */
  function clear(): void {
    if (timer) { clearTimeout(timer); timer = null }
    pending = {}
    doc.value = {}
    ready.value = false
  }

  /**
   * Read a key within a namespace, returning a deep clone so callers can hold
   * and mutate their own copy without aliasing the store. Falls back to
   * `fallback` when absent.
   */
  function read<T>(namespace: PreferenceNamespace, key: string, fallback: T): T {
    const ns = doc.value[namespace]
    if (isPlainObject(ns) && key in ns) {
      return clone(ns[key]) as T
    }
    return fallback
  }

  /** Whole-namespace read (clone), e.g. all saved table prefs. */
  function readNamespace<T>(namespace: PreferenceNamespace): Record<string, T> {
    const ns = doc.value[namespace]
    return isPlainObject(ns) ? (clone(ns) as Record<string, T>) : {}
  }

  /**
   * Write a key within a namespace. Updates the in-memory document and queues
   * a `{ [namespace]: { [key]: value } }` merge-patch to the server.
   */
  function write(namespace: PreferenceNamespace, key: string, value: unknown): void {
    const ns = isPlainObject(doc.value[namespace])
      ? (doc.value[namespace] as Record<string, unknown>)
      : {}
    ns[key] = clone(value)
    doc.value = { ...doc.value, [namespace]: ns }
    queuePatch({ [namespace]: { [key]: clone(value) } })
  }

  /** Delete a key within a namespace (sends a null so the server drops it). */
  function remove(namespace: PreferenceNamespace, key: string): void {
    const ns = doc.value[namespace]
    if (isPlainObject(ns) && key in ns) {
      delete ns[key]
      doc.value = { ...doc.value, [namespace]: ns }
    }
    queuePatch({ [namespace]: { [key]: null } })
  }

  function queuePatch(patch: Record<string, unknown>): void {
    deepMerge(pending, patch)
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { void flush() }, FLUSH_DELAY_MS)
  }

  /** Send any queued changes now. Safe to call when nothing is pending. */
  async function flush(): Promise<void> {
    if (timer) { clearTimeout(timer); timer = null }
    if (Object.keys(pending).length === 0) return
    const outbound = pending
    pending = {}
    try {
      await patchPreferences(outbound)
    } catch {
      // Re-queue so the change isn't lost; it'll retry on the next write/flush.
      deepMerge(pending, outbound)
    }
  }

  // ── One-time migration off localStorage ───────────────────────────────────
  // Read any legacy localStorage keys, fold the ones the server doesn't already
  // have into the document, then delete them. Idempotent: once removed there's
  // nothing left to migrate.
  async function migrateLegacyLocalStorage(): Promise<void> {
    if (typeof localStorage === 'undefined') return
    const patch: Record<string, Record<string, unknown>> = {}
    const consumed: string[] = []

    const has = (ns: string, key: string): boolean => {
      const n = doc.value[ns]
      return isPlainObject(n) && key in n
    }
    const takeJson = (lsKey: string): unknown | undefined => {
      const raw = localStorage.getItem(lsKey)
      if (raw == null) return undefined
      consumed.push(lsKey)
      try { return JSON.parse(raw) } catch { return raw }
    }

    // appearance (stored as bare strings)
    const legacyTheme = localStorage.getItem('ldapportal-theme')
    if (legacyTheme && !has('appearance', 'theme')) {
      ;(patch.appearance ??= {}).theme = legacyTheme
    }
    if (legacyTheme != null) consumed.push('ldapportal-theme')
    const legacyDensity = localStorage.getItem('ldapportal-density')
    if (legacyDensity && !has('appearance', 'density')) {
      ;(patch.appearance ??= {}).density = legacyDensity
    }
    if (legacyDensity != null) consumed.push('ldapportal-density')

    // tables + filters (one key per table / page)
    for (let i = 0; i < localStorage.length; i++) {
      const lsKey = localStorage.key(i)
      if (!lsKey) continue
      if (lsKey.startsWith('ldapportal.table-prefs.v1:')) {
        const tableKey = lsKey.slice('ldapportal.table-prefs.v1:'.length)
        if (!has('tables', tableKey)) {
          const value = takeJson(lsKey)
          if (value !== undefined) (patch.tables ??= {})[tableKey] = value
        } else { consumed.push(lsKey) }
      } else if (lsKey.startsWith('saved-filters:')) {
        const pageKey = lsKey.slice('saved-filters:'.length)
        if (!has('filters', pageKey)) {
          const value = takeJson(lsKey)
          if (value !== undefined) (patch.filters ??= {})[pageKey] = value
        } else { consumed.push(lsKey) }
      }
    }

    // directory search history + saved searches
    const history = takeJson('ldap-search-history')
    const saved = takeJson('ldap-saved-searches')
    if ((history !== undefined && !has('search', 'directory'))
        || (saved !== undefined && !has('search', 'directory'))) {
      const directory: Record<string, unknown> = {}
      if (history !== undefined) directory.history = history
      if (saved !== undefined) directory.saved = saved
      ;(patch.search ??= {}).directory = directory
    }

    if (Object.keys(patch).length > 0) {
      try {
        const { data } = await patchPreferences(patch)
        if (isPlainObject(data)) doc.value = data as PreferencesDocument
      } catch {
        // Migration is best-effort; leave localStorage in place to retry later.
        return
      }
    }
    // Only clear localStorage once any needed upload succeeded.
    for (const k of consumed) {
      try { localStorage.removeItem(k) } catch { /* ignore */ }
    }
  }

  return { doc, ready, hydrate, clear, read, readNamespace, write, remove, flush }
})
