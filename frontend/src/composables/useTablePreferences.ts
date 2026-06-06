// SPDX-License-Identifier: Apache-2.0
/**
 * Persist per-table user preferences (column widths, hidden columns, page size,
 * sort state) so they survive page reloads. Storage is the server-side
 * preferences document (namespace `tables`, keyed by `tableKey`) via the
 * preferences store — not localStorage — so a user's table layout follows them
 * across browsers and devices.
 *
 * Each table has a unique `tableKey` (e.g. "directory-search-results",
 * "audit-reports", "report-jobs") which forms the document key.
 *
 * The composable is deliberately framework-agnostic — it returns a `prefs`
 * ref plus mutator helpers. ResultsTable.vue wires it to the local sort/
 * page/column state and updates on every change.
 */
import { ref, watch, type Ref } from 'vue'
import { usePreferencesStore } from '@/stores/preferences'

export interface TablePrefs {
  /** Column widths in pixels, keyed by column key. */
  widths: Record<string, number>
  /** Column keys explicitly hidden by the user. */
  hidden: string[]
  /** Page size: 25, 50, 100, or 0 (= All). */
  pageSize: number
  /** Last sort state. Empty key = unsorted. */
  sortKey: string
  sortAsc: boolean
  /**
   * Column keys ResultsTable has presented to this user before.
   * Used to gate {@code defaultHidden} seeding: when a column is
   * discovered for the first time (key not in this list), the
   * composable's {@code recordSeen} helper applies the column's
   * default-hidden flag once, then records the key here so the
   * user's subsequent show/hide choices are respected. Without
   * this list, an existing user's empty {@code hidden} array
   * (from the pre-dynamic-columns era) would suppress every
   * default-hidden seed.
   */
  seenColumns: string[]
}

export function defaultPrefs(): TablePrefs {
  return { widths: {}, hidden: [], pageSize: 50, sortKey: '', sortAsc: true, seenColumns: [] }
}

/** Coerce a stored (possibly partial / legacy) blob into a full TablePrefs. */
function normalize(parsed: Partial<TablePrefs> | null | undefined): TablePrefs {
  const p = parsed ?? {}
  return {
    widths: p.widths && typeof p.widths === 'object' ? p.widths : {},
    hidden: Array.isArray(p.hidden) ? p.hidden : [],
    pageSize: typeof p.pageSize === 'number' ? p.pageSize : 50,
    sortKey: typeof p.sortKey === 'string' ? p.sortKey : '',
    sortAsc: typeof p.sortAsc === 'boolean' ? p.sortAsc : true,
    seenColumns: Array.isArray(p.seenColumns) ? p.seenColumns : [],
  }
}

export interface UseTablePreferences {
  prefs: Ref<TablePrefs>
  setWidth: (col: string, px: number) => void
  toggleHidden: (col: string) => void
  setHidden: (cols: string[]) => void
  setPageSize: (size: number) => void
  setSort: (key: string, asc: boolean) => void
  /**
   * Called by ResultsTable whenever {@code props.columns} changes.
   * For each column key not yet in {@code prefs.seenColumns}, applies
   * its {@code defaultHidden} flag (one-shot — the user's later
   * show/hide actions take precedence) and records the key as seen.
   *
   * @param entries column key + defaultHidden pairs in current render
   */
  recordSeen: (entries: Array<{ key: string, defaultHidden?: boolean }>) => void
  reset: () => void
}

export function useTablePreferences(tableKey: string): UseTablePreferences {
  const prefsStore = usePreferencesStore()

  // First-run defaults are applied via recordSeen on the first
  // column-change tick — it gates default-hidden seeding on
  // {@code seenColumns}, which empty-defaultPrefs initialises to [].
  const prefs = ref<TablePrefs>(
    normalize(prefsStore.read<Partial<TablePrefs> | undefined>('tables', tableKey, undefined)),
  )

  // Persist whenever any field changes. `deep: true` because widths is an
  // object and hidden is an array — shallow watch wouldn't catch internal
  // mutations. The store coalesces + debounces the write to the server.
  watch(prefs, (next) => prefsStore.write('tables', tableKey, next), { deep: true })

  function setWidth(col: string, px: number): void {
    // Clone to trigger watcher; assigning to nested key on a deep ref also
    // works but cloning is more predictable across Vue versions.
    prefs.value = { ...prefs.value, widths: { ...prefs.value.widths, [col]: Math.max(40, Math.round(px)) } }
  }

  function toggleHidden(col: string): void {
    const set = new Set(prefs.value.hidden)
    if (set.has(col)) set.delete(col)
    else set.add(col)
    prefs.value = { ...prefs.value, hidden: [...set] }
  }

  function setHidden(cols: string[]): void {
    prefs.value = { ...prefs.value, hidden: [...cols] }
  }

  function setPageSize(size: number): void {
    prefs.value = { ...prefs.value, pageSize: size }
  }

  function setSort(key: string, asc: boolean): void {
    prefs.value = { ...prefs.value, sortKey: key, sortAsc: asc }
  }

  function reset(): void {
    prefs.value = defaultPrefs()
  }

  function recordSeen(entries: Array<{ key: string, defaultHidden?: boolean }>): void {
    if (entries.length === 0) return
    const seen = new Set(prefs.value.seenColumns)
    const hidden = new Set(prefs.value.hidden)
    let changed = false
    for (const c of entries) {
      if (seen.has(c.key)) continue
      seen.add(c.key)
      if (c.defaultHidden) hidden.add(c.key)
      changed = true
    }
    if (changed) {
      prefs.value = { ...prefs.value, seenColumns: [...seen], hidden: [...hidden] }
    }
  }

  return { prefs, setWidth, toggleHidden, setHidden, setPageSize, setSort, recordSeen, reset }
}

// ── Pure helpers (exported so tests can exercise them without mounting) ──

export interface SortableRow {
  [col: string]: unknown
}

/**
 * Sort rows by a column. Numeric values sort numerically; everything else
 * sorts as locale-aware strings. Null/undefined sort to the end regardless
 * of direction (consistent with most spreadsheet apps).
 */
export function sortRows<T extends SortableRow>(rows: T[], col: string, asc: boolean): T[] {
  if (!col) return rows
  const dir = asc ? 1 : -1
  return [...rows].sort((a, b) => {
    const av = a[col]
    const bv = b[col]
    if (av == null && bv == null) return 0
    if (av == null) return 1
    if (bv == null) return -1
    if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * dir
    return String(av).localeCompare(String(bv)) * dir
  })
}

/**
 * Filter rows by a quick-search string. A row matches if ANY visible column's
 * stringified value contains the query (case-insensitive). Empty query
 * returns rows unchanged.
 */
export function filterRows<T extends SortableRow>(rows: T[], query: string, columns: string[]): T[] {
  const q = query.trim().toLowerCase()
  if (!q) return rows
  return rows.filter((row) => columns.some((c) => {
    const v = row[c]
    if (v == null) return false
    return String(v).toLowerCase().includes(q)
  }))
}

/**
 * Slice rows for the requested page. `pageSize === 0` means "all rows on
 * one page". Returns the rows and the total page count for the caller.
 */
export function paginate<T>(rows: T[], page: number, pageSize: number): { rows: T[], totalPages: number } {
  if (pageSize <= 0) return { rows, totalPages: 1 }
  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize))
  const safePage = Math.min(Math.max(0, page), totalPages - 1)
  const start = safePage * pageSize
  return { rows: rows.slice(start, start + pageSize), totalPages }
}
