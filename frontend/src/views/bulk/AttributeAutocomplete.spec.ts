// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import AttributeAutocomplete from './AttributeAutocomplete.vue'
import type { SchemaAttr } from './templateMapping'

const attrs = (...names: string[]): SchemaAttr[] => names.map(name => ({ name, required: name === 'sn' }))

let w: VueWrapper | null = null
afterEach(() => { w?.unmount(); w = null })

function make(options: SchemaAttr[], modelValue = '') {
  w = mount(AttributeAutocomplete, {
    props: {
      modelValue, options, label: 'LDAP attribute for Street',
      'onUpdate:modelValue': (v: string) => w!.setProps({ modelValue: v }),
    },
    attachTo: document.body,
  })
  return w
}

// The list is teleported out of the component, so read it from the document.
const shown = () => Array.from(document.querySelectorAll('[role="option"]')).map(o => o.getAttribute('data-attr'))

async function type(wr: VueWrapper, value: string) {
  const input = wr.find('input')
  await input.trigger('focus')
  await input.setValue(value)
}

describe('AttributeAutocomplete', () => {
  it('keeps an exactly typed attribute in the list, first', async () => {
    const wr = make(attrs('cn', 'c', 'carLicense', 'l', 'labeledURI', 'street', 'streetAddress'))
    await type(wr, 'l')
    expect(shown()).toEqual(['l', 'labeledURI', 'carLicense'])
    await type(wr, 'c')
    expect(shown()).toEqual(['c', 'cn', 'carLicense'])
    await type(wr, 'street')
    expect(shown()).toEqual(['street', 'streetAddress'])
  })

  it('picks an exact match with Enter', async () => {
    const wr = make(attrs('l', 'labeledURI'))
    await type(wr, 'l')
    await wr.find('input').trigger('keydown', { key: 'ArrowDown' })
    await wr.find('input').trigger('keydown', { key: 'Enter' })
    expect(wr.emitted('update:modelValue')?.at(-1)).toEqual(['l'])
    expect(wr.emitted('commit')).toHaveLength(1)
  })

  it('lists every match in a scrolling list, and keeps the highlighted one in view', async () => {
    const many = attrs(...Array.from({ length: 20 }, (_, i) => `attr${String(i).padStart(2, '0')}`))
    const wr = make(many)
    await wr.find('input').trigger('focus')
    expect(shown()).toHaveLength(20)
    const list = document.querySelector('[data-testid="attr-suggestions"]')!
    expect(list.className).toContain('overflow-y-auto')

    const scrolled = vi.fn()
    Element.prototype.scrollIntoView = scrolled
    for (let i = 0; i < 12; i++) await wr.find('input').trigger('keydown', { key: 'ArrowDown' })
    await nextTick()
    expect(document.querySelector('[aria-selected="true"]')?.getAttribute('data-attr')).toBe('attr11')
    expect(scrolled).toHaveBeenLastCalledWith({ block: 'nearest' })
  })

  it('closes on Escape without letting the key reach the modal', async () => {
    const wr = make(attrs('mail'))
    await wr.find('input').trigger('focus')
    expect(shown()).toEqual(['mail'])
    const outer = vi.fn()
    document.addEventListener('keydown', outer)
    await wr.find('input').trigger('keydown', { key: 'Escape' })
    document.removeEventListener('keydown', outer)
    expect(shown()).toEqual([])
    expect(outer).not.toHaveBeenCalled()
  })

  it('matches a special target by the start of a word in its label only', async () => {
    const opts: SchemaAttr[] = [
      { name: '@groups', required: false, label: 'Group memberships', hint: 'Adds the user to groups' },
      ...attrs('sn', 'mail'),
    ]
    const wr = make(opts)
    await type(wr, 'memb')
    expect(shown()).toEqual(['@groups'])
    expect(document.querySelector('[role="option"]')!.textContent).toContain('Adds the user to groups')
    await type(wr, 's')
    expect(shown()).toEqual(['sn'])
  })
})
