// SPDX-License-Identifier: Apache-2.0
/**
 * Tests the schema-management flow in SchemaManageView: directory picker →
 * unsupported-vendor gating → LDIF preview (element table, counts, blocking
 * banner) → apply, including the OpenLDAP config-credentials requirement. The
 * schema/directories API modules are mocked; PageContainer is stubbed.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

const api = vi.hoisted(() => ({
  listDirectories: vi.fn(),
  previewSchemaLdif: vi.fn(),
  applySchemaPreview: vi.fn(),
  exportSchema: vi.fn(),
  confirm: vi.fn().mockResolvedValue(true),
}))

vi.mock('@/api/directories', () => ({ listDirectories: api.listDirectories }))
vi.mock('@/api/schema', () => ({
  previewSchemaLdif: api.previewSchemaLdif,
  applySchemaPreview: api.applySchemaPreview,
  exportSchema: api.exportSchema,
}))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => api.confirm }))

import SchemaManageView from './SchemaManageView.vue'

const stubs = {
  PageContainer: { template: '<div><slot /></div>' },
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

async function mountWith(directories: Record<string, unknown>[]) {
  api.listDirectories.mockResolvedValue({ data: directories })
  const wrapper = mount(SchemaManageView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

async function pickFileAndPreview(wrapper: ReturnType<typeof mount>) {
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

const OPENDJ = { id: 'dir-1', displayName: 'OpenDJ Dev', directoryType: 'ORACLE_UNIFIED_DIRECTORY' }
const OPENLDAP = { id: 'dir-1', displayName: 'OpenLDAP Dev', directoryType: 'OPENLDAP' }
const AD = { id: 'dir-1', displayName: 'Corp AD', directoryType: 'ACTIVE_DIRECTORY' }

describe('SchemaManageView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.confirm.mockResolvedValue(true)
  })

  it('gates unsupported vendors: shows a notice and no upload area', async () => {
    const wrapper = await mountWith([AD])
    expect(wrapper.text()).toContain('only supported for OpenLDAP and OpenDJ/OUD')
    expect(wrapper.text()).toContain('Active Directory')
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })

  it('previews an OpenDJ LDIF and lists the classified elements', async () => {
    const wrapper = await mountWith([OPENDJ])
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
    const wrapper = await mountWith([OPENDJ])
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

  it('applies a non-blocking OpenDJ preview without config creds and shows results', async () => {
    const wrapper = await mountWith([OPENDJ])
    api.previewSchemaLdif.mockResolvedValue({ data: summary() })
    api.applySchemaPreview.mockResolvedValue({ data: { applied: 1, failed: 0, errors: [] } })

    await pickFileAndPreview(wrapper)
    const applyBtn = wrapper.findAll('button').find(b => b.text().startsWith('Apply'))!
    await applyBtn.trigger('click')
    await flushPromises()

    expect(api.applySchemaPreview).toHaveBeenCalledWith('dir-1', 'sp-1', '', '')
    expect(wrapper.text()).toContain('Apply results')
    expect(wrapper.text()).toContain('Applied')
  })

  it('requires config credentials before applying on OpenLDAP', async () => {
    const wrapper = await mountWith([OPENLDAP])
    api.previewSchemaLdif.mockResolvedValue({ data: summary({ vendor: 'OPENLDAP' }) })
    api.applySchemaPreview.mockResolvedValue({ data: { applied: 1, failed: 0, errors: [] } })

    await pickFileAndPreview(wrapper)

    // Config fields present, apply disabled until they're filled.
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

    expect(api.applySchemaPreview).toHaveBeenCalledWith('dir-1', 'sp-1', 'cn=admin,cn=config', 'config-secret')
  })
})
