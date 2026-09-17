<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
/**
 * ResultsTable — shared rich table for report-style result sets.
 *
 * Features:
 *   - Click-to-sort columns (with persisted sort state)
 *   - Drag-to-resize columns (widths persisted to localStorage)
 *   - Pagination with selectable page size (25 / 50 / 100 / All)
 *   - Quick-filter input (case-insensitive substring match across visible cols)
 *   - Column show/hide picker (persisted)
 *   - Sticky header
 *   - Per-cell rendering via scoped slot `cell-<key>` so callers keep
 *     full control over badges, dates, DN truncation, etc.
 *   - Per-header controls via scoped slot `header-<key>` (rendered after
 *     the label), e.g. a column-level toggle. Use `@click.stop` inside it
 *     so the control doesn't also trigger the header's sort.
 *   - Optional checkbox selection column (v-model:selectedKeys + rowKey).
 *
 * Caller contract:
 *   <ResultsTable
 *     table-key="audit-reports"          // localStorage namespace
 *     :columns="[{ key: 'Name', label: 'Name' }, ...]"
 *     :rows="rows"
 *     :selectable="true"                 // optional
 *     row-key="id"                       // optional, default 'id'
 *     v-model:selectedKeys="selected"    // optional Set<unknown>
 *   >
 *     <template #cell-Status="{ value, row }">
 *       <span :class="badgeClass(value)">{{ value }}</span>
 *     </template>
 *   </ResultsTable>
 */
