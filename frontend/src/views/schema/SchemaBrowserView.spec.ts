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
}))

import { listDirectories } from '@/api/directories'
import { listObjectClasses, getObjectClass, listAttributeTypes, getAttributeType } from '@/api/schema'

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

const mountView = () =>
  mount(SchemaBrowserView, {
    global: { stubs: { PageContainer: { template: '<div><slot /></div>' } } },
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

describe('SchemaBrowserView attribute detail', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    stubResponses()
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
})
