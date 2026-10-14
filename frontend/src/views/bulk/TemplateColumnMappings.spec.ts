// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TemplateColumnMappings from './TemplateColumnMappings.vue'
import { checkMappings, type MappingRow, type SchemaAttr } from './templateMapping'

const schema: SchemaAttr[] = [
  { name: 'cn', required: true }, { name: 'sn', required: true },
  { name: 'mail', required: false }, { name: 'givenName', required: false },
]

function make(rows: MappingRow[], props: Record<string, unknown> = {}) {
  const dnColumn = (props.dnColumn as string | null) ?? null
  const check = (r: MappingRow[]) => checkMappings(r, { schema, dnColumn, rdnAttribute: null })
  const w = mount(TemplateColumnMappings, {
    props: {
      rows,
      schema,
      schemaState: 'loaded' as const,
      check: check(rows),
      'onUpdate:rows': (v: MappingRow[]) => w.setProps({ rows: v, check: check(v) }),
      ...props,
    },
    attachTo: document.body,
  })
  return w
}

const statuses = (w: ReturnType<typeof make>) =>
  w.findAll('[data-status]').map(r => r.attributes('data-status'))
const columns = (w: ReturnType<typeof make>) =>
  w.findAll('[data-column]').map(r => r.attributes('data-column'))
const attrInput = (w: ReturnType<typeof make>, label: string) =>
  w.find(`input[aria-label="LDAP attribute for ${label}"]`)

describe('TemplateColumnMappings', () => {
  it('shows the empty-state copy with no rows', () => {
    const w = make([])
    expect(w.text()).toContain('Choose a sample file, or add input columns')
  })

  it('is a plain list: no drag handles or move buttons', () => {
    const w = make([{ column: 'Email', attribute: 'mail' }, { column: 'Login', attribute: '' }])
    expect(w.find('[draggable="true"]').exists()).toBe(false)
    expect(w.find('button[aria-label^="Move"]').exists()).toBe(false)
  })

  it('labels each row and counts mapped and ignored columns', () => {
    const w = make([
      { column: 'First', attribute: 'cn' }, { column: 'Last', attribute: 'sn' }, { column: 'Notes', attribute: '' },
    ])
    expect(statuses(w)).toEqual(['mapped', 'mapped', 'ignored'])
    expect(w.text()).toContain('2 mapped')
    expect(w.text()).toContain('1 ignored')
  })

  it('lists required attributes nobody has mapped', () => {
    const w = make([{ column: 'First', attribute: 'cn' }])
    expect(w.text()).toContain('1 required attribute not mapped')
    expect(w.find('[data-testid="missing-required"]').text()).toContain('sn')
  })

  it('writes the typed attribute back and uses the schema spelling on blur', async () => {
    const w = make([{ column: 'First', attribute: '' }])
    const input = attrInput(w, 'First')
    await input.setValue('givenname')
    expect(w.props('rows')[0].attribute).toBe('givenname')
    await input.trigger('blur')
    expect(w.props('rows')[0].attribute).toBe('givenName')
  })

  it('suggests schema attributes as the operator types, required ones marked', async () => {
    const w = make([{ column: 'Last', attribute: '' }])
    const input = attrInput(w, 'Last')
    await input.trigger('focus')
    await input.setValue('s')
    const options = w.findAll('[role="option"]')
    expect(options.map(o => o.attributes('data-attr'))).toEqual(['sn'])
    expect(options[0].text()).toContain('required')
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })
    expect(w.props('rows')[0].attribute).toBe('sn')
  })

  it('hides the DN column when the columns come from a sample file', () => {
    const w = make([{ column: 'dn', attribute: '' }, { column: 'uid', attribute: 'cn' }],
      { fromSample: true, dnColumn: 'DN' })
    expect(columns(w)).toEqual(['uid'])
    expect(w.text()).toContain("isn't listed")
  })

  it("says when a mapped column became the DN column and won't be saved", () => {
    const w = make([{ column: 'uid', attribute: 'uid' }, { column: 'mail', attribute: 'cn' }],
      { fromSample: true, dnColumn: 'uid' })
    expect(columns(w)).toEqual(['mail'])
    expect(w.find('[data-testid="dn-mapping-dropped"]').text())
      .toMatch(/uid\s+is now the DN column, so its mapping to\s+uid\s+won't be saved/)
  })

  it('flags a typed column that is the DN column', () => {
    const w = make([{ column: 'dn', attribute: '' }], { editableColumns: true, dnColumn: 'dn' })
    expect(statuses(w)).toEqual(['dn'])
    expect(w.text()).toContain("This is the DN column; it isn't mapped")
  })

  it('renders editable column names, and a remove button, without a sample file', async () => {
    const w = make([{ column: 'Dept', attribute: '' }], { editableColumns: true })
    await w.find('input[aria-label="CSV column for row 1"]').setValue('Department')
    expect(w.props('rows')[0].column).toBe('Department')
    await w.find('button[aria-label="Remove Department"]').trigger('click')
    expect(w.emitted('remove')).toEqual([[0]])
  })

  it('offers remove only for kept columns the sample file lacks', () => {
    const w = make([
      { column: 'uid', attribute: 'cn' }, { column: 'Old', attribute: 'sn', notInSample: true },
    ], { fromSample: true })
    expect(w.findAll('button[aria-label^="Remove"]').map(b => b.attributes('aria-label'))).toEqual(['Remove Old'])
    expect(w.text()).toContain('Not in the sample file')
  })

  it('shows first-row values as hints when the sample has no header row', () => {
    const w = make([{ column: 'Column 1', attribute: '' }], { samples: { 'Column 1': 'alice' } })
    expect(w.find('[data-column="Column 1"]').text()).toMatch(/Column 1\s+e\.g\. alice/)
  })
})
