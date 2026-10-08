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

const activeProfile = { id: 'p1', name: 'Engineers', targetUserDn: 'ou=eng,dc=x', themeColor: '#b91c1c' }

function mountSection() {
  return mount(BulkDeleteSection, { props: { dirId: 'd1', activeProfile } })
}
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

  it('shows the Active-profile field and previews with disposition badges', async () => {
    vi.mocked(previewBulkDelete).mockResolvedValue({ data: { totalRows: 2, rows: [
      { rowNumber: 1, dn: 'uid=a,ou=p,dc=x', disposition: 'WILL_DELETE' },
      { rowNumber: 2, dn: 'uid=b,ou=p,dc=x', disposition: 'NOT_FOUND', note: 'No entry at this DN' },
    ] } } as never)

    const w = mountSection()
    expect(w.text()).toContain('Active profile')
    expect(w.text()).toContain('Engineers')
    await attachFile(w)
    await btnByText(w, 'Preview').trigger('click')
    await flushPromises()

    expect(previewBulkDelete).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ keyAttribute: null, baseDn: null, skipHeaderRow: true }))
    expect(w.find('.badge-green').text()).toContain('1 will delete')
    expect(w.find('.badge-gray').exists()).toBe(true)
  })

  it('arms Delete only after typing the profile name', async () => {
    vi.mocked(previewBulkDelete).mockResolvedValue({ data: { totalRows: 1, rows: [
      { rowNumber: 1, dn: 'uid=a,ou=p,dc=x', disposition: 'WILL_DELETE' },
    ] } } as never)
    vi.mocked(bulkDelete).mockResolvedValue({ data: { totalRows: 1, deleted: 1, skipped: 0, errors: 0,
      rows: [{ rowNumber: 1, dn: 'uid=a,ou=p,dc=x', status: 'DELETED' }] } } as never)

    const w = mountSection()
    await attachFile(w)
    await btnByText(w, 'Preview').trigger('click')
    await flushPromises()

    expect(btnByText(w, 'Delete').attributes('disabled')).toBeDefined()
    await w.find('input[aria-label="Type the profile name to confirm"]').setValue('DELETE') // wrong
    expect(btnByText(w, 'Delete').attributes('disabled')).toBeDefined()
    await w.find('input[aria-label="Type the profile name to confirm"]').setValue('engineers') // ci match
    expect(btnByText(w, 'Delete').attributes('disabled')).toBeUndefined()

    await btnByText(w, 'Delete').trigger('click')
    await flushPromises()
    expect(bulkDelete).toHaveBeenCalledOnce()
  })

  it('disables Preview until a CSV file is chosen', async () => {
    const w = mountSection()
    expect(btnByText(w, 'Preview').attributes('disabled')).toBeDefined()
    await attachFile(w)
    expect(btnByText(w, 'Preview').attributes('disabled')).toBeUndefined()
  })

  it('scopes key-attribute deletes to the active profile target OU', async () => {
    vi.mocked(previewBulkDelete).mockResolvedValue({ data: { totalRows: 0, rows: [] } } as never)
    const w = mountSection()
    await w.find('#bd-mode').setValue('key')
    await attachFile(w)
    await btnByText(w, 'Preview').trigger('click')
    await flushPromises()

    expect(previewBulkDelete).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ keyAttribute: 'uid', baseDn: 'ou=eng,dc=x' }))
  })

  it('fills the CSV column with a real value that follows the mode until edited', async () => {
    const w = mountSection()
    const col = () => (w.find('#bd-valuecol').element as HTMLInputElement).value
    expect(col()).toBe('dn')

    await w.find('#bd-mode').setValue('key')
    expect(col()).toBe('uid')
    await w.find('#bd-keyattr').setValue('employeeNumber')
    expect(col()).toBe('employeeNumber')

    // Once the operator types their own column, mode/attribute changes leave it alone.
    await w.find('#bd-valuecol').setValue('emp_no')
    await w.find('#bd-keyattr').setValue('uid')
    expect(col()).toBe('emp_no')
  })

  it('requires the CSV column: blank blocks Preview with a message', async () => {
    const w = mountSection()
    await attachFile(w)
    expect(w.find('label[for="bd-valuecol"]').text()).toBe('CSV column *')
    expect(w.find('#bd-valuecol').attributes('required')).toBeDefined()

    // Regression: a blank column used to preview anyway (backend silently used "dn").
    await w.find('#bd-valuecol').setValue('')
    expect(btnByText(w, 'Preview').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain("Enter the CSV column that holds each user's DN.")

    await w.find('#bd-valuecol').setValue('dn')
    expect(btnByText(w, 'Preview').attributes('disabled')).toBeUndefined()
  })

  it('sends the shown CSV column with the preview', async () => {
    vi.mocked(previewBulkDelete).mockResolvedValue({ data: { totalRows: 0, rows: [] } } as never)
    const w = mountSection()
    await attachFile(w)
    await btnByText(w, 'Preview').trigger('click')
    await flushPromises()
    expect(previewBulkDelete).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ valueColumn: 'dn' }))
  })

  it('puts the CSV file picker on the same row as the CSV column', async () => {
    const w = mountSection()
    const row = w.find('#bd-valuecol').element.closest('.grid')!
    expect(row.querySelector('input[type="file"][aria-label="CSV File"]')).not.toBeNull()
    expect(row.querySelector('#bd-skip-header')).not.toBeNull()
  })
})
