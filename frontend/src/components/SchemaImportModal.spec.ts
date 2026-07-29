// SPDX-License-Identifier: Apache-2.0
/**
 * Tests the schema-update flow in SchemaImportModal: pick file → preview
 * (element table, counts, blocking banner) → apply, including the OpenLDAP
 * config-credentials requirement and the `applied` emit that lets the host
 * refresh its schema lists. The schema API is mocked; AppModal is stubbed so
 * its slots (body + footer) render.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

const api = vi.hoisted(() => ({
  previewSchemaLdif: vi.fn(),
  applySchemaPreview: vi.fn(),
  confirm: vi.fn().mockResolvedValue(true),
}))

vi.mock('@/api/schema', () => ({
  previewSchemaLdif: api.previewSchemaLdif,
  applySchemaPreview: api.applySchemaPreview,
}))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => api.confirm }))

import SchemaImportModal from './SchemaImportModal.vue'

const stubs = {
  AppModal: {
    props: ['modelValue', 'dirty'],
    emits: ['update:modelValue'],
    template: `<div v-if="modelValue"><slot /><slot name="footer" :close="() => $emit('update:modelValue', false)" /></div>`,
  },
}

function element(overrides: Record<string, unknown> = {}) {
  return {
    rowNumber: 1,
    kind: 'ATTRIBUTE_TYPE',
    name: 'myCustomAttr',
    oid: '1.3.6.1.4.1.99999.1',
    action: 'ADD_NEW',
    targetDn: 'cn=schema',
    definition: '( 1.3.6.1.4.1.99999.1 NAME \'myCustomAttr\' )',
    issues: [] as unknown[],
    ...overrides,
  }
}

function summary(overrides: Record<string, unknown> = {}) {
  return {
    previewId: 'sp-1',
    directoryId: 'dir-1',
    vendor: 'ORACLE_UNIFIED_DIRECTORY',
    total: 1,
    counts: { addNew: 1, modifyExisting: 0, unsupported: 0, errors: 0 },
    elements: [element()],
    blocking: false,
    ...overrides,
  }
}

function mountModal(directoryType = 'ORACLE_UNIFIED_DIRECTORY') {
  return mount(SchemaImportModal, {
    props: { modelValue: true, directoryId: 'dir-1', directoryType },
    global: { stubs },
  })
}

async function pickFileAndPreview(wrapper: ReturnType<typeof mountModal>) {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', {
    value: [new File(['dn: cn=schema\n'], 'schema.ldif')],
    configurable: true,
  })
  await input.trigger('change')
  const previewBtn = wrapper.findAll('button').find(b => b.text() === 'Preview')!
  await previewBtn.trigger('click')
  await flushPromises()
}

describe('SchemaImportModal', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.confirm.mockResolvedValue(true)
  })

  it('previews an LDIF and lists the classified elements', async () => {
    const wrapper = mountModal()
    api.previewSchemaLdif.mockResolvedValue({ data: summary() })

    await pickFileAndPreview(wrapper)

    expect(api.previewSchemaLdif).toHaveBeenCalledWith('dir-1', expect.any(File))
    expect(wrapper.text()).toContain('myCustomAttr')
    expect(wrapper.text()).toContain('Add new')
    const applyBtn = wrapper.findAll('button').find(b => b.text().startsWith('Apply'))!
    expect(applyBtn.text()).toBe('Apply (1)')
    expect(applyBtn.attributes('disabled')).toBeUndefined()
  })

  it('disables apply and shows a banner when the preview is blocking', async () => {
    const wrapper = mountModal()
    api.previewSchemaLdif.mockResolvedValue({
      data: summary({
        blocking: true,
        counts: { addNew: 0, modifyExisting: 0, unsupported: 1, errors: 1 },
        elements: [element({
          action: 'UNSUPPORTED',
          issues: [{ severity: 'ERROR', code: 'MODIFY_UNSUPPORTED', message: 'cannot modify online' }],
        })],
      }),
    })

    await pickFileAndPreview(wrapper)

    expect(wrapper.text()).toContain('cannot be applied')
    const applyBtn = wrapper.findAll('button').find(b => b.text().startsWith('Apply'))!
    expect(applyBtn.text()).toBe('Apply (0)')
    expect(applyBtn.attributes('disabled')).toBeDefined()
    expect(api.applySchemaPreview).not.toHaveBeenCalled()
  })

  it('applies a non-blocking OpenDJ preview without config creds and emits applied', async () => {
    const wrapper = mountModal('ORACLE_UNIFIED_DIRECTORY')
    api.previewSchemaLdif.mockResolvedValue({ data: summary() })
    api.applySchemaPreview.mockResolvedValue({ data: { applied: 1, failed: 0, errors: [] } })

    await pickFileAndPreview(wrapper)
    const applyBtn = wrapper.findAll('button').find(b => b.text().startsWith('Apply'))!
    await applyBtn.trigger('click')
    await flushPromises()

    expect(api.applySchemaPreview).toHaveBeenCalledWith('dir-1', 'sp-1', '', '', false)
    expect(wrapper.text()).toContain('Apply results')
    expect(wrapper.text()).toContain('Applied')
    expect(wrapper.emitted('applied')).toBeTruthy()
  })

  it('requires config credentials before applying on OpenLDAP', async () => {
    const wrapper = mountModal('OPENLDAP')
    api.previewSchemaLdif.mockResolvedValue({ data: summary({ vendor: 'OPENLDAP' }) })
    api.applySchemaPreview.mockResolvedValue({ data: { applied: 1, failed: 0, errors: [] } })

    await pickFileAndPreview(wrapper)

    const bindInput = wrapper.find('input[aria-label="Config bind DN"]')
    const pwInput = wrapper.find('input[aria-label="Config password"]')
    expect(bindInput.exists()).toBe(true)
    let applyBtn = wrapper.findAll('button').find(b => b.text().startsWith('Apply'))!
    expect(applyBtn.attributes('disabled')).toBeDefined()

    await bindInput.setValue('cn=admin,cn=config')
    await pwInput.setValue('config-secret')
    applyBtn = wrapper.findAll('button').find(b => b.text().startsWith('Apply'))!
    expect(applyBtn.attributes('disabled')).toBeUndefined()

    await applyBtn.trigger('click')
    await flushPromises()

    expect(api.applySchemaPreview).toHaveBeenCalledWith('dir-1', 'sp-1', 'cn=admin,cn=config', 'config-secret', false)
  })

  it('hides the exclude-existing toggle when the preview has no modifications', async () => {
    const wrapper = mountModal()
    api.previewSchemaLdif.mockResolvedValue({ data: summary() }) // modifyExisting: 0

    await pickFileAndPreview(wrapper)

    expect(wrapper.find('input[aria-label="Exclude existing elements"]').exists()).toBe(false)
  })

  it('excludes existing updates and applies only adds when the toggle is on', async () => {
    const wrapper = mountModal('ORACLE_UNIFIED_DIRECTORY')
    api.previewSchemaLdif.mockResolvedValue({
      data: summary({
        total: 2,
        counts: { addNew: 1, modifyExisting: 1, unsupported: 0, errors: 0 },
        elements: [
          element({ rowNumber: 1, name: 'myCustomAttr', action: 'ADD_NEW' }),
          element({
            rowNumber: 2, name: 'cn', oid: '2.5.4.3', action: 'MODIFY_EXISTING',
            issues: [{ severity: 'WARN', code: 'MODIFIES_EXISTING', message: 'updates an existing element' }],
          }),
        ],
      }),
    })
    api.applySchemaPreview.mockResolvedValue({ data: { applied: 1, failed: 0, errors: [] } })

    await pickFileAndPreview(wrapper)

    const applyBtn = () => wrapper.findAll('button').find(b => b.text().startsWith('Apply'))!
    expect(applyBtn().text()).toBe('Apply (2)')

    const toggle = wrapper.find('input[aria-label="Exclude existing elements"]')
    expect(toggle.exists()).toBe(true)
    await toggle.setValue(true)

    expect(applyBtn().text()).toBe('Apply (1)') // only the ADD_NEW element
    expect(wrapper.text()).toContain('skipped')

    await applyBtn().trigger('click')
    await flushPromises()

    expect(api.applySchemaPreview).toHaveBeenCalledWith('dir-1', 'sp-1', '', '', true)
  })

  it('sorts the preview table by a column header and toggles direction', async () => {
    const wrapper = mountModal()
    api.previewSchemaLdif.mockResolvedValue({
      data: summary({
        total: 3,
        counts: { addNew: 3, modifyExisting: 0, unsupported: 0, errors: 0 },
        elements: [
          element({ rowNumber: 1, name: 'charlie', oid: '1.3.6.1.4.1.99999.3' }),
          element({ rowNumber: 2, name: 'alpha', oid: '1.3.6.1.4.1.99999.1' }),
          element({ rowNumber: 3, name: 'bravo', oid: '1.3.6.1.4.1.99999.2' }),
        ],
      }),
    })

    await pickFileAndPreview(wrapper)

    // Name is the 2nd column (Kind, Name, OID, Action, Issues).
    const names = () => wrapper.findAll('tbody tr').map(r => r.findAll('td')[1].text())
    // Default: backend/file order, untouched.
    expect(names()).toEqual(['charlie', 'alpha', 'bravo'])

    const nameHeader = wrapper.findAll('thead button').find(b => b.text().startsWith('Name'))!
    await nameHeader.trigger('click')            // ascending
    expect(names()).toEqual(['alpha', 'bravo', 'charlie'])

    await nameHeader.trigger('click')            // descending
    expect(names()).toEqual(['charlie', 'bravo', 'alpha'])
  })
})
