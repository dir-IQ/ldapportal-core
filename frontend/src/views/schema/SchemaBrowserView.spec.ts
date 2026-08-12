// SPDX-License-Identifier: Apache-2.0
/**
 * Schema browser — attribute view. Covers the reverse "used by" index (which
 * object classes reference the attribute, MUST vs MAY), the direct-vs-inherited
 * selector, the resolved syntax detail, and chip cross-navigation back to an
 * object class.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import SchemaBrowserView from './SchemaBrowserView.vue'

vi.mock('@/api/directories', () => ({
  listDirectories: vi.fn(),
}))
vi.mock('@/api/schema', () => ({
  listObjectClasses: vi.fn(),
  getObjectClass: vi.fn(),
  listAttributeTypes: vi.fn(),
  getAttributeType: vi.fn(),
  exportSchema: vi.fn(),
  previewSchemaLdif: vi.fn(),
  applySchemaPreview: vi.fn(),
}))

// The write toolbar is gated by the manage_schema permission; toggle it per
// test via the hoisted state (default off, so the read-only tests are unaffected).
const authState = vi.hoisted(() => ({ canManage: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasSuperadminPermission: () => authState.canManage }),
}))

import { listDirectories } from '@/api/directories'
import { listObjectClasses, getObjectClass, listAttributeTypes, getAttributeType, exportSchema } from '@/api/schema'

const ATTR_DETAIL = {
  oid: '2.5.4.3',
  description: 'Common name',
  singleValued: false,
  syntax: { oid: '1.3.6.1.4.1.1466.115.121.1.15', description: 'Directory String', maxLength: 128 },
  usedBy: [
    { objectClass: 'alphaClass', required: true, inherited: false },   // required, direct
    { objectClass: 'betaClass', required: true, inherited: true },     // required, inherited
    { objectClass: 'gammaClass', required: false, inherited: false },  // optional, direct
    { objectClass: 'deltaClass', required: false, inherited: true },   // optional, inherited
  ],
}

function stubResponses() {
  vi.mocked(listDirectories).mockResolvedValue({
    data: [{ id: 'dir-1', displayName: 'OUD', directoryType: 'GENERIC' }],
  } as never)
  vi.mocked(listObjectClasses).mockResolvedValue({ data: [{ name: 'person', oid: '2.5.6.6' }] } as never)
  vi.mocked(listAttributeTypes).mockResolvedValue({ data: [{ name: 'cn', oid: '2.5.4.3' }] } as never)
  vi.mocked(getAttributeType).mockResolvedValue({ data: ATTR_DETAIL } as never)
  vi.mocked(getObjectClass).mockResolvedValue({ data: { oid: '2.5.6.6', required: ['cn'], optional: [] } } as never)
}

const ImportModalStub = {
  name: 'SchemaImportModal',
  props: ['modelValue', 'directoryId', 'directoryType'],
  emits: ['update:modelValue', 'applied'],
  template: '<div class="import-modal" />',
}

const mountView = () =>
  mount(SchemaBrowserView, {
    global: {
      stubs: {
        PageContainer: { template: '<div><slot /></div>' },
        SchemaImportModal: ImportModalStub,
      },
    },
  })

const btn = (w: ReturnType<typeof mountView>, text: string) =>
  w.findAll('button').find(b => b.text() === text)

/** Drive the view to the attribute-detail state for `cn`. */
async function openAttribute(w: ReturnType<typeof mountView>) {
  await flushPromises()                    // directories + initial object-class list
  await btn(w, 'Attribute Types')!.trigger('click')
  await flushPromises()                    // attribute-type list
  await btn(w, 'cn')!.trigger('click')
  await flushPromises()                    // attribute detail
}

const backLink = (w: ReturnType<typeof mountView>, name: string) =>
  w.findAll('button').find(b => b.text().includes(`Back to ${name}`))

