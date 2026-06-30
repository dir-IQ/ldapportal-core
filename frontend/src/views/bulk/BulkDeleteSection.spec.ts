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

// Stub the themed confirm modal to a simple confirm trigger.
const ConfirmStub = {
  props: {
    modelValue: Boolean, profileName: String, themeColor: String, title: String,
    summary: String, targetDn: String, confirmLabel: String,
    requireTyped: Boolean, danger: Boolean, busy: Boolean,
  },
  emits: ['update:modelValue', 'confirm'],
  template: `<button v-if="modelValue" class="confirm-stub" :data-profile="profileName"
    :data-typed="requireTyped ? '1' : '0'" :data-danger="danger ? '1' : '0'"
    @click="$emit('confirm')">{{ confirmLabel }}</button>`,
}

function mountSection() {
  return mount(BulkDeleteSection, {
    props: { dirId: 'd1', activeProfile },
    global: { stubs: { BulkConfirmModal: ConfirmStub } },
  })
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

  it('previews and renders disposition badges with a will-delete count', async () => {
    vi.mocked(previewBulkDelete).mockResolvedValue({ data: { totalRows: 2, rows: [
      { rowNumber: 1, dn: 'uid=a,ou=p,dc=x', disposition: 'WILL_DELETE' },
      { rowNumber: 2, dn: 'uid=b,ou=p,dc=x', disposition: 'NOT_FOUND', note: 'No entry at this DN' },
    ] } } as never)

    const w = mountSection()
    await attachFile(w)
    await btnByText(w, 'Preview').trigger('click')
    await flushPromises()

    expect(previewBulkDelete).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ keyAttribute: null, baseDn: null, skipHeaderRow: true }))
    expect(w.find('.badge-green').text()).toContain('1 will delete')
    expect(w.find('.badge-gray').exists()).toBe(true)
  })

  it('deletes through a themed, type-to-confirm modal', async () => {
    vi.mocked(previewBulkDelete).mockResolvedValue({ data: { totalRows: 1, rows: [
      { rowNumber: 1, dn: 'uid=a,ou=p,dc=x', disposition: 'WILL_DELETE' },
    ] } } as never)
    vi.mocked(bulkDelete).mockResolvedValue({ data: { totalRows: 1, deleted: 1, skipped: 0, errors: 0,
      rows: [{ rowNumber: 1, dn: 'uid=a,ou=p,dc=x', status: 'DELETED' }] } } as never)

    const w = mountSection()
    await attachFile(w)
    await btnByText(w, 'Preview').trigger('click')
    await flushPromises()

    // The Delete button opens the themed confirm modal (require-typed + danger).
    await btnByText(w, 'Delete').trigger('click')
    const confirm = w.find('.confirm-stub')
    expect(confirm.exists()).toBe(true)
    expect(confirm.attributes('data-profile')).toBe('Engineers')
    expect(confirm.attributes('data-typed')).toBe('1')
    expect(confirm.attributes('data-danger')).toBe('1')

    await confirm.trigger('click')
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
})
