// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TemplateColumnMappings from './TemplateColumnMappings.vue'
import type { MappingStacks } from './templateMapping'

function make(stacks: MappingStacks, props: Record<string, unknown> = {}) {
  const w = mount(TemplateColumnMappings, {
    props: {
      stacks,
      'onUpdate:stacks': (v: MappingStacks) => w.setProps({ stacks: v }),
      ...props,
    },
    attachTo: document.body,
  })
  return w
}

const inputs = (w: ReturnType<typeof make>) =>
  w.findAll('.stack-chip[data-side="in"]').map(c => c.text().replace('⋮⋮', '').trim())
const attrs = (w: ReturnType<typeof make>) =>
  w.findAll('.stack-chip[data-side="out"]').map(c => c.attributes('data-attr') ?? null)
const statuses = (w: ReturnType<typeof make>) =>
  w.findAll('[data-status]').map(r => r.attributes('data-status'))

function sample(): MappingStacks {
  return {
    inputs: ['Email', 'Login', 'CostCenter'],
    attrs: [{ name: 'uid', required: true }, { name: 'mail', required: false }, null],
  }
}

describe('TemplateColumnMappings', () => {
  it('shows the empty-state copy with no rows', () => {
    const w = make({ inputs: [], attrs: [] })
    expect(w.text()).toContain('Select an object class to populate attribute mappings.')
  })

  it('pairs rows by position and labels each row state', () => {
    const w = make({ inputs: ['Login', null, 'CostCenter'], attrs: [{ name: 'uid', required: true }, { name: 'cn', required: true }, null] })
    expect(statuses(w)).toEqual(['mapped', 'missing', 'ignored'])
    expect(w.text()).toContain('1 mapped')
    expect(w.text()).toContain('1 required missing')
    expect(w.text()).toContain('1 input ignored')
    expect(w.text()).toContain('needs input')
  })

  it('moves an input with the ▲▼ buttons without touching the attribute stack', async () => {
    const stacks = sample()
    const w = make(stacks)
    await w.find('button[aria-label="Move input Login up"]').trigger('click')
    expect(inputs(w)).toEqual(['Login', 'Email', 'CostCenter'])
    expect(attrs(w)).toEqual(['uid', 'mail', null])
    expect(statuses(w)).toEqual(['mapped', 'mapped', 'ignored'])
    // First row can't move up, last row can't move down.
    expect(w.find('button[aria-label="Move input Login up"]').attributes('disabled')).toBeDefined()
    expect(w.find('button[aria-label="Move input CostCenter down"]').attributes('disabled')).toBeDefined()
  })

  it('moves an attribute (or an empty slot) with Alt+arrow keys', async () => {
    const w = make(sample())
    await w.find('.stack-chip[data-side="out"][data-index="2"]').trigger('keydown', { key: 'ArrowUp', altKey: true })
    expect(attrs(w)).toEqual(['uid', null, 'mail'])
    // Without Alt the key does nothing.
    await w.find('.stack-chip[data-side="out"][data-index="0"]').trigger('keydown', { key: 'ArrowDown' })
    expect(attrs(w)).toEqual(['uid', null, 'mail'])
  })

  it('reorders by drag and drop within one stack only', async () => {
    const w = make(sample())
    const from = w.find('.stack-chip[data-side="in"][data-index="2"]')
    await from.trigger('dragstart', { dataTransfer: { setData: () => {}, effectAllowed: '' } })
    // Dropping on the other stack is ignored.
    await w.find('.stack-chip[data-side="out"][data-index="0"]').trigger('drop')
    expect(inputs(w)).toEqual(['Email', 'Login', 'CostCenter'])
    await w.find('.stack-chip[data-side="in"][data-index="0"]').trigger('drop')
    expect(inputs(w)).toEqual(['CostCenter', 'Email', 'Login'])
  })

  it('emits remove for optional attributes only', async () => {
    const w = make(sample())
    const removes = w.findAll('button[aria-label="Remove mapping"]')
    expect(removes).toHaveLength(1) // mail; uid is required
    await removes[0].trigger('click')
    expect(w.emitted('remove')).toEqual([[1]])
  })

  it('renders editable inputs when no sample file is loaded', async () => {
    const stacks = sample()
    const w = make(stacks, { editableInputs: true })
    const field = w.find('input[aria-label="CSV column for row 3"]')
    await field.setValue('Dept')
    expect(w.props('stacks').inputs[2]).toBe('Dept')
  })

  it('labels the DN source column instead of calling it ignored', () => {
    const w = make({ inputs: ['dn', 'Login'], attrs: [null, { name: 'uid', required: true }] }, { dnColumn: 'DN' })
    expect(w.text()).toContain('DN column')
    expect(w.text()).not.toContain('input ignored')
  })

  it('shows first-row values as hints when the sample has no header row', () => {
    const w = make({ inputs: ['Column 2', 'Column 1'], attrs: [] }, { samples: { 'Column 1': 'alice', 'Column 2': 'a@x' } })
    // Hints follow the column by name, so they stay right after a reorder.
    const chips = w.findAll('.stack-chip[data-side="in"]')
    expect(chips[0].text()).toMatch(/Column 2\s+e\.g\. a@x/)
    expect(chips[1].text()).toMatch(/Column 1\s+e\.g\. alice/)
  })
})
