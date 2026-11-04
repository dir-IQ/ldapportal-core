// SPDX-License-Identifier: Apache-2.0
/**
 * Every field of the provisioning-profile form has browser autocomplete
 * disabled: a profile's settings (DNs, templates, regexes, password rules)
 * are configuration, and a browser's remembered entries or saved-credential
 * autofill would only put wrong values in them. Reads the component sources
 * so a field added later without autocomplete="off" fails here.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import profilesView from './SuperadminProfilesView.vue?raw'
import formLayoutDesigner from '@/components/FormLayoutDesigner.vue?raw'
import isvaOverride from '@/components/profiles/IsvaProfileOverrideControl.vue?raw'
import DnPicker from '@/components/DnPicker.vue'

/** The opening tags of every input / select / textarea / DnPicker in an SFC's template. */
function fieldTags(sfc: string): string[] {
  const start = sfc.indexOf('<template>')
  const tpl = sfc.slice(start, sfc.lastIndexOf('</template>'))
  return tpl.match(/<(input|select|textarea|DnPicker)\b[^>]*>/g) ?? []
}

describe('provisioning profile form autocomplete', () => {
  it.each([
    ['SuperadminProfilesView', profilesView],
    ['FormLayoutDesigner', formLayoutDesigner],
    ['IsvaProfileOverrideControl', isvaOverride],
  ])('%s: every field has autocomplete="off"', (_name, sfc) => {
    const tags = fieldTags(sfc)
    expect(tags.length).toBeGreaterThan(0)
    expect(tags.filter(t => !/\sautocomplete="off"/.test(t))).toEqual([])
  })

  it('DnPicker puts its autocomplete prop on the text input, and leaves it unset by default', () => {
    const opts = { global: { stubs: { teleport: true }, directives: { 'dialog-a11y': {} } } }
    const off = mount(DnPicker, { props: { directoryId: 'd', autocomplete: 'off' }, ...opts })
    expect(off.find('input[type="text"]').attributes('autocomplete')).toBe('off')

    const plain = mount(DnPicker, { props: { directoryId: 'd' }, ...opts })
    expect(plain.find('input[type="text"]').attributes('autocomplete')).toBeUndefined()
  })
})
