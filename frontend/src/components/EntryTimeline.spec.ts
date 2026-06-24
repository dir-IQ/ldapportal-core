// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('@/api/audit', () => ({ getEntryTimeline: vi.fn() }))
import { getEntryTimeline } from '@/api/audit'
import EntryTimeline from './EntryTimeline.vue'

const stubs = { RelativeTime: true }
interface Evt { id: string; action: string; occurredAt: string; detail?: Record<string, unknown> }

function mountWithData(data: Record<string, unknown>) {
  vi.mocked(getEntryTimeline).mockResolvedValue({ data } as never)
  return mount(EntryTimeline, {
    props: { directoryId: 'd1', targetDn: 'uid=jdoe,ou=people,dc=x' },
    global: { stubs },
  })
}
const mountWith = (events: Evt[]) => mountWithData({ content: events, last: true })
const ev = (id: string, action: string, detail?: Record<string, unknown>): Evt =>
  ({ id, action, occurredAt: '2026-06-01T00:00:00Z', detail })

describe('EntryTimeline action labels', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders the specific IVIA op label from detail.ivia_op instead of the generic action', async () => {
    const w = mountWith([
      ev('1', 'USER_UPDATE', { ivia_op: 'grant' }),
      ev('2', 'USER_DELETE', { ivia_op: 'revoke_hard' }),
      ev('3', 'USER_DISABLE', { ivia_op: 'suspend' }),
    ])
    await flushPromises()
    const text = w.text()
    expect(text).toContain('IVIA account granted')
    expect(text).toContain('IVIA account revoked (hard)')
    expect(text).toContain('IVIA account suspended')
    // The generic fallbacks must NOT appear — the discriminator wins.
    expect(text).not.toContain('Updated')
    expect(text).not.toContain('Deleted')
  })

  it('falls back to the generic label when there is no ivia_op discriminator', async () => {
    const w = mountWith([
      ev('4', 'USER_UPDATE', { modifiedAttributes: ['mail'] }),
      ev('5', 'GROUP_MEMBER_ADD', { member: 'uid=jdoe,ou=people,dc=x' }),
    ])
    await flushPromises()
    const text = w.text()
    expect(text).toContain('Updated')
    expect(text).toContain('Member added')
    expect(text).not.toContain('IVIA')
  })
})

describe('EntryTimeline load-more visibility', () => {
  beforeEach(() => vi.clearAllMocks())

  // PAGE_SIZE in the component is 20.
  const fullPage = Array.from({ length: 20 }, (_, i) => ev(String(i), 'USER_UPDATE'))

  it('hides Load more when fewer than a full page is returned and there is no metadata', async () => {
    const w = mountWithData({ content: [ev('a', 'USER_UPDATE')] })
    await flushPromises()
    expect(w.text()).not.toContain('Load more')
  })

  it('hides Load more on the last page even when the response omits a `last` flag', async () => {
    const w = mountWithData({ content: fullPage, totalPages: 1 })
    await flushPromises()
    expect(w.text()).not.toContain('Load more')
  })

  it('shows Load more when more pages remain (totalPages metadata)', async () => {
    const w = mountWithData({ content: fullPage, totalPages: 3 })
    await flushPromises()
    expect(w.text()).toContain('Load more')
  })

  it('shows Load more as a fallback when a full page returns with no metadata', async () => {
    const w = mountWithData({ content: fullPage })
    await flushPromises()
    expect(w.text()).toContain('Load more')
  })

  it('shows the approval/bulk-update coverage note', async () => {
    const w = mountWithData({ content: [], totalPages: 0 })
    await flushPromises()
    expect(w.text()).toContain("aren't shown in this timeline")
  })
})
