// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for ResultsTable.vue.
 *
 * The pure logic (sort/filter/paginate math) is tested in
 * useTablePreferences.spec.ts. These tests verify the wiring: that the
 * component renders rows, responds to header clicks, exposes scoped slots,
 * filters via the toolbar, and emits selection updates.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'

// ResultsTable persists column prefs through the preferences store; stub it so
// these wiring tests don't need an active Pinia or a backend.
const tablePrefs: Record<string, unknown> = {}
vi.mock('@/stores/preferences', () => ({
  usePreferencesStore: () => ({
    read: (_ns: string, key: string, fallback: unknown) =>
      key in tablePrefs ? JSON.parse(JSON.stringify(tablePrefs[key])) : fallback,
    write: (_ns: string, key: string, value: unknown) => {
      tablePrefs[key] = JSON.parse(JSON.stringify(value))
    },
  }),
}))

import ResultsTable from './ResultsTable.vue'
import type { ColumnDef } from './ResultsTable.vue'

const columns: ColumnDef[] = [
  { key: 'id', label: 'ID', alwaysVisible: true },
  { key: 'name', label: 'Name' },
  { key: 'dept', label: 'Department' },
]

const rows = [
  { id: 1, name: 'Alice', dept: 'Eng' },
  { id: 2, name: 'Bob', dept: 'Sales' },
  { id: 3, name: 'Carol', dept: 'Eng' },
  { id: 4, name: 'Dave', dept: 'Marketing' },
]

function makeWrapper(extraProps: Record<string, unknown> = {}) {
  return mount(ResultsTable, {
    props: { tableKey: 'test-' + Math.random(), columns, rows, ...extraProps },
  })
}

beforeEach(() => {
  for (const k of Object.keys(tablePrefs)) delete tablePrefs[k]
})

