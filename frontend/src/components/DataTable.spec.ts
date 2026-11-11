// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DataTable from './DataTable.vue'
import type { DataTableColumn } from './DataTable.vue'

interface Row { id: number; name: string | null; last: string | null }

const rows: Row[] = [
  { id: 1, name: 'bravo',   last: '2026-01-02T00:00:00Z' },
  { id: 2, name: 'alpha',   last: null },
  { id: 3, name: 'charlie', last: '2025-12-31T00:00:00Z' },
]

function mountTable(columns: DataTableColumn[]) {
  return mount(DataTable, { props: { columns, rows, rowKey: 'id' } })
}

const firstColumn = (w: ReturnType<typeof mountTable>) =>
  w.findAll('tbody tr').map(tr => tr.findAll('td')[0].text())

describe('DataTable sorting', () => {
  it('leaves rows in the given order and headers as plain text when no column is sortable', () => {
    const w = mountTable([{ key: 'name', label: 'Name' }])
    expect(w.find('thead button').exists()).toBe(false)
    expect(w.find('th').attributes('aria-sort')).toBeUndefined()
    expect(firstColumn(w)).toEqual(['bravo', 'alpha', 'charlie'])
  })

  it('sorts ascending on first click and descending on second, updating aria-sort', async () => {
    const w = mountTable([{ key: 'name', label: 'Name', sortable: true }])
    const th = w.find('th')
    expect(th.attributes('aria-sort')).toBe('none')

    await th.find('button').trigger('click')
    expect(firstColumn(w)).toEqual(['alpha', 'bravo', 'charlie'])
    expect(th.attributes('aria-sort')).toBe('ascending')

    await th.find('button').trigger('click')
    expect(firstColumn(w)).toEqual(['charlie', 'bravo', 'alpha'])
    expect(th.attributes('aria-sort')).toBe('descending')
  })

  it('sorts nulls last in both directions', async () => {
    const w = mountTable([
      { key: 'last', label: 'Last', sortable: true },
      { key: 'name', label: 'Name' },
    ])
    const names = () => w.findAll('tbody tr').map(tr => tr.findAll('td')[1].text())
    const btn = w.find('th button')

    await btn.trigger('click')
    expect(names()).toEqual(['charlie', 'bravo', 'alpha'])
    await btn.trigger('click')
    expect(names()).toEqual(['bravo', 'charlie', 'alpha'])
  })

  it('uses sortValue when given instead of row[key]', async () => {
    const w = mountTable([
      { key: 'name', label: 'Name', sortable: true, sortValue: (r: Row) => -r.id },
    ])
    await w.find('th button').trigger('click')
    expect(firstColumn(w)).toEqual(['charlie', 'alpha', 'bravo'])
  })
})