describe('SchemaBrowserView attribute detail', () => {
  let scrollSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    authState.canManage = false
    stubResponses()
    // jsdom has no layout, so scrollIntoView isn't implemented — stub it both
    // to avoid noise and to assert the selected row is scrolled back into view.
    scrollSpy = vi.fn()
    Element.prototype.scrollIntoView = scrollSpy as unknown as () => void
  })

  it('shows the resolved syntax name, OID, and length hint', async () => {
    const w = mountView()
    await openAttribute(w)
    expect(w.text()).toContain('Directory String')
    expect(w.text()).toContain('1.3.6.1.4.1.1466.115.121.1.15')
    expect(w.text()).toContain('{128}')
  })

  it('lists direct usages only by default, splitting MUST from MAY', async () => {
    const w = mountView()
    await openAttribute(w)
    // Direct usages are shown…
    expect(w.text()).toContain('alphaClass')   // required, direct
    expect(w.text()).toContain('gammaClass')   // optional, direct
    // …inherited ones are hidden until the selector is flipped.
    expect(w.text()).not.toContain('betaClass')
    expect(w.text()).not.toContain('deltaClass')
  })

  it('reveals inherited usages when the selector switches to "Incl. inherited"', async () => {
    const w = mountView()
    await openAttribute(w)
    await btn(w, 'Incl. inherited')!.trigger('click')
    expect(w.text()).toContain('betaClass')    // required, inherited
    expect(w.text()).toContain('deltaClass')   // optional, inherited
  })

  it('navigates to an object class when a used-by chip is clicked', async () => {
    const w = mountView()
    await openAttribute(w)
    await btn(w, 'alphaClass')!.trigger('click')
    await flushPromises()
    expect(vi.mocked(getObjectClass)).toHaveBeenCalledWith('dir-1', 'alphaClass')
    // A back link appears so the jump can be walked back.
    expect(w.text()).toContain('Back to cn')
  })

  it('scrolls the attribute row back into view when returning via the back link', async () => {
    const w = mountView()
    await openAttribute(w)                          // attribute 'cn'
    await btn(w, 'alphaClass')!.trigger('click')    // → object class
    await flushPromises()
    scrollSpy.mockClear()
    await backLink(w, 'cn')!.trigger('click')       // back to attribute 'cn'
    await flushPromises()
    expect(scrollSpy).toHaveBeenCalled()
  })

  it('scrolls the object-class row back into view when returning from an attribute', async () => {
    const w = mountView()
    await flushPromises()                           // object-class list
    await btn(w, 'person')!.trigger('click')        // object class 'person'
    await flushPromises()
    await btn(w, 'cn')!.trigger('click')            // follow its 'cn' chip → attribute
    await flushPromises()
    scrollSpy.mockClear()
    await backLink(w, 'person')!.trigger('click')   // back to object class 'person'
    await flushPromises()
    expect(scrollSpy).toHaveBeenCalled()
  })
})

describe('SchemaBrowserView schema management toolbar', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    authState.canManage = false
    stubResponses()
    Element.prototype.scrollIntoView = vi.fn() as unknown as () => void
  })

  function withSupportedDirectory() {
    vi.mocked(listDirectories).mockResolvedValue({
      data: [{ id: 'dir-1', displayName: 'OpenDJ', directoryType: 'ORACLE_UNIFIED_DIRECTORY' }],
    } as never)
  }

  it('hides the write toolbar without the manage_schema permission', async () => {
    withSupportedDirectory() // supported vendor, but no permission
    const w = mountView()
    await flushPromises()
    expect(btn(w, 'Update schema…')).toBeUndefined()
    expect(btn(w, 'Export schema')).toBeUndefined()
    expect(w.find('.import-modal').exists()).toBe(false)
  })

  it('hides the write toolbar on unsupported vendors even with permission', async () => {
    authState.canManage = true
    // stubResponses()'s default directory is GENERIC — unsupported for writes.
    const w = mountView()
    await flushPromises()
    expect(btn(w, 'Update schema…')).toBeUndefined()
    expect(btn(w, 'Export schema')).toBeUndefined()
  })

  it('shows the toolbar and opens the modal on a supported vendor with permission', async () => {
    authState.canManage = true
    withSupportedDirectory()
    const w = mountView()
    await flushPromises()

    expect(btn(w, 'Export schema')).toBeTruthy()
    const update = btn(w, 'Update schema…')!
    expect(update).toBeTruthy()

    const modal = w.findComponent(ImportModalStub)
    expect(modal.props('modelValue')).toBe(false)
    await update.trigger('click')
    expect(modal.props('modelValue')).toBe(true)
    expect(modal.props('directoryType')).toBe('ORACLE_UNIFIED_DIRECTORY')
  })

  it('downloads the current schema when Export is clicked', async () => {
    authState.canManage = true
    withSupportedDirectory()
    vi.mocked(exportSchema).mockResolvedValue({ data: new Blob(['schema']) } as never)
    // jsdom lacks these; stub so the download path runs without throwing.
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:x')
    globalThis.URL.revokeObjectURL = vi.fn()

    const w = mountView()
    await flushPromises()
    await btn(w, 'Export schema')!.trigger('click')
    await flushPromises()

    expect(vi.mocked(exportSchema)).toHaveBeenCalledWith('dir-1')
  })

  it('reloads the current tab list after a schema apply', async () => {
    authState.canManage = true
    withSupportedDirectory()
    const w = mountView()
    await flushPromises()

    // Initial load fetched object classes once; an apply should refetch them.
    expect(vi.mocked(listObjectClasses)).toHaveBeenCalledTimes(1)
    w.findComponent(ImportModalStub).vm.$emit('applied')
    await flushPromises()
    expect(vi.mocked(listObjectClasses)).toHaveBeenCalledTimes(2)
  })
})

