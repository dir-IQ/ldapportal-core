// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('@/api/audit', () => ({ getEntryTimeline: vi.fn() }))
import { getEntryTimeline } from '@/api/audit'
import EntryTimeline from './EntryTimeline.vue'

const stubs = { RelativeTime: true }

interface Evt { id: string; action: string; occurredAt: string; detail?: Record<string, unknown> }

function mountWith(events: Evt[]) {
  vi.mocked(getEntryTimeline).mockResolvedValue({ data: { content: events, last: true } } as never)
  return mount(EntryTimeline, {
    props: { directoryId: 'd1', targetDn: 'uid=jdoe,ou=people,dc=x' },
    global: { stubs },
  })
}

describe('EntryTimeline action labels', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders the specific IVIA op label from detail.ivia_op instead of the generic action', async () => {
    const w = mountWith([
      { id: '1', action: 'USER_UPDATE', occurredAt: '2026-06-01T00:00:00Z', detail: { ivia_op: 'grant' } },
      { id: '2', action: 'USER_DELETE', occurredAt: '2026-06-01T00:00:00Z', detail: { ivia_op: 'revoke_hard' } },
      { id: '3', action: 'USER_DISABLE', occurredAt: '2026-06-01T00:00:00Z', detail: { ivia_op: 'suspend' } },
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
      { id: '4', action: 'USER_UPDATE', occurredAt: '2026-06-01T00:00:00Z', detail: { modifiedAttributes: ['mail'] } },
      { id: '5', action: 'GROUP_MEMBER_ADD', occurredAt: '2026-06-01T00:00:00Z', detail: { member: 'uid=jdoe,ou=people,dc=x' } },
    ])
    await flushPromises()
    const text = w.text()
    expect(text).toContain('Updated')
    expect(text).toContain('Member added')
    expect(text).not.toContain('IVIA')
  })
})
