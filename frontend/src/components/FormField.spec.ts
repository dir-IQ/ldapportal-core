// SPDX-License-Identifier: Apache-2.0
/**
 * FormField — the password reveal toggle (opt-in via `revealable`). Masked by
 * default; an eye button flips the input between password and text on click.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import FormField from './FormField.vue'

const revealButton = (w: ReturnType<typeof mount>) =>
  w.findAll('button').find((b) => {
    const label = b.attributes('aria-label')
    return label === 'Show password' || label === 'Hide password'
  })

describe('FormField password reveal', () => {
  it('renders no reveal toggle by default (secret stays masked)', () => {
    const w = mount(FormField, { props: { type: 'password', modelValue: 'hunter2' } })
    expect(w.find('input').attributes('type')).toBe('password')
    expect(revealButton(w)).toBeUndefined()
  })

  it('toggles between password and text when revealable and the eye is clicked', async () => {
    const w = mount(FormField, {
      props: { type: 'password', modelValue: 'hunter2', revealable: true },
    })
    const input = w.find('input')
    expect(input.attributes('type')).toBe('password')

    const btn = revealButton(w)!
    expect(btn).toBeDefined()
    expect(btn.attributes('aria-label')).toBe('Show password')

    await btn.trigger('click')
    expect(w.find('input').attributes('type')).toBe('text')
    expect(revealButton(w)!.attributes('aria-label')).toBe('Hide password')

    await revealButton(w)!.trigger('click')
    expect(w.find('input').attributes('type')).toBe('password')
  })

  it('ignores revealable for non-password inputs', () => {
    const w = mount(FormField, { props: { type: 'text', modelValue: 'x', revealable: true } })
    expect(revealButton(w)).toBeUndefined()
  })
})
