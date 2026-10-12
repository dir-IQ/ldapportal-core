// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const notif = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }))
vi.mock('@/stores/notifications', () => ({ useNotificationStore: () => notif }))
vi.mock('@/composables/useApi', () => ({ downloadBlob: vi.fn() }))
vi.mock('@/api/bulkMemberships', () => ({
  previewBulkMemberships: vi.fn(),
  applyBulkMemberships: vi.fn(),
}))

import { previewBulkMemberships, applyBulkMemberships } from '@/api/bulkMemberships'
import { downloadBlob } from '@/composables/useApi'
import BulkMembershipSection from './BulkMembershipSection.vue'

const BOB = 'uid=bob,ou=users,dc=x'
const DEVS = 'cn=devs,ou=groups,dc=x'

function mountSection() {
  return mount(BulkMembershipSection, {
    props: { dirId: 'd1' },
    global: { stubs: { ExpandablePreview: { template: '<div><slot /></div>' } } },
  })
}
function btnByText(w: ReturnType<typeof mount>, text: string) {
  return w.findAll('button').find(b => b.text().includes(text))
}
async function attachFile(w: ReturnType<typeof mount>) {
  const input = w.find('input[type="file"]')
  const file = new File(['user,groups\nbob,devs\n'], 'm.csv', { type: 'text/csv' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}

const PREVIEW = {
  totalPairs: 4, actionable: 2, rows: [
    { rowNumber: 1, user: 'bob', userDn: BOB, group: 'devs', groupDn: DEVS, op: 'ADD', disposition: 'WILL_ADD', note: null },
    { rowNumber: 1, user: 'bob', userDn: BOB, group: 'ops', groupDn: 'cn=ops,dc=x', op: 'REMOVE', disposition: 'WILL_REMOVE', note: null },
    { rowNumber: 2, user: 'amy', userDn: 'uid=amy,dc=x', group: 'devs', groupDn: DEVS, op: 'ADD', disposition: 'ALREADY_MEMBER', note: 'Already a member' },
    { rowNumber: 3, user: 'zed', userDn: null, group: 'devs', groupDn: null, op: 'ADD', disposition: 'USER_NOT_FOUND', note: 'No user with uid=zed' },
  ],
}

describe('BulkMembershipSection', () => {
  beforeEach(() => vi.clearAllMocks())

  it('enables Preview only once a file is chosen', async () => {
    const w = mountSection()
    expect(btnByText(w, 'Preview')!.attributes('disabled')).toBeDefined()
    await attachFile(w)
    expect(btnByText(w, 'Preview')!.attributes('disabled')).toBeUndefined()
  })

  it('previews and summarises every pair, then applies the actionable ones', async () => {
    vi.mocked(previewBulkMemberships).mockResolvedValue({ data: PREVIEW } as never)
    vi.mocked(applyBulkMemberships).mockResolvedValue({ data: {
      totalPairs: 4, applied: 1, queued: 1, skipped: 1, failed: 1, rows: [
        { rowNumber: 3, user: 'zed', userDn: null, group: 'devs', groupDn: null, op: 'ADD', status: 'ERROR', approvalId: null, message: 'No user with uid=zed' },
      ],
    } } as never)

    const w = mountSection()
    await attachFile(w)
    await btnByText(w, 'Preview')!.trigger('click')
    await flushPromises()

    expect(previewBulkMemberships).toHaveBeenCalledWith('d1', expect.any(File))
    const preview = w.find('[data-testid="membership-preview"]')
    expect(preview.text()).toContain('4 user-group pairs')
    expect(preview.find('.badge-green').text()).toContain('1 to add')
    expect(preview.find('.badge-blue').text()).toContain('1 to remove')
    expect(preview.text()).toContain('1 no change')
    expect(preview.text()).toContain('1 need attention')
    expect(preview.text()).toContain('No user with uid=zed')

    await btnByText(w, 'Apply 2 changes')!.trigger('click')
    await flushPromises()

    expect(applyBulkMemberships).toHaveBeenCalledWith('d1', expect.any(File))
    expect(w.find('[data-testid="membership-preview"]').exists()).toBe(false)
    const result = w.find('[data-testid="membership-result"]')
    expect(result.text()).toContain('Queued for approval')
    expect(result.text()).toContain('Row 3 (zed → devs): No user with uid=zed')
    expect(notif.error).toHaveBeenCalledWith('Memberships: 1 applied, 1 queued, 1 failed')
  })

  it('offers no Apply when nothing would change', async () => {
    vi.mocked(previewBulkMemberships).mockResolvedValue({ data: {
      totalPairs: 1, actionable: 0, rows: [PREVIEW.rows[2]] } } as never)
    const w = mountSection()
    await attachFile(w)
    await btnByText(w, 'Preview')!.trigger('click')
    await flushPromises()

    expect(btnByText(w, 'Apply')).toBeUndefined()
    expect(w.text()).toContain('Nothing to change')
  })

  it('shows the server error for a malformed file', async () => {
    vi.mocked(previewBulkMemberships).mockRejectedValue({ response: { data: { detail: "The file needs a 'user' column" } } })
    const w = mountSection()
    await attachFile(w)
    await btnByText(w, 'Preview')!.trigger('click')
    await flushPromises()

    expect(notif.error).toHaveBeenCalledWith("The file needs a 'user' column")
  })

  it('downloads the template and the preview as CSV', async () => {
    vi.mocked(previewBulkMemberships).mockResolvedValue({ data: PREVIEW } as never)
    const w = mountSection()
    await btnByText(w, 'Download template CSV')!.trigger('click')
    expect(downloadBlob).toHaveBeenCalledWith(expect.any(Blob), 'group-memberships-template.csv')

    await attachFile(w)
    await btnByText(w, 'Preview')!.trigger('click')
    await flushPromises()
    await btnByText(w, 'Download preview CSV')!.trigger('click')

    const blob = vi.mocked(downloadBlob).mock.calls[1][0] as Blob
    const text = await blob.text()
    expect(text.split('\n')[0]).toBe('row,user,userDn,group,groupDn,op,outcome,note')
    expect(text).toContain(`1,bob,"${BOB}",devs,"${DEVS}",ADD,Will add,`)
  })
})
