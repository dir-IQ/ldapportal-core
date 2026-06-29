// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { dirId: 'd1' } }) }))
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasFeature: () => false, isSuperadmin: false }),
}))
vi.mock('@/composables/useApi', () => ({ downloadBlob: vi.fn() }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => () => Promise.resolve(true) }))
vi.mock('@/api/schema', () => ({
  listObjectClasses: vi.fn(() => Promise.resolve({ data: [] })),
  getObjectClassesBulk: vi.fn(() => Promise.resolve({ data: {} })),
}))
vi.mock('@/api/profiles', () => ({
  listProfiles: vi.fn(() => Promise.resolve({ data: [
    { id: 'p1', name: 'Engineers', targetUserDn: 'ou=eng,dc=x', objectClassNames: ['inetOrgPerson'], rdnAttribute: 'uid' },
  ] })),
}))
vi.mock('@/api/csvTemplates', () => ({
  listCsvTemplates: vi.fn(() => Promise.resolve({ data: [
    { id: 't1', name: 'Staff', targetKeyAttribute: 'uid', conflictHandling: 'SKIP',
      objectClass: 'inetOrgPerson', skipHeaderRow: true, dnSourceColumn: '', entries: [] },
  ] })),
  createCsvTemplate: vi.fn(), updateCsvTemplate: vi.fn(), deleteCsvTemplate: vi.fn(),
  previewCsv: vi.fn(() => Promise.resolve({ data: {
    totalRows: 1, rows: [{ rowNumber: 1, computedDn: 'uid=a,ou=eng,dc=x', attributes: {} }] } })),
  importCsv: vi.fn(() => Promise.resolve({ status: 200, data: {
    totalRows: 1, created: 1, updated: 0, skipped: 0, errors: 0, rows: [] } })),
  exportCsv: vi.fn(),
  previewGroupCsv: vi.fn(), importGroupCsv: vi.fn(), exportGroupCsv: vi.fn(),
  checkContainerExists: vi.fn(() => Promise.resolve({ data: { exists: true } })),
  createContainer: vi.fn(),
}))

import { previewCsv, importCsv } from '@/api/csvTemplates'
import BulkView from './BulkView.vue'

const global = {
  stubs: {
    PageContainer: { template: '<div><slot/></div>' },
    DnPicker: true, AppModal: true, FormField: true, ConfirmDialog: true, BulkDeleteSection: true,
  },
}

async function attachUserFile(w: ReturnType<typeof mount>) {
  const input = w.find('input[type="file"][aria-label="CSV File"]')
  const file = new File(['uid\na\n'], 'u.csv', { type: 'text/csv' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}
function btnByText(w: ReturnType<typeof mount>, text: string) {
  return w.findAll('button').find(b => b.text().includes(text))!
}

describe('BulkView — user import into a profile', () => {
  beforeEach(() => vi.clearAllMocks())

  it('imports into the chosen profile and gates Perform Import on the profile name', async () => {
    const w = mount(BulkView, { global })
    await flushPromises() // onMounted: profiles + templates

    await w.find('#bulk-import-profile').setValue('p1')
    await w.find('#bulk-import-template').setValue('t1')
    await attachUserFile(w)

    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()

    // Preview targets the profile (profileId), not a hand-entered parent DN.
    expect(previewCsv).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ profileId: 'p1' }))

    // Perform Import stays disabled until the profile name is typed.
    expect(btnByText(w, 'Perform Import').attributes('disabled')).toBeDefined()
    await w.find('input[aria-label="Type the profile name to confirm"]').setValue('Engineers')
    expect(btnByText(w, 'Perform Import').attributes('disabled')).toBeUndefined()

    await btnByText(w, 'Perform Import').trigger('click')
    await flushPromises()
    expect(importCsv).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ profileId: 'p1' }))
  })
})
