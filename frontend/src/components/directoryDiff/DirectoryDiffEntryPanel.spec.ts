// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  getDirectoryDiffEntry: vi.fn(),
  recheckDirectoryDiffEntry: vi.fn(),
}))
vi.mock('@/api/directoryDiff', () => api)

import DirectoryDiffEntryPanel from './DirectoryDiffEntryPanel.vue'
import { vDialogA11y } from '@/directives/dialogA11y'
import type { DirectoryDiffEntry } from '@/api/directoryDiff'

const DIFFERENT: DirectoryDiffEntry = {
  seq: 4, category: 'DIFFERENT', dnA: 'uid=bob,ou=people,dc=a', dnB: 'uid=bob,ou=Users,dc=b', attributes: ['cn'],
  note: null,
}

let wrapper: VueWrapper | null = null

beforeEach(() => {
  vi.clearAllMocks()
  api.getDirectoryDiffEntry.mockResolvedValue({ data: {
    seq: 4, category: 'DIFFERENT', dnA: DIFFERENT.dnA, dnB: DIFFERENT.dnB, note: null,
    diffs: [{ name: 'cn', onlyA: ['Bob'], onlyACount: 1, onlyB: ['Robert'], onlyBCount: 1, both: [], bothCount: 0 },
      { name: 'mail', onlyA: ['a1', 'a2'], onlyACount: 150, onlyB: [], onlyBCount: 0, both: ['x'], bothCount: 1 }],
    sameAttributes: ['sn', 'uid'],
  } })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function mountPanel(entry: DirectoryDiffEntry = DIFFERENT) {
  wrapper = mount(DirectoryDiffEntryPanel, {
    props: {
      runId: 'run-1', entry, dirA: 'dir-a', dirB: 'dir-b', foundAt: '2026-10-31T14:05:00Z',
      position: '1 of 2', hasPrevious: false, hasNext: true,
    },
    global: {
      stubs: { RouterLink: { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' } },
      directives: { 'dialog-a11y': vDialogA11y },
    },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

const body = () => document.body

describe('DirectoryDiffEntryPanel', () => {
  it('shows each differing attribute, split by side, with capped values counted', async () => {
    await mountPanel()
    expect(api.getDirectoryDiffEntry).toHaveBeenCalledWith('run-1', 4)
    const table = body().querySelector('[data-testid="diff-table"]')!
    expect(table.textContent).toContain('only in A: Bob')
    expect(table.textContent).toContain('only in B: Robert')
    expect(table.textContent).toContain('…and 148 more')
    // mail is absent on B apart from the shared value
    expect(table.querySelectorAll('tbody tr')[1].textContent).toContain('x')
  })

  it('lists matching attributes by name until re-read', async () => {
    await mountPanel()
    const toggle = Array.from(body().querySelectorAll('button')).find(b => b.textContent?.includes('matching'))!
    expect(toggle.textContent).toContain('Show 2 matching attributes')
    toggle.click()
    await flushPromises()
    expect(body().querySelector('[data-testid="same-table"]')!.textContent).toContain('Re-read live to see the values')
  })

  it('re-reads both entries and says what changed', async () => {
    api.recheckDirectoryDiffEntry.mockResolvedValue({ data: {
      category: 'SAME', dnA: DIFFERENT.dnA, dnB: DIFFERENT.dnB, presentInA: true, presentInB: true,
      readAt: new Date().toISOString(), diffs: [], same: [{ name: 'cn', values: ['Bob'] }],
    } })
    await mountPanel()
    Array.from(body().querySelectorAll('button')).find(b => b.textContent?.trim() === 'Re-read live')!.click()
    await flushPromises()
    expect(api.recheckDirectoryDiffEntry).toHaveBeenCalledWith('run-1', 4)
    expect(body().querySelector('[data-testid="live-banner"]')!.textContent).toContain('now the same')
    expect(body().querySelector('[data-testid="diff-table"]')).toBeNull()
  })

  it('links each side to the directory browser', async () => {
    await mountPanel()
    const links = Array.from(body().querySelectorAll('a')).map(a => a.getAttribute('data-to'))
    expect(links).toContain(JSON.stringify({ path: '/superadmin/directory-browser',
      query: { dir: 'dir-b', dn: DIFFERENT.dnB } }))
  })

  it('does not load stored detail or offer a re-read for an unpaired entry', async () => {
    await mountPanel({ ...DIFFERENT, category: 'ONLY_A', dnB: null, note: 'Not under side A’s base DN' })
    expect(api.getDirectoryDiffEntry).not.toHaveBeenCalled()
    expect(body().textContent).toContain('Not under side A’s base DN')
    expect(Array.from(body().querySelectorAll('button')).some(b => b.textContent?.includes('Re-read'))).toBe(false)
  })

  it('closes on Escape and moves on with Next', async () => {
    const w = await mountPanel()
    body().querySelector('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    Array.from(body().querySelectorAll('button')).find(b => b.textContent?.includes('Next'))!.click()
    expect(w.emitted('close')).toBeTruthy()
    expect(w.emitted('next')).toBeTruthy()
  })

  it('gives focus back to what opened it when it closes', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const w = await mountPanel()
    w.unmount()
    wrapper = null
    expect(document.activeElement).toBe(opener)
    opener.remove()
  })
})
