// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ExcludedAttributesEditor from './ExcludedAttributesEditor.vue'

const DEFAULTS = ['userPassword', 'entryUUID', 'cn']

describe('ExcludedAttributesEditor', () => {
  it('shows the defaults (with a Defaults badge) when attributes is null', () => {
    const w = mount(ExcludedAttributesEditor, { props: { attributes: null, defaults: DEFAULTS } })
    expect(w.text()).toContain('Defaults')
    for (const a of DEFAULTS) expect(w.text()).toContain(a)
  })

  it('adding an attribute commits an explicit list seeded from defaults', async () => {
    const w = mount(ExcludedAttributesEditor, { props: { attributes: null, defaults: DEFAULTS } })
    await w.find('input[type="text"]').setValue('telephoneNumber')
    await w.findAll('button').find(b => b.text() === 'Add')!.trigger('click')
    expect(w.emitted('update:attributes')![0][0])
      .toEqual([...DEFAULTS, 'telephoneNumber'])
  })

  it('removing a chip emits the filtered list', async () => {
    const w = mount(ExcludedAttributesEditor, { props: { attributes: ['userPassword', 'cn'], defaults: DEFAULTS } })
    await w.find('button[aria-label="Remove userPassword"]').trigger('click')
    expect(w.emitted('update:attributes')![0][0]).toEqual(['cn'])
  })

  it('reset emits null (back to defaults)', async () => {
    const w = mount(ExcludedAttributesEditor, { props: { attributes: ['cn'], defaults: DEFAULTS } })
    await w.findAll('button').find(b => b.text() === 'Reset to defaults')!.trigger('click')
    expect(w.emitted('update:attributes')![0][0]).toBeNull()
  })

  it('warns when a default password attribute is no longer excluded', () => {
    // userPassword (a default) is not in the effective list ⇒ passwords will sync.
    const w = mount(ExcludedAttributesEditor, { props: { attributes: ['cn'], defaults: DEFAULTS } })
    expect(w.text()).toContain('password values will be synced')
  })

  it('does not warn while the password attribute stays excluded', () => {
    const w = mount(ExcludedAttributesEditor, { props: { attributes: null, defaults: DEFAULTS } })
    expect(w.text()).not.toContain('password values will be synced')
  })
})