describe('SchemaBrowserView object-class hierarchy', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    authState.canManage = false
    stubResponses()
    Element.prototype.scrollIntoView = vi.fn() as unknown as () => void
  })

  const OC_DETAIL = {
    oid: '1.3.18.0.2.6.79',
    kind: 'STRUCTURAL',
    description: 'An IVIA secUser',
    required: ['objectClass'],
    optional: ['principalName'],
    superiors: ['eUser', 'cimManagedElement', 'top'],
    subclasses: ['secUserChild'],
  }

  /** Open the single object class the stub list carries. */
  async function openObjectClass(w: ReturnType<typeof mountView>) {
    await flushPromises()
    await btn(w, 'person')!.trigger('click')
    await flushPromises()
  }

  it('renders the ancestor chain and subclasses', async () => {
    vi.mocked(getObjectClass).mockResolvedValue({ data: OC_DETAIL } as never)
    const w = mountView()
    await openObjectClass(w)

    const panel = w.find('[data-testid="oc-hierarchy"]')
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('eUser')
    expect(panel.text()).toContain('cimManagedElement')
    expect(panel.text()).toContain('top')
    expect(panel.text()).toContain('secUserChild')
    expect(panel.text()).toContain('STRUCTURAL')
  })

  it('navigates to a superclass and leaves a back link', async () => {
    vi.mocked(getObjectClass).mockResolvedValue({ data: OC_DETAIL } as never)
    const w = mountView()
    await openObjectClass(w)

    await btn(w, 'eUser')!.trigger('click')
    await flushPromises()

    expect(vi.mocked(getObjectClass)).toHaveBeenLastCalledWith('dir-1', 'eUser')
    // Same-tab hops are recorded, so climbing the chain stays reversible.
    expect(w.text()).toContain('Back to person')
  })

  it('omits the hierarchy panel when the class has none', async () => {
    vi.mocked(getObjectClass).mockResolvedValue({
      data: { oid: '2.5.6.6', required: ['cn'], optional: [] },
    } as never)
    const w = mountView()
    await openObjectClass(w)

    expect(w.find('[data-testid="oc-hierarchy"]').exists()).toBe(false)
  })

  it('does not record history for plain list-row clicks', async () => {
    vi.mocked(listObjectClasses).mockResolvedValue({
      data: [{ name: 'person', oid: '1' }, { name: 'device', oid: '2' }],
    } as never)
    vi.mocked(getObjectClass).mockResolvedValue({ data: OC_DETAIL } as never)
    const w = mountView()
    await openObjectClass(w)          // list click
    await btn(w, 'device')!.trigger('click')   // another list click
    await flushPromises()

    expect(w.text()).not.toContain('Back to')
  })
})

describe('SchemaBrowserView list de-dup + filter', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    authState.canManage = false
    stubResponses()
    Element.prototype.scrollIntoView = vi.fn() as unknown as () => void
  })

  // List rows are the font-mono buttons in the left panel (tab / chip buttons
  // aren't font-mono, and no row is selected on initial load so no detail chips
  // are present).
  const listRowNames = (w: ReturnType<typeof mountView>) =>
    w.findAll('button.font-mono').map((b) => b.text())

  it('collapses duplicate object-class names to a single row', async () => {
    vi.mocked(listObjectClasses).mockResolvedValue({
      data: [
        { name: 'eUser', oid: '1' }, { name: 'eUser', oid: '2' },
        { name: 'eUser', oid: '3' }, { name: 'container', oid: '4' },
      ],
    } as never)

    const w = mountView()
    await flushPromises()

    const names = listRowNames(w)
    expect(names.filter((n) => n === 'eUser')).toHaveLength(1)
    expect(names).toContain('container')
  })

  it('filters the list as you type (unique keys keep it reconciling)', async () => {
    vi.mocked(listObjectClasses).mockResolvedValue({
      data: [
        { name: 'eUser', oid: '1' }, { name: 'eUser', oid: '2' },
        { name: 'container', oid: '4' },
      ],
    } as never)

    const w = mountView()
    await flushPromises()

    await w.find('input[type="text"]').setValue('eu')

    const names = listRowNames(w)
    expect(names).toContain('eUser')
    expect(names).not.toContain('container')
  })
})