import { computed, ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import {
  useTablePreferences,
  sortRows,
  filterRows,
  paginate,
  type SortableRow,
} from '@/composables/useTablePreferences'
import EmptyState from '@/components/EmptyState.vue'

export interface ColumnDef {
  /** Stable column identifier (also used as the data accessor `row[key]`). */
  key: string
  /** Display label in the header. Falls back to `key`. */
  label?: string
  /** If false, header click is a no-op and no sort indicator shows. */
  sortable?: boolean
  /** Default width in pixels (only used when no persisted width exists). */
  defaultWidth?: number
  /** If true, cannot be hidden via the column picker. */
  alwaysVisible?: boolean
  /**
   * If true, the column starts hidden on first render (no
   * localStorage entry yet) but surfaces in the column picker so
   * the user can opt in. Useful when a caller declares many
   * dynamically-discovered columns and wants only a curated subset
   * shown by default. After the user touches the picker, their
   * choice persists and this flag is ignored.
   */
  defaultHidden?: boolean
  /**
   * Pin this column to the right edge of the horizontal scroll area so it
   * stays visible while the rest of the table scrolls (typically a row-actions
   * column). Should be the right-most column; only one right-pinned column is
   * supported.
   */
  pinned?: boolean
}

const props = withDefaults(defineProps<{
  tableKey: string
  columns: ColumnDef[]
  rows: SortableRow[]
  rowKey?: string
  selectable?: boolean
  selectedKeys?: Set<unknown>
  /** Page-size choices shown in the footer dropdown. 0 = "All". */
  pageSizes?: number[]
  /** Optional zebra-striping toggle. */
  striped?: boolean
  /**
   * True while the caller is waiting on the backend. With no rows yet this
   * renders "Loading…" in place of the empty state, so an operator never
   * reads "No <things> found" for a query that hasn't answered.
   */
  loading?: boolean
  /** Empty-state text. */
  emptyText?: string
  /** Empty-state icon (see EmptyState.vue: folder|users|search|shield|clipboard). */
  emptyIcon?: string
  /** When true, clicking a row emits `row-click`. */
  rowClickable?: boolean
  /**
   * Size columns to content on first view — only when no column widths are
   * saved yet — running the same measurement pass as the "Fit columns" action.
   * Avoids the fixed-layout table padding columns out to fill the container
   * (e.g. trailing dead space after a narrow actions column). A later manual
   * resize persists and takes precedence; this never overrides saved widths.
   */
  autoFitFirstView?: boolean
}>(), {
  rowKey: 'id',
  selectable: false,
  selectedKeys: () => new Set(),
  pageSizes: () => [25, 50, 100, 0],
  striped: true,
  loading: false,
  emptyText: 'No results.',
  emptyIcon: 'folder',
  rowClickable: false,
  autoFitFirstView: false,
})

const emit = defineEmits<{
  (e: 'update:selectedKeys', v: Set<unknown>): void
  (e: 'row-click', row: SortableRow): void
}>()

const { prefs, setWidth, resetWidths, toggleHidden, setPageSize, setSort, recordSeen } = useTablePreferences(props.tableKey)

// Seed default-hidden columns the first time each column key is
// shown to the user. Re-runs whenever the caller's `columns` prop
// changes, so dynamically-discovered columns (e.g. UserListView's
// union-of-LDAP-attributes) get their defaultHidden flag honoured
// the first time they appear — even if the user already had
// localStorage prefs from before those columns existed. Once a key
// is in `prefs.seenColumns`, its defaultHidden flag is ignored and
// the user's show/hide choice wins.
function syncSeen(): void {
  recordSeen(props.columns
    .filter(c => !c.alwaysVisible)
    .map(c => ({ key: c.key, defaultHidden: c.defaultHidden })))
}
syncSeen()
watch(() => props.columns.map(c => c.key).join('\u0000'), syncSeen)

// ── Column visibility ───────────────────────────────────────────────────────
const hiddenSet = computed(() => new Set(prefs.value.hidden))
const visibleColumns = computed<ColumnDef[]>(() =>
  props.columns.filter(c => c.alwaysVisible || !hiddenSet.value.has(c.key)),
)
const visibleKeys = computed(() => visibleColumns.value.map(c => c.key))

// ── Filtering ───────────────────────────────────────────────────────────────
const query = ref('')
const filteredRows = computed(() => filterRows(props.rows, query.value, visibleKeys.value))

// ── Sorting ─────────────────────────────────────────────────────────────────
const sortedRows = computed(() =>
  sortRows(filteredRows.value, prefs.value.sortKey, prefs.value.sortAsc),
)

function onHeaderClick(col: ColumnDef): void {
  if (col.sortable === false) return
  const same = prefs.value.sortKey === col.key
  setSort(col.key, same ? !prefs.value.sortAsc : true)
  page.value = 0
}

// ── Pagination ──────────────────────────────────────────────────────────────
const page = ref(0)
const pageInfo = computed(() => paginate(sortedRows.value, page.value, prefs.value.pageSize))
const pagedRows = computed(() => pageInfo.value.rows)
const totalPages = computed(() => pageInfo.value.totalPages)

function gotoPage(p: number): void {
  page.value = Math.min(Math.max(0, p), totalPages.value - 1)
}

// ── Column resize ───────────────────────────────────────────────────────────
const widthFor = (col: ColumnDef): string => {
  const persisted = prefs.value.widths[col.key]
  if (persisted) return persisted + 'px'
  if (col.defaultWidth) return col.defaultWidth + 'px'
  return 'auto'
}

interface ResizeState {
  col: string
  startX: number
  startWidth: number
}
const resizing = ref<ResizeState | null>(null)

function startResize(col: ColumnDef, e: PointerEvent): void {
  if (!(e.target instanceof HTMLElement)) return
  e.preventDefault()
  e.stopPropagation()
  // Find the current rendered width of the th so the drag is anchored to
  // whatever the user can see, not whatever happens to be persisted.
  const th = e.target.closest('th')
  const currentWidth = th ? th.getBoundingClientRect().width : (prefs.value.widths[col.key] ?? 120)
  resizing.value = { col: col.key, startX: e.clientX, startWidth: currentWidth }
  window.addEventListener('pointermove', onResizeMove)
  window.addEventListener('pointerup', endResize, { once: true })
}

function onResizeMove(e: PointerEvent): void {
  const r = resizing.value
  if (!r) return
  const next = r.startWidth + (e.clientX - r.startX)
  setWidth(r.col, next)
}

function endResize(): void {
  resizing.value = null
  window.removeEventListener('pointermove', onResizeMove)
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onResizeMove)
})

// ── Fit columns to content ───────────────────────────────────────────────────
// "Auto-fit" each visible column to the width its content actually needs —
// the standard spreadsheet/data-grid gesture. Because the table is
// table-layout:fixed (so cells clip rather than size to content, which is what
// makes resizing predictable), we can't just read scrollWidth: a column wider
// than its content reports the cell width, not the natural width, so it could
// never shrink. Instead we flip the table to table-layout:auto for one
// measurement pass — where each column sizes to its widest cell (header
// included) — read those widths, then snap back to fixed with them persisted.
// The flip happens entirely within microtasks (nextTick), before the browser
// paints, so there's no visible flash.
const tableRef = ref<HTMLTableElement | null>(null)
const measuring = ref(false)

