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
  confirm: vi.fn().mockResolvedValue(true),
}))

vi.mock('@/api/browse', () => ({
  previewLdif: api.previewLdif,
  getLdifPreviewPage: api.getLdifPreviewPage,
  getLdifPreviewRow: api.getLdifPreviewRow,
  applyLdifPreview: api.applyLdifPreview,
}))

vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => api.confirm }))

// Auth store: only isIsvaIntegrationEnabled is read by the modal. Toggle it
// per-test via the hoisted state (default off so existing tests are unaffected).
const authState = vi.hoisted(() => ({ isvaEnabled: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ get isIsvaIntegrationEnabled() { return authState.isvaEnabled } }),
}))

const isvaApi = vi.hoisted(() => ({ getIsvaConfig: vi.fn() }))
vi.mock('@/api/isvaConfig', () => ({ getIsvaConfig: isvaApi.getIsvaConfig }))

import LdifImportModal from './LdifImportModal.vue'

const stubs = {
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  DataTable: {
    props: ['columns', 'rows', 'rowKey', 'loading', 'expandedKey'],
    emits: ['row-click'],
    template: `<div class="dt"><template v-for="r in rows" :key="r.rowNumber">
      <div class="dt-row" @click="$emit('row-click', r)">
        <slot name="cell-op" :row="r" /><slot name="cell-dn" :row="r" /><slot name="cell-detail" :row="r" /><slot name="cell-issues" :row="r" />
      </div>
      <div v-if="expandedKey !== undefined && r.rowNumber === expandedKey" class="dt-detail"><slot name="row-detail" :row="r" /></div>
    </template></div>`,
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
    authState.isvaEnabled = false
    isvaApi.getIsvaConfig.mockResolvedValue({ data: { enabled: true, topologyMode: 'INLINE' } })
    api.confirm.mockResolvedValue(true)
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

    expect(api.applyLdifPreview).toHaveBeenCalledWith('dir-1', 'prev-1', false, [])
    expect(wrapper.emitted('imported')).toBeTruthy()
    expect(wrapper.text()).toContain('Import Results')
  })

  it('confirms before applying a destructive (delete) import', async () => {
    api.previewLdif.mockResolvedValue({
      data: summary({ countsByOp: { add: 1, modify: 0, delete: 2, moddn: 0, skip: 0, error: 0 } }),
    })
    const wrapper = mountModal()
    await toPreview(wrapper)

    // Declined → nothing applied.
    api.confirm.mockResolvedValueOnce(false)
    await byText(wrapper, 'Import (3)')[0].trigger('click')
    await flushPromises()
    expect(api.confirm).toHaveBeenCalledWith(expect.objectContaining({ danger: true }))
    expect(api.applyLdifPreview).not.toHaveBeenCalled()

    // Confirmed → applied.
    await byText(wrapper, 'Import (3)')[0].trigger('click')
    await flushPromises()
    expect(api.applyLdifPreview).toHaveBeenCalledWith('dir-1', 'prev-1', false, [])
  })

  it('shows the secUser provisioning indication + per-row badge when IVIA is enabled, and the toggle drives apply', async () => {
    authState.isvaEnabled = true
    api.previewLdif.mockResolvedValue({
      data: summary({
        userAddCount: 1,
        containsVendorOverlayEntries: false,
        page0: {
          rows: [
            { rowNumber: 1, dn: 'uid=bob,dc=example,dc=com', op: 'ADD', objectClasses: ['inetOrgPerson'], attrCount: 5, memberDelta: null, memberCount: null, issues: [], userAdd: true },
            { rowNumber: 2, dn: 'cn=team,dc=example,dc=com', op: 'MODIFY', objectClasses: [], attrCount: 2, memberDelta: null, memberCount: null, issues: [], userAdd: false },
          ],
          page: 0, size: 50, totalFiltered: 2,
        },
      }),
    })
    const wrapper = mountModal()
    await toPreview(wrapper)
    await flushPromises()

    // Indication mentions secUser; the user-add row carries the +secUser badge.
    expect(wrapper.text()).toContain('secUser')
    expect(wrapper.text()).toContain('+secUser')

    // Toggle the provisioning off → apply passes suppressVendorOverlay = true.
    await wrapper.find('input[type="checkbox"]').setValue(false)
    expect(wrapper.text()).not.toContain('+secUser')
    await byText(wrapper, 'Import (2)')[0].trigger('click')
    await flushPromises()
    expect(api.applyLdifPreview).toHaveBeenCalledWith('dir-1', 'prev-1', true, [])
  })

  it('per-row +secUser badge toggles that row out of provisioning on apply', async () => {
    authState.isvaEnabled = true
    api.previewLdif.mockResolvedValue({
      data: summary({
        userAddCount: 1,
        containsVendorOverlayEntries: false,
        countsByOp: { add: 1, modify: 0, delete: 0, moddn: 0, skip: 0, error: 0 },
        page0: {
          rows: [
            { rowNumber: 1, dn: 'uid=bob,dc=example,dc=com', op: 'ADD', objectClasses: ['inetOrgPerson'], attrCount: 5, memberDelta: null, memberCount: null, issues: [], userAdd: true },
          ],
          page: 0, size: 50, totalFiltered: 1,
        },
      }),
    })
    const wrapper = mountModal()
    await toPreview(wrapper)
    await flushPromises()

    // Click the +secUser badge → it flips to "secUser skipped".
    const badge = wrapper.findAll('[role="button"]').find(el => el.text() === '+secUser')
    expect(badge).toBeTruthy()
    await badge!.trigger('click')
    expect(wrapper.text()).toContain('secUser skipped')

    // Apply now sends row 1 in excludeOverlayRows.
    await byText(wrapper, 'Import (1)')[0].trigger('click')
    await flushPromises()
    expect(api.applyLdifPreview).toHaveBeenCalledWith('dir-1', 'prev-1', false, [1])
  })

  it('suppresses provisioning when the file already contains secUser entries', async () => {
    authState.isvaEnabled = true
    api.previewLdif.mockResolvedValue({
      data: summary({ userAddCount: 1, containsVendorOverlayEntries: true }),
    })
    const wrapper = mountModal()
    await toPreview(wrapper)
    await flushPromises()

    expect(wrapper.text()).toContain('already contains')
    // No toggle / badge when suppressed file-wide.
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('+secUser')
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