describe('ResultsTable.vue', () => {
  it('renders one row per data item with cells from the row', () => {
    const w = makeWrapper()
    const bodyRows = w.findAll('tbody tr')
    expect(bodyRows).toHaveLength(4)
    expect(bodyRows[0].text()).toContain('Alice')
    expect(bodyRows[0].text()).toContain('Eng')
  })

  it('renders the result count in the toolbar', () => {
    const w = makeWrapper()
    expect(w.text()).toContain('4 results')
  })

  it('uses the singular form for one result', () => {
    const w = mount(ResultsTable, {
      props: { tableKey: 'singular-' + Math.random(), columns, rows: [rows[0]] },
    })
    expect(w.text()).toContain('1 result')
    expect(w.text()).not.toContain('1 results')
  })

  it('shows the empty state when rows is empty', () => {
    const w = mount(ResultsTable, {
      props: { tableKey: 'empty-' + Math.random(), columns, rows: [], emptyText: 'Nothing here.' },
    })
    expect(w.text()).toContain('Nothing here.')
    expect(w.find('tbody').exists()).toBe(false)
  })

  it('sorts rows when a sortable header is clicked', async () => {
    const w = makeWrapper()
    // Find the Name header (second th since 'id' is first)
    const nameHeader = w.findAll('thead th')[1]
    await nameHeader.trigger('click')

    const cells = w.findAll('tbody tr').map(r => r.findAll('td')[1].text())
    expect(cells).toEqual(['Alice', 'Bob', 'Carol', 'Dave'])

    // Click again — descending
    await nameHeader.trigger('click')
    const cellsDesc = w.findAll('tbody tr').map(r => r.findAll('td')[1].text())
    expect(cellsDesc).toEqual(['Dave', 'Carol', 'Bob', 'Alice'])
  })

  it('does not sort when sortable: false', async () => {
    const cols: ColumnDef[] = [
      { key: 'id', label: 'ID', sortable: false },
      { key: 'name', label: 'Name' },
    ]
    const w = mount(ResultsTable, {
      props: { tableKey: 'unsortable-' + Math.random(), columns: cols, rows },
    })
    const idHeader = w.findAll('thead th')[0]
    await idHeader.trigger('click')
    // Order unchanged: 1, 2, 3, 4
    const ids = w.findAll('tbody tr').map(r => r.findAll('td')[0].text())
    expect(ids).toEqual(['1', '2', '3', '4'])
  })

  it('filters rows via the toolbar input', async () => {
    const w = makeWrapper()
    const input = w.find('input[type="search"]')
    await input.setValue('eng')
    await nextTick()
    const bodyRows = w.findAll('tbody tr')
    expect(bodyRows).toHaveLength(2)
    expect(bodyRows.map(r => r.text())).toEqual(
      expect.arrayContaining([expect.stringContaining('Alice'), expect.stringContaining('Carol')]),
    )
  })

  it('shows "no match" message when filter excludes everything', async () => {
    const w = makeWrapper()
    await w.find('input[type="search"]').setValue('nonexistent')
    await nextTick()
    expect(w.text()).toContain('No results match the filter.')
  })

  it('paginates when row count exceeds page size', async () => {
    const manyRows = Array.from({ length: 60 }, (_, i) => ({ id: i, name: 'Row ' + i, dept: 'X' }))
    const w = mount(ResultsTable, {
      props: { tableKey: 'paged-' + Math.random(), columns, rows: manyRows, pageSizes: [25, 50, 100, 0] },
    })
    // Default page size is 50 → first page has 50 rows
    expect(w.findAll('tbody tr')).toHaveLength(50)
    expect(w.text()).toContain('Page 1 of 2')

    const nextBtn = w.findAll('button').find(b => b.text() === 'Next')!
    await nextBtn.trigger('click')
    expect(w.findAll('tbody tr')).toHaveLength(10)
    expect(w.text()).toContain('Page 2 of 2')
  })

  it('exposes scoped slot for per-cell rendering', () => {
    const w = mount(ResultsTable, {
      props: { tableKey: 'slot-' + Math.random(), columns, rows },
      slots: {
        'cell-name': `<template #cell-name="{ value }"><strong class="custom-cell">!{{ value }}!</strong></template>`,
      },
    })
    const customCells = w.findAll('.custom-cell')
    expect(customCells).toHaveLength(4)
    expect(customCells[0].text()).toBe('!Alice!')
  })

  it('emits row-click when rowClickable is true', async () => {
    const w = makeWrapper({ rowClickable: true })
    await w.findAll('tbody tr')[1].trigger('click')
    const events = w.emitted('row-click')
    expect(events).toBeTruthy()
    expect(events![0][0]).toEqual(rows[1])
  })

  it('emits update:selectedKeys when a row checkbox is toggled', async () => {
    const w = makeWrapper({ selectable: true, selectedKeys: new Set() })
    // Find checkbox inside first body row (skip the header select-all)
    const rowCheckboxes = w.findAll('tbody input[type="checkbox"]')
    expect(rowCheckboxes).toHaveLength(4)
    await rowCheckboxes[0].trigger('change')
    const events = w.emitted('update:selectedKeys')
    expect(events).toBeTruthy()
    const set = events![0][0] as Set<unknown>
    expect(set.has(1)).toBe(true)
  })

  it('select-all on header toggles every row on the current page', async () => {
    const w = makeWrapper({ selectable: true, selectedKeys: new Set() })
    const headerCheckbox = w.find('thead input[type="checkbox"]')
    await headerCheckbox.trigger('change')
    const events = w.emitted('update:selectedKeys')
    const set = events![0][0] as Set<unknown>
    expect(set.size).toBe(4)
    expect(set.has(1) && set.has(2) && set.has(3) && set.has(4)).toBe(true)
  })

  it('respects the rowKey prop for selection', async () => {
    const customRows = [{ uuid: 'a' }, { uuid: 'b' }]
    const customCols: ColumnDef[] = [{ key: 'uuid', label: 'UUID' }]
    const w = mount(ResultsTable, {
      props: {
        tableKey: 'rowkey-' + Math.random(),
        columns: customCols,
        rows: customRows,
        rowKey: 'uuid',
        selectable: true,
        selectedKeys: new Set(),
      },
    })
    await w.findAll('tbody input[type="checkbox"]')[0].trigger('change')
    const events = w.emitted('update:selectedKeys')
    const set = events![0][0] as Set<unknown>
    expect(set.has('a')).toBe(true)
  })

  it('persists sort across mount cycles via the same table key', async () => {
    const key = 'persist-sort-' + Math.random()
    const w1 = mount(ResultsTable, { props: { tableKey: key, columns, rows } })
    const nameHeader1 = w1.findAll('thead th')[1]
    await nameHeader1.trigger('click') // sort asc by name
    w1.unmount()

    const w2 = mount(ResultsTable, { props: { tableKey: key, columns, rows } })
    const cells = w2.findAll('tbody tr').map(r => r.findAll('td')[1].text())
    expect(cells).toEqual(['Alice', 'Bob', 'Carol', 'Dave'])
  })

  it('column picker toggles visibility', async () => {
    const w = makeWrapper()
    // Open picker
    const colsBtn = w.findAll('button').find(b => b.text().includes('Columns'))!
    await colsBtn.trigger('click')
    // Find the "Department" item and click it
    const deptItem = w.findAll('div.cursor-pointer').find(d => d.text().includes('Department'))!
    await deptItem.trigger('click')
    await nextTick()
    // Now the body rows should not contain "Sales", "Eng", "Marketing"
    const headerLabels = w.findAll('thead th').map(t => t.text())
    expect(headerLabels.some(l => l.includes('Department'))).toBe(false)
  })

  it('alwaysVisible columns cannot be hidden', async () => {
    const w = makeWrapper()
    const colsBtn = w.findAll('button').find(b => b.text().includes('Columns'))!
    await colsBtn.trigger('click')
    // The 'id' column has alwaysVisible: true, so its row should be marked
    // disabled and clicking it should be a no-op.
    const idItem = w.findAll('div.cursor-not-allowed').find(d => d.text().includes('ID'))
    expect(idItem).toBeTruthy()
  })

  // "Fit columns" measures each visible column's content and persists a width.
  // jsdom has no layout (getBoundingClientRect is 0), so the measured value
  // clamps to the floor — the point of the test is the wiring: clicking the
  // action persists a width for every visible column through the prefs store.
  it('"Fit columns" persists a width for every visible column', async () => {
    const key = 'fit-' + Math.random()
    const w = mount(ResultsTable, { props: { tableKey: key, columns, rows } })
    await w.findAll('button').find(b => b.text().includes('Fit columns'))!.trigger('click')
    await flushPromises()
    expect(Object.keys(tablePrefs[key] ? (tablePrefs[key] as { widths: object }).widths : {}))
      .toEqual(expect.arrayContaining(['id', 'name', 'dept']))
  })

  it('double-clicking a column resize handle fits only that column', async () => {
    const key = 'fit-one-' + Math.random()
    const w = mount(ResultsTable, { props: { tableKey: key, columns, rows } })
    const handle = w.find('th[data-col-key="name"] span[role="separator"]')
    await handle.trigger('dblclick')
    await flushPromises()
    expect(Object.keys((tablePrefs[key] as { widths: object }).widths)).toEqual(['name'])
  })

  it('"Reset column widths" clears persisted widths', async () => {
    const key = 'reset-' + Math.random()
    tablePrefs[key] = { widths: { id: 120, name: 200 }, hidden: [], pageSize: 50, sortKey: '', sortAsc: true, seenColumns: [] }
    const w = mount(ResultsTable, { props: { tableKey: key, columns, rows } })
    await w.findAll('button').find(b => b.text().includes('Columns'))!.trigger('click')
    await w.findAll('button').find(b => b.text().includes('Reset column widths'))!.trigger('click')
    await flushPromises()
    expect((tablePrefs[key] as { widths: object }).widths).toEqual({})
  })

  it('pins a column flagged `pinned` to the right with sticky classes; others stay relative', () => {
    const pinnedCols: ColumnDef[] = [
      { key: 'id', label: 'ID' },
      { key: 'actions', label: '', pinned: true, alwaysVisible: true },
    ]
    const w = mount(ResultsTable, {
      props: { tableKey: 'pin-' + Math.random(), columns: pinnedCols, rows: [{ id: 1 }], rowKey: 'id' },
    })
    // Pinned header: sticky to the right edge.
    const pinnedHead = w.find('th[data-col-key="actions"]')
    expect(pinnedHead.classes()).toEqual(expect.arrayContaining(['sticky', 'right-0']))
    // Non-pinned header: positioned relative (so its resize handle anchors), not sticky.
    const plainHead = w.find('th[data-col-key="id"]')
    expect(plainHead.classes()).toContain('relative')
    expect(plainHead.classes()).not.toContain('sticky')
    // Pinned body cell is sticky too (so it occludes scrolled content).
    const bodyCells = w.findAll('tbody td')
    expect(bodyCells.some(td => td.classes().includes('sticky') && td.classes().includes('right-0'))).toBe(true)
  })

  it('does not pin a column while the table fits (no horizontal overflow)', async () => {
    // Simulate a table no wider than its scroll container: scrollWidth ===
    // clientWidth, so there's nothing to scroll and pinning should disengage.
    const cw = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth')
    const sw = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollWidth')
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 1000 })
    Object.defineProperty(HTMLElement.prototype, 'scrollWidth', { configurable: true, get: () => 1000 })
    try {
      const pinnedCols: ColumnDef[] = [
        { key: 'id', label: 'ID' },
        { key: 'actions', label: '', pinned: true, alwaysVisible: true },
      ]
      const w = mount(ResultsTable, {
        props: { tableKey: 'fit-pin-' + Math.random(), columns: pinnedCols, rows: [{ id: 1 }], rowKey: 'id' },
      })
      await nextTick()
      const head = w.find('th[data-col-key="actions"]')
      // Flush to data, not detached to the container edge with a gap before it.
      expect(head.classes()).not.toContain('sticky')
      expect(head.classes()).toContain('relative')
    } finally {
      if (cw) Object.defineProperty(HTMLElement.prototype, 'clientWidth', cw)
      else Reflect.deleteProperty(HTMLElement.prototype, 'clientWidth')
      if (sw) Object.defineProperty(HTMLElement.prototype, 'scrollWidth', sw)
      else Reflect.deleteProperty(HTMLElement.prototype, 'scrollWidth')
    }
  })

  it('renders a trailing filler cell so real columns keep their exact widths', () => {
    const w = makeWrapper()
    // The filler is the last header cell: aria-hidden and not a real column.
    const headerCells = w.findAll('thead th')
    const fillerHead = headerCells[headerCells.length - 1]
    expect(fillerHead.attributes('aria-hidden')).toBe('true')
    expect(fillerHead.attributes('data-col-key')).toBeUndefined()
    // And a matching filler cell trails each body row.
    const firstRowCells = w.findAll('tbody tr')[0].findAll('td')
    expect(firstRowCells[firstRowCells.length - 1].attributes('aria-hidden')).toBe('true')
  })

  it('scrolls a newly revealed column into view', async () => {
    const scrollSpy = vi.fn()
    const orig = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = scrollSpy
    try {
      const key = 'reveal-' + Math.random()
      tablePrefs[key] = {
        widths: {}, hidden: ['dept'], pageSize: 50, sortKey: '', sortAsc: true,
        seenColumns: ['id', 'name', 'dept'],
      }
      const w = mount(ResultsTable, { props: { tableKey: key, columns, rows } })
      await w.findAll('button').find(b => b.text().includes('Columns'))!.trigger('click')
      const deptRow = w.findAll('div.cursor-pointer').find(d => d.text().includes('Department'))!
      await deptRow.trigger('click')
      await flushPromises()
      expect(scrollSpy).toHaveBeenCalled()
    } finally {
      Element.prototype.scrollIntoView = orig
    }
  })
})
