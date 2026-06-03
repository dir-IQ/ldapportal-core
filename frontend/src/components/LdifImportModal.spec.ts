// SPDX-License-Identifier: Apache-2.0
/**
 * Tests the LDIF import preview flow in LdifImportModal: pick file → preview
 * (summary chips, rows, member delta) → filter via the page endpoint → lazy
 * row detail → apply. The browse API module is mocked; AppModal/DataTable are
 * stubbed so slots render and row-click is clickable.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

const api = vi.hoisted(() => ({
  previewLdif: vi.fn(),
  getLdifPreviewPage: vi.fn(),
  getLdifPreviewRow: vi.fn(),
  applyLdifPreview: vi.fn(),
}))

vi.mock('@/api/browse', () => ({
  previewLdif: api.previewLdif,
  getLdifPreviewPage: api.getLdifPreviewPage,
  getLdifPreviewRow: api.getLdifPreviewRow,
  applyLdifPreview: api.applyLdifPreview,
}))

import LdifImportModal from './LdifImportModal.vue'

const stubs = {
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  DataTable: {
    props: ['columns', 'rows', 'rowKey', 'loading'],
    emits: ['row-click'],
    template: `<div class="dt"><div v-for="r in rows" :key="r.rowNumber" class="dt-row" @click="$emit('row-click', r)">
      <slot name="cell-op" :row="r" /><slot name="cell-dn" :row="r" /><slot name="cell-detail" :row="r" /><slot name="cell-issues" :row="r" />
    </div></div>`,
  },
}

function summary(overrides = {}) {
  return {
    previewId: 'prev-1',
    totalRows: 2,
    countsByOp: { add: 1, modify: 1, delete: 0, moddn: 0, skip: 0, error: 0 },
    warningCount: 0,
    errorCount: 0,
    truncated: false,
    page0: {
      rows: [
        { rowNumber: 1, dn: 'uid=bob,dc=example,dc=com', op: 'ADD', objectClasses: ['inetOrgPerson'], attrCount: 5, memberDelta: null, memberCount: null, issues: [] },
        { rowNumber: 2, dn: 'cn=team,dc=example,dc=com', op: 'MODIFY', objectClasses: [], attrCount: 2, memberDelta: { added: 12, removed: 3 }, memberCount: null, issues: [] },
      ],
      page: 0, size: 50, totalFiltered: 2,
    },
    ...overrides,
  }
}

function byText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').filter(b => b.text() === text)
}

async function toPreview(wrapper: ReturnType<typeof mount>) {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', {
    value: [new File(['dn: x\n'], 'x.ldif')], configurable: true,
  })
  await input.trigger('change')
  await byText(wrapper, 'Preview')[0].trigger('click')
  await flushPromises()
}

function mountModal() {
  return mount(LdifImportModal, {
    props: { modelValue: true, directoryId: 'dir-1' },
    global: { stubs },
  })
}

describe('LdifImportModal preview flow', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.previewLdif.mockResolvedValue({ data: summary() })
    api.getLdifPreviewPage.mockResolvedValue({ data: { rows: [], page: 0, size: 50, totalFiltered: 0 } })
    api.getLdifPreviewRow.mockResolvedValue({
      data: { rowNumber: 1, dn: 'uid=bob,dc=example,dc=com', op: 'ADD', attributes: { cn: ['Bob'], sn: ['Jones'] }, memberDelta: null, issues: [] },
    })
    api.applyLdifPreview.mockResolvedValue({ data: { added: 1, updated: 1, skipped: 0, failed: 0, errors: [] } })
  })

  it('previews and renders summary chips, rows, and member delta', async () => {
    const wrapper = mountModal()
    await toPreview(wrapper)

    expect(api.previewLdif).toHaveBeenCalledWith('dir-1', expect.any(File), 'SKIP')
    expect(wrapper.text()).toContain('Add')
    expect(wrapper.findAll('.dt-row')).toHaveLength(2)
    expect(wrapper.text()).toContain('uid=bob,dc=example,dc=com')
    // Member delta rendered for the group modify row.
    expect(wrapper.text()).toContain('+12')
    expect(wrapper.text()).toContain('−3')
  })

  it('filtering by op queries the page endpoint', async () => {
    const wrapper = mountModal()
    await toPreview(wrapper)

    await byText(wrapper, 'Adds')[0].trigger('click')
    await flushPromises()

    expect(api.getLdifPreviewPage).toHaveBeenCalledWith('dir-1', 'prev-1',
      expect.objectContaining({ op: 'ADD', page: 0 }))
  })

  it('opening a row lazy-loads its detail', async () => {
    const wrapper = mountModal()
    await toPreview(wrapper)

    await wrapper.findAll('.dt-row')[0].trigger('click')
    await flushPromises()

    expect(api.getLdifPreviewRow).toHaveBeenCalledWith('dir-1', 'prev-1', 1)
    expect(wrapper.text()).toContain('Bob')
    expect(wrapper.text()).toContain('Jones')
  })

  it('applies the previewed records and emits imported', async () => {
    const wrapper = mountModal()
    await toPreview(wrapper)

    await byText(wrapper, 'Import (2)')[0].trigger('click')
    await flushPromises()

    expect(api.applyLdifPreview).toHaveBeenCalledWith('dir-1', 'prev-1')
    expect(wrapper.emitted('imported')).toBeTruthy()
    expect(wrapper.text()).toContain('Import Results')
  })

  it('defaults to the Errors filter when the upload has parse errors', async () => {
    api.previewLdif.mockResolvedValue({
      data: summary({ errorCount: 1, countsByOp: { add: 1, modify: 0, delete: 0, moddn: 0, skip: 0, error: 1 } }),
    })
    api.getLdifPreviewPage.mockResolvedValue({
      data: { rows: [{ rowNumber: 2, dn: null, op: 'ERROR', objectClasses: [], attrCount: 0, memberDelta: null, memberCount: null, issues: [{ severity: 'ERROR', code: 'PARSE_ERROR', message: 'bad' }] }], page: 0, size: 50, totalFiltered: 1 },
    })
    const wrapper = mountModal()
    await toPreview(wrapper)

    expect(api.getLdifPreviewPage).toHaveBeenCalledWith('dir-1', 'prev-1',
      expect.objectContaining({ op: 'ERRORS' }))
  })
})