// Floor keeps a column readable (header + sort caret); ceiling stops a single
// long value (e.g. a DN) from blowing the column out — it ellipsizes instead.
// Slack absorbs sub-pixel rounding so content doesn't sit right on the edge.
const FIT_MIN_PX = 64
const FIT_MAX_PX = 480
const FIT_SLACK_PX = 8

async function measureNaturalWidths(): Promise<Map<string, number>> {
  const widths = new Map<string, number>()
  if (!pagedRows.value.length && !visibleColumns.value.length) return widths
  measuring.value = true
  await nextTick()
  const ths = tableRef.value?.querySelectorAll<HTMLElement>('th[data-col-key]')
  ths?.forEach((th) => {
    const key = th.dataset.colKey
    if (!key) return
    const natural = Math.ceil(th.getBoundingClientRect().width) + FIT_SLACK_PX
    widths.set(key, Math.min(FIT_MAX_PX, Math.max(FIT_MIN_PX, natural)))
  })
  measuring.value = false
  return widths
}

async function fitColumns(): Promise<void> {
  const widths = await measureNaturalWidths()
  for (const [key, px] of widths) setWidth(key, px)
}

async function fitColumn(col: ColumnDef): Promise<void> {
  const widths = await measureNaturalWidths()
  const px = widths.get(col.key)
  if (px != null) setWidth(col.key, px)
}

// First-view auto-fit (opt-in). Fires once, when rows first arrive and the
// table has no saved column widths — then fitColumns() persists widths and the
// guard/saved-widths check keep it from running again. Because it bails the
// moment any width is persisted, a user's manual resize is never overridden.
const autoFitted = ref(false)
watch(pagedRows, async (rows) => {
  if (!props.autoFitFirstView || autoFitted.value || rows.length === 0) return
  if (Object.keys(prefs.value.widths).length > 0) return
  autoFitted.value = true
  await fitColumns()
}, { immediate: true })

// ── Horizontal overflow detection ─────────────────────────────────────────────
// Column widths are exact: a trailing filler column (see template) is the only
// auto-width column, so it soaks up any slack and the real columns render at
// precisely their set widths — no proportional stretching, which is what made a
// drag-resize "jump" before it settled and a freshly-shown column mis-size.
// A right-pinned column should only stick while the table actually overflows;
// when everything fits, the slack lives to the RIGHT of the pinned column (in
// the filler) and the column sits flush after the data instead of detaching to
// the container edge with an empty gap before it. Defaults to true so the first
// paint and layout-less environments (jsdom) keep the pinned column reachable.
const scrollWrap = ref<HTMLElement | null>(null)
const overflowing = ref(true)
let overflowObserver: ResizeObserver | null = null

function measureOverflow(): void {
  const el = scrollWrap.value
  if (!el || el.clientWidth === 0) return
  overflowing.value = el.scrollWidth - el.clientWidth > 1
}

onMounted(() => {
  measureOverflow()
  if (typeof ResizeObserver !== 'undefined') {
    overflowObserver = new ResizeObserver(() => measureOverflow())
    if (scrollWrap.value) overflowObserver.observe(scrollWrap.value)
  }
})
onBeforeUnmount(() => { overflowObserver?.disconnect(); overflowObserver = null })

// Re-measure once the column set or any width change has rendered.
watch(
  [visibleKeys, () => prefs.value.widths],
  () => nextTick(measureOverflow),
  { deep: true },
)

