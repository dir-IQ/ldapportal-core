// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/api/csvTemplates', () => ({
  previewBulkDelete: vi.fn(),
  bulkDelete: vi.fn(),
}))

import { previewBulkDelete, bulkDelete } from '@/api/csvTemplates'
import BulkDeleteSection from './BulkDeleteSection.vue'

const stubs = { DnPicker: true }

function btnByText(w: ReturnType<typeof mount>, text: string) {
  return w.findAll('button').find(b => b.text().includes(text))!
}

async function attachFile(w: ReturnType<typeof mount>) {
  const input = w.find('input[type="file"]')
  const file = new File(['dn\n"uid=a,ou=p,dc=x"\n'], 'd.csv', { type: 'text/csv' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}

describe('BulkDeleteSection', () => {
  beforeEach(() => vi.clearAllMocks())

  it('previews and renders disposition badges with a will-delete count', async () => {
    vi.mocked(previewBulkDelete).mockResolvedValue({ data: { totalRows: 2, rows: [
      { rowNumber: 1, dn: 'uid=a,ou=p,dc=x', disposition: 'WILL_DELETE' },
      { rowNumber: 2, dn: 'uid=b,ou=p,dc=x', disposition: 'NOT_FOUND', note: 'No entry at this DN' },
    ] } } as never)

    const w = mount(BulkDeleteSection, { props: { dirId: 'd1' }, global: { stubs } })
    await attachFile(w)
    await btnByText(w, 'Preview').trigger('click')
    await flushPromises()

    expect(previewBulkDelete).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ keyAttribute: null, baseDn: null, skipHeaderRow: true }))
    expect(w.find('.badge-green').text()).toContain('1 will delete')
    expect(w.find('.badge-gray').exists()).toBe(true)
  })

  it('arms the delete button only after typing DELETE', async () => {
    vi.mocked(previewBulkDelete).mockResolvedValue({ data: { totalRows: 1, rows: [
      { rowNumber: 1, dn: 'uid=a,ou=p,dc=x', disposition: 'WILL_DELETE' },
    ] } } as never)
    vi.mocked(bulkDelete).mockResolvedValue({ data: { totalRows: 1, deleted: 1, skipped: 0, errors: 0,
      rows: [{ rowNumber: 1, dn: 'uid=a,ou=p,dc=x', status: 'DELETED' }] } } as never)

    const w = mount(BulkDeleteSection, { props: { dirId: 'd1' }, global: { stubs } })
    await attachFile(w)
    await btnByText(w, 'Preview').trigger('click')
    await flushPromises()

    const deleteBtn = btnByText(w, 'Delete')
    expect(deleteBtn.attributes('disabled')).toBeDefined()

    await w.find('input[aria-label="Type DELETE to confirm"]').setValue('DELETE')
    expect(btnByText(w, 'Delete').attributes('disabled')).toBeUndefined()

    await btnByText(w, 'Delete').trigger('click')
    await flushPromises()
    expect(bulkDelete).toHaveBeenCalledOnce()
  })

  it('disables Preview in key mode until a base DN is set', async () => {
    const w = mount(BulkDeleteSection, { props: { dirId: 'd1' }, global: { stubs } })
    await w.find('#bd-mode').setValue('key')
    await attachFile(w)
    // keyAttribute defaults to uid, but baseDn (from the stubbed DnPicker) is
    // empty, so Preview stays disabled.
    expect(btnByText(w, 'Preview').attributes('disabled')).toBeDefined()
  })
})
