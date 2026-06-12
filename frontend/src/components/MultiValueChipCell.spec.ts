// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MultiValueChipCell from './MultiValueChipCell.vue'

function mountCell(modelValue: string[] = ['first', 'second']) {
  return mount(MultiValueChipCell, {
    props: { modelValue, inputLabel: 'description for cn=alice' },
  })
}

describe('MultiValueChipCell', () => {
  it('renders one chip per value with a remove button', () => {
    const w = mountCell()
    const chips = w.findAll('.badge-blue')
    expect(chips).toHaveLength(2)
    expect(chips[0].text()).toContain('first')
    expect(w.findAll('button[aria-label^="Remove"]')).toHaveLength(2)
  })

  it('emits the remaining values when a chip is removed', async () => {
    const w = mountCell()
    await w.find('button[aria-label="Remove first"]').trigger('click')
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([['second']])
  })

  it('appends the trimmed draft on Enter and clears the input', async () => {
    const w = mountCell()
    const input = w.find('input')
    await input.setValue('  third  ')
    await input.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([['first', 'second', 'third']])
    expect((input.element as HTMLInputElement).value).toBe('')
  })

  it('appends on comma too', async () => {
    const w = mountCell()
    const input = w.find('input')
    await input.setValue('third')
    await input.trigger('keydown', { key: ',' })
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([['first', 'second', 'third']])
  })

  it('commits a half-typed draft on blur (row-level focusout save must include it)', async () => {
    const w = mountCell()
    const input = w.find('input')
    await input.setValue('third')
    await input.trigger('blur')
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([['first', 'second', 'third']])
  })

  it('drops exact duplicates silently', async () => {
    const w = mountCell()
    const input = w.find('input')
    await input.setValue('second')
    await input.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('update:modelValue')).toBeUndefined()
    expect((input.element as HTMLInputElement).value).toBe('')
  })

  it('Backspace in the empty input removes the last chip', async () => {
    const w = mountCell()
    await w.find('input').trigger('keydown', { key: 'Backspace' })
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([['first']])
  })

  it('Backspace with a draft in progress edits the draft, not the chips', async () => {
    const w = mountCell()
    const input = w.find('input')
    await input.setValue('dr')
    await input.trigger('keydown', { key: 'Backspace' })
    expect(w.emitted('update:modelValue')).toBeUndefined()
  })

  it('Escape clears the draft and emits cancel', async () => {
    const w = mountCell()
    const input = w.find('input')
    await input.setValue('half-typed')
    await input.trigger('keydown', { key: 'Escape' })
    expect(w.emitted('cancel')).toBeTruthy()
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(w.emitted('update:modelValue')).toBeUndefined()
  })
})