// ── Pinned (sticky-right) columns ─────────────────────────────────────────────
// A `pinned` column sticks to the right edge of the scroll area so it stays
// visible during horizontal scroll. Sticky cells need their own OPAQUE
// background or scrolled cells bleed through, so we mirror the row's
// stripe/hover state with solid colours (the row's own translucent bg-*/50
// would let content show through) and add a hairline divider to mark the
// boundary. Pinning engages only while the table overflows (see above) and is
// disabled during a fit-columns measurement pass so it doesn't perturb the
// auto-layout sizing. The header keeps `relative` when not pinned so the
// absolutely-positioned resize handle still anchors to it.
function isPinned(col: ColumnDef): boolean {
  return !!col.pinned && !measuring.value && overflowing.value
}
function pinnedHeaderClass(col: ColumnDef): string {
  return isPinned(col)
    ? 'sticky right-0 z-[11] bg-gray-50 border-l border-gray-200'
    : 'relative'
}
function pinnedCellClass(col: ColumnDef, rowIndex: number): string {
  if (!isPinned(col)) return ''
  const bg = props.striped && rowIndex % 2 === 1 ? 'bg-gray-50' : 'bg-white'
  return `sticky right-0 z-[5] ${bg} group-hover:bg-blue-50 border-l border-gray-200`
}

// ── Column picker ───────────────────────────────────────────────────────────
const showColPicker = ref(false)
const colPickerRef = ref<HTMLDivElement | null>(null)

// Toggle a column's visibility. When REVEALING a column we scroll it into view
// after it renders: opt-in columns (Groups, enrichment attrs) sit toward the
// right, so on a wide table they'd otherwise appear off-screen behind the
// pinned actions column — the "I enabled it but couldn't see it" report.
function onColumnToggle(col: ColumnDef): void {
  if (col.alwaysVisible) return
  const revealing = hiddenSet.value.has(col.key)
  toggleHidden(col.key)
  if (!revealing) return
  nextTick(() => {
    const th = tableRef.value?.querySelector<HTMLElement>(`th[data-col-key="${col.key}"]`)
    th?.scrollIntoView({ inline: 'nearest', block: 'nearest' })
  })
}

function onDocClick(e: MouseEvent): void {
  if (!showColPicker.value) return
  if (colPickerRef.value && !colPickerRef.value.contains(e.target as Node)) {
    showColPicker.value = false
  }
}
onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

// ── Selection ───────────────────────────────────────────────────────────────
function rowId(row: SortableRow): unknown {
  return row[props.rowKey]
}

const allPageSelected = computed(() => {
  if (!pagedRows.value.length) return false
  return pagedRows.value.every(r => props.selectedKeys.has(rowId(r)))
})

function toggleSelect(row: SortableRow): void {
  const next = new Set(props.selectedKeys)
  const id = rowId(row)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  emit('update:selectedKeys', next)
}

function toggleSelectAll(): void {
  const next = new Set(props.selectedKeys)
  if (allPageSelected.value) {
    pagedRows.value.forEach(r => next.delete(rowId(r)))
  } else {
    pagedRows.value.forEach(r => next.add(rowId(r)))
  }
  emit('update:selectedKeys', next)
}

// ── Page-size handler ───────────────────────────────────────────────────────
function onPageSizeChange(e: Event): void {
  const v = parseInt((e.target as HTMLSelectElement).value, 10)
  setPageSize(v)
  page.value = 0
}

// Format page-size label: 0 → "All".
function pageSizeLabel(n: number): string {
  return n === 0 ? 'All' : String(n)
}

// Reset page to 0 if filter changes (avoid stranded on empty page).
watch(query, () => { page.value = 0 })
watch(() => props.rows, () => { page.value = 0 })
</script>

