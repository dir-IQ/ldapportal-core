// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

const notif = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/stores/notifications', () => ({ useNotificationStore: () => notif }))
const downloadBlob = vi.hoisted(() => vi.fn())
vi.mock('@/composables/useApi', () => ({ downloadBlob }))
const api = vi.hoisted(() => ({
  listDirectoryDiffEntries: vi.fn(),
  downloadDirectoryDiffCsv: vi.fn(),
  getDirectoryDiffEntry: vi.fn(),
  recheckDirectoryDiffEntry: vi.fn(),
}))
vi.mock('@/api/directoryDiff', () => api)

import DirectoryDiffResults from './DirectoryDiffResults.vue'
import DirectoryDiffEntryPanel from './DirectoryDiffEntryPanel.vue'
import { diffRun } from './fixtures'
import type { DirectoryDiffEntry } from '@/api/directoryDiff'

function entry(seq: number, overrides: Partial<DirectoryDiffEntry> = {}): DirectoryDiffEntry {
  return {
    seq, category: 'DIFFERENT', dnA: `uid=u${seq},ou=people,dc=a`, dnB: `uid=u${seq},ou=Users,dc=b`,
    attributes: ['cn'], note: null, ...overrides,
  }
}

function page(content: DirectoryDiffEntry[], number = 0, totalElements = content.length, totalPages = 1) {
  return { data: { content, totalElements, totalPages, number, size: 50 } }
}

let wrapper: VueWrapper | null = null

beforeEach(() => {
  vi.clearAllMocks()
  api.listDirectoryDiffEntries.mockResolvedValue(page([entry(1), entry(2)]))
  api.getDirectoryDiffEntry.mockResolvedValue({ data: { seq: 1, category: 'DIFFERENT', dnA: 'a', dnB: 'b',
    diffs: [], sameAttributes: [], note: null } })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function mountResults(run = diffRun()) {
  wrapper = mount(DirectoryDiffResults, {
    props: { run },
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

describe('DirectoryDiffResults', () => {
  it('opens on Different and shows the counts of every group', async () => {
    const w = await mountResults()
    expect(api.listDirectoryDiffEntries).toHaveBeenCalledWith('run-1',
      { category: 'DIFFERENT', attribute: null, q: null, page: 0, size: 50 })
    expect(w.find('[data-testid="group-DIFFERENT"]').attributes('aria-pressed')).toBe('true')
    expect(w.find('[data-testid="group-SAME"]').text()).toContain('5')
    expect(w.find('[data-testid="group-ONLY_B"]').text()).toContain('2')
    expect(w.findAll('[data-testid="results-table"] tbody tr')).toHaveLength(2)
  })

  it('opens on the first group with entries when nothing differs', async () => {
    await mountResults(diffRun({ counts: { same: 3, different: 0, onlyA: 0, onlyB: 1 }, attributeCounts: [] }))
    expect(api.listDirectoryDiffEntries).toHaveBeenCalledWith('run-1', expect.objectContaining({ category: 'ONLY_B' }))
  })

  it('filters Different by an attribute, and back', async () => {
    const w = await mountResults()
    const chip = w.find('[data-testid="attribute-filter"]').findAll('button').find(b => b.text().startsWith('mail'))!
    await chip.trigger('click')
    await flushPromises()
    expect(api.listDirectoryDiffEntries).toHaveBeenLastCalledWith('run-1', expect.objectContaining({ attribute: 'mail' }))
    const showAll = w.find('[data-testid="attribute-filter"]').findAll('button').find(b => b.text() === 'Show all')!
    await showAll.trigger('click')
    await flushPromises()
    expect(api.listDirectoryDiffEntries).toHaveBeenLastCalledWith('run-1', expect.objectContaining({ attribute: null }))
  })

  it('shows why an unpaired entry is on one side', async () => {
    api.listDirectoryDiffEntries.mockResolvedValue(page([
      entry(9, { category: 'ONLY_A', dnB: null, attributes: [], note: 'No employeeNumber value to pair by' })]))
    const w = await mountResults()
    await w.find('[data-testid="group-ONLY_A"]').trigger('click')
    await flushPromises()
    expect(api.listDirectoryDiffEntries).toHaveBeenLastCalledWith('run-1', expect.objectContaining({ category: 'ONLY_A' }))
    expect(w.find('[data-testid="results-table"]').text()).toContain('No employeeNumber value to pair by')
  })

  it('searches by DN after a pause', async () => {
    vi.useFakeTimers()
    try {
      const w = await mountResults()
      await w.find('input[type="search"]').setValue('alice')
      vi.advanceTimersByTime(300)
      await flushPromises()
      expect(api.listDirectoryDiffEntries).toHaveBeenLastCalledWith('run-1', expect.objectContaining({ q: 'alice' }))
    } finally {
      vi.useRealTimers()
    }
  })

  it('steps through entries across pages in the panel', async () => {
    api.listDirectoryDiffEntries
      .mockResolvedValueOnce(page([entry(1), entry(2)], 0, 3, 2))
      .mockResolvedValueOnce(page([entry(3)], 1, 3, 2))
    const w = await mountResults()
    await w.findAll('[data-testid="results-table"] tbody tr')[1].find('button').trigger('click')
    await flushPromises()
    const panel = () => w.findComponent(DirectoryDiffEntryPanel)
    expect(panel().props('entry')).toMatchObject({ seq: 2 })
    expect(panel().props('position')).toBe('2 of 3')
    panel().vm.$emit('next')
    await flushPromises()
    expect(api.listDirectoryDiffEntries).toHaveBeenLastCalledWith('run-1', expect.objectContaining({ page: 1 }))
    expect(panel().props('entry')).toMatchObject({ seq: 3 })
    expect(panel().props('hasNext')).toBe(false)
  })

  it('exports the current group and everything as CSV', async () => {
    api.downloadDirectoryDiffCsv.mockResolvedValue({ data: new Blob(['x']) })
    const w = await mountResults()
    const buttons = w.findAll('button')
    await buttons.find(b => b.text() === 'Export Different (CSV)')!.trigger('click')
    await flushPromises()
    expect(api.downloadDirectoryDiffCsv).toHaveBeenLastCalledWith('run-1', 'DIFFERENT')
    expect(downloadBlob).toHaveBeenLastCalledWith(expect.any(Blob), 'directory-diff-run-1-different.csv')
    await buttons.find(b => b.text() === 'Export all (CSV)')!.trigger('click')
    await flushPromises()
    expect(api.downloadDirectoryDiffCsv).toHaveBeenLastCalledWith('run-1', undefined)
  })

  it('shows the server’s message when an export fails', async () => {
    const body = new Blob([JSON.stringify({ detail: 'Directory comparison not found: run-1' })])
    api.downloadDirectoryDiffCsv.mockRejectedValue({ response: { data: body }, message: 'Request failed' })
    const w = await mountResults()
    await w.findAll('button').find(b => b.text() === 'Export all (CSV)')!.trigger('click')
    await flushPromises()
    expect(notif.error).toHaveBeenCalledWith('Directory comparison not found: run-1')
  })
})