<template>
  <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
    <!-- Toolbar -->
    <div class="px-5 py-3 border-b border-gray-200 flex items-center justify-between gap-3 flex-wrap">
      <div class="flex items-center gap-3 flex-1 min-w-0">
        <span class="text-sm text-gray-600 whitespace-nowrap">
          {{ filteredRows.length }} result{{ filteredRows.length !== 1 ? 's' : '' }}
          <span v-if="filteredRows.length !== rows.length" class="text-gray-400">
            (of {{ rows.length }})
          </span>
        </span>
        <input
          v-model="query"
          type="search"
          placeholder="Filter…"
          class="input text-sm w-48"
          aria-label="Filter results"
        />
      </div>
      <div class="flex items-center gap-2">
        <slot name="toolbar" />
        <!-- Fit columns to content (the bulk "auto-size" verb; double-clicking
             a single column's resize handle fits just that one). -->
        <button
          v-if="filteredRows.length"
          @click="fitColumns"
          class="btn-secondary text-xs flex items-center gap-1"
          type="button"
          aria-label="Fit column widths to content"
          title="Fit column widths to content (double-click a column edge to fit one)"
        >
          <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 20 20" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M7 5L4 10l3 5M13 5l3 5-3 5M9 10h2" />
          </svg>
          Fit columns
        </button>
        <!-- Column picker -->
        <div class="relative" ref="colPickerRef">
          <button
            @click.stop="showColPicker = !showColPicker"
            class="btn-secondary text-xs flex items-center gap-1"
            type="button"
            aria-haspopup="true"
            :aria-expanded="showColPicker"
          >
            <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 20 20" stroke="currentColor" stroke-width="1.5">
              <path stroke-linecap="round" d="M3 5h14M3 10h14M3 15h14" />
            </svg>
            Columns
          </button>
          <div
            v-if="showColPicker"
            class="absolute right-0 top-full mt-1 z-20 bg-white border border-gray-200 rounded-lg shadow-lg py-2 w-56 max-h-80 overflow-y-auto"
          >
            <div v-for="col in columns" :key="col.key"
                 class="px-3 py-1.5 text-sm hover:bg-gray-50 flex items-center gap-2 cursor-pointer"
                 :class="col.alwaysVisible ? 'opacity-50 cursor-not-allowed' : ''"
                 @click="onColumnToggle(col)">
              <input
                type="checkbox"
                :checked="!hiddenSet.has(col.key) || col.alwaysVisible"
                :disabled="col.alwaysVisible"
                @click.stop
                @change="onColumnToggle(col)"
                class="rounded border-gray-300"
              />
              <span class="text-gray-700">{{ col.label || col.key }}</span>
            </div>
            <!-- Layout reset: clears any fitted / dragged widths back to the
                 column defaults. Paired with "Fit columns" so the width
                 customisation is reversible. -->
            <button
              type="button"
              class="w-full text-left px-3 py-1.5 mt-1 pt-2 border-t border-gray-100 text-sm text-gray-600 hover:bg-gray-50"
              @click="resetWidths(); showColPicker = false"
            >Reset column widths</button>
          </div>
        </div>
      </div>
    </div>

    <!-- Loading state: only while there are no rows to show yet. A refresh
         over existing rows keeps them on screen until the response lands. -->
    <div v-if="loading && rows.length === 0" role="status" class="px-4 py-8 text-center text-sm text-gray-500">
      Loading…
    </div>
    <!-- Empty state -->
    <EmptyState v-else-if="rows.length === 0" :icon="emptyIcon" :title="emptyText" />
    <EmptyState
      v-else-if="filteredRows.length === 0"
      icon="search"
      title="No results match the filter."
    />

    <!-- Table -->
    <div v-else ref="scrollWrap" class="overflow-x-auto">
      <!-- During a fit-columns measurement pass the table goes table-layout:auto
           AND width:auto (shrink-to-fit) so each column sizes to its content
           rather than being stretched to fill the container; otherwise it's the
           usual fixed-layout, full-width, resizable table. -->
      <table
        ref="tableRef"
        class="w-full text-sm density-table"
        :style="measuring ? { tableLayout: 'auto', width: 'auto' } : { tableLayout: 'fixed' }"
      >
        <thead class="bg-gray-50 sticky top-0 z-10">
          <tr class="border-b border-gray-200">
            <th v-if="selectable" class="w-8 py-2 px-3">
              <input
                type="checkbox"
                :checked="allPageSelected"
                @change="toggleSelectAll"
                class="rounded border-gray-300"
                aria-label="Select all on page"
              />
            </th>
            <th
              v-for="col in visibleColumns"
              :key="col.key"
              :data-col-key="col.key"
              :style="{ width: measuring ? 'auto' : widthFor(col) }"
              class="text-left py-2 px-4 text-xs font-semibold text-gray-500 uppercase tracking-wider select-none whitespace-nowrap"
              :class="[
                col.sortable === false ? '' : 'cursor-pointer hover:text-gray-700',
                pinnedHeaderClass(col),
              ]"
              @click="onHeaderClick(col)"
            >
              <span class="truncate inline-block max-w-full align-bottom">
                {{ col.label || col.key }}
              </span>
              <span
                v-if="prefs.sortKey === col.key && col.sortable !== false"
                class="ml-0.5"
              >{{ prefs.sortAsc ? '▲' : '▼' }}</span>
              <!-- Optional per-column header control (e.g. a toggle). The
                   caller is responsible for @click.stop so it doesn't
                   trigger the header sort. -->
              <slot :name="`header-${col.key}`" :col="col" />
              <!-- Resize handle. Drag to resize; double-click to auto-fit this
                   one column to its content (standard data-grid gesture). -->
              <span
                class="absolute top-0 right-0 h-full w-1.5 cursor-col-resize hover:bg-blue-300 active:bg-blue-400"
                @pointerdown.stop="startResize(col, $event)"
                @click.stop
                @dblclick.stop="fitColumn(col)"
                role="separator"
                aria-label="Resize column (double-click to fit to content)"
              ></span>
            </th>
            <!-- Trailing filler: the sole auto-width column. It soaks up any
                 slack so the real columns keep their exact set widths, and it
                 parks that slack to the RIGHT of a pinned column. Omitted during
                 the fit-columns measurement pass so it can't skew the readings. -->
            <th v-if="!measuring" aria-hidden="true" class="p-0 bg-gray-50"></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(row, i) in pagedRows"
            :key="(rowId(row) as string | number) ?? i"
            :class="[
              'group',
              striped && i % 2 === 1 ? 'bg-gray-50/50' : 'bg-white',
              rowClickable ? 'cursor-pointer hover:bg-blue-50' : 'hover:bg-blue-50/30',
              'border-b border-gray-100/50',
            ]"
            @click="rowClickable && emit('row-click', row)"
          >
            <td v-if="selectable" class="w-8 py-2 px-3" @click.stop>
              <input
                type="checkbox"
                :checked="selectedKeys.has(rowId(row))"
                @change="toggleSelect(row)"
                class="rounded border-gray-300"
              />
            </td>
            <td
              v-for="col in visibleColumns"
              :key="col.key"
              :style="{ width: measuring ? 'auto' : widthFor(col) }"
              class="py-2 px-4 text-[13px] text-gray-700"
              :class="[
                measuring ? 'whitespace-nowrap' : 'overflow-hidden',
                pinnedCellClass(col, i),
              ]"
              :title="typeof row[col.key] === 'string' ? (row[col.key] as string) : undefined"
            >
              <slot
                :name="`cell-${col.key}`"
                :row="row"
                :value="row[col.key]"
                :col="col"
              >
                <!--
                  Fallback `cell` slot lets a caller handle EVERY column with
                  one switch (e.g. dispatching on col.key for dates / DNs /
                  badges). Slots named after columns with spaces don't work
                  as static slot names, so this is the escape hatch.
                -->
                <slot name="cell" :row="row" :value="row[col.key]" :col="col">
                  <span class="truncate block">{{ row[col.key] ?? '' }}</span>
                </slot>
              </slot>
            </td>
            <!-- Trailing filler (see thead): absorbs slack; inherits the row's
                 background so it reads as empty table space. -->
            <td v-if="!measuring" aria-hidden="true" class="p-0"></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Pagination footer -->
    <div
      v-if="rows.length > 0"
      class="px-5 py-3 border-t border-gray-200 flex items-center justify-between gap-3 flex-wrap text-xs"
    >
      <div class="flex items-center gap-2">
        <label class="text-gray-500">Rows per page</label>
        <select
          :value="prefs.pageSize"
          @change="onPageSizeChange"
          class="input text-xs py-1"
        >
          <option v-for="n in pageSizes" :key="n" :value="n">{{ pageSizeLabel(n) }}</option>
        </select>
      </div>
      <div class="flex items-center gap-2">
        <span class="text-gray-500">Page {{ totalPages === 0 ? 0 : page + 1 }} of {{ totalPages }}</span>
        <button
          @click="gotoPage(0)"
          :disabled="page === 0"
          class="btn-secondary text-xs px-2"
          aria-label="First page"
        >«</button>
        <button
          @click="gotoPage(page - 1)"
          :disabled="page === 0"
          class="btn-secondary text-xs"
        >Prev</button>
        <button
          @click="gotoPage(page + 1)"
          :disabled="page >= totalPages - 1"
          class="btn-secondary text-xs"
        >Next</button>
        <button
          @click="gotoPage(totalPages - 1)"
          :disabled="page >= totalPages - 1"
          class="btn-secondary text-xs px-2"
          aria-label="Last page"
        >»</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
@reference "tailwindcss";
</style>
