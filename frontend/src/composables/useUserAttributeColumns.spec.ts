// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, afterEach } from 'vitest'
import { nextTick, ref } from 'vue'
import {
  buildUserAttributeColumns, exportAttributeNames, isSecretAttribute, attributeDisplayName,
  useOperationalAttributesToggle,
} from './useUserAttributeColumns'

describe('buildUserAttributeColumns', () => {
  it('orders dn, the present defaults, groups, then the rest A–Z (hidden)', () => {
    const cols = buildUserAttributeColumns(
      ['uid', 'zip', 'cn', 'Mail', 'apple', 'sn'], { groupsKey: 'groups' })

    expect(cols.map(c => c.key)).toEqual(['dn', 'cn', 'sn', 'Mail', 'uid', 'groups', 'apple', 'zip'])
    expect(cols.filter(c => c.defaultHidden).map(c => c.key)).toEqual(['apple', 'zip'])
    expect(cols[0]).toMatchObject({ key: 'dn', label: 'dn', alwaysVisible: true, lockPosition: true })
  })

  it('drops secrets, raw membership attributes and a duplicate dn', () => {
    const cols = buildUserAttributeColumns(
      ['dn', 'cn', 'userPassword', 'unicodepwd;binary', 'memberOf', 'ismemberof', 'mail'])

    expect(cols.map(c => c.key)).toEqual(['dn', 'cn', 'mail'])
  })

  it('heads columns by schema casing, then known default casing, then as given', () => {
    const cols = buildUserAttributeColumns(['givenname', 'employeenumber', 'carlicense'], {
      schemaNames: new Map([['employeenumber', 'employeeNumber']]),
    })

    expect(cols.map(c => [c.key, c.label])).toEqual([
      ['dn', 'dn'], ['givenname', 'givenName'], ['carlicense', 'carlicense'], ['employeenumber', 'employeeNumber'],
    ])
  })

  it('honours a label override and per-column defaults', () => {
    const cols = buildUserAttributeColumns(['isva.seclogin'], {
      labelOverride: k => (k.startsWith('isva.') ? 'ivia.' + k.slice(5) : undefined),
      columnDefaults: { defaultWidth: 180 },
      dnColumn: { defaultWidth: 360 },
    })

    expect(cols).toEqual([
      expect.objectContaining({ key: 'dn', defaultWidth: 360 }),
      expect.objectContaining({ key: 'isva.seclogin', label: 'ivia.seclogin', defaultWidth: 180 }),
    ])
  })

  it('can start the groups column hidden', () => {
    const cols = buildUserAttributeColumns(['cn'], { groupsKey: '__groups', groupsDefaultHidden: true })
    expect(cols.find(c => c.key === '__groups')).toMatchObject({ label: 'groups', defaultHidden: true, sortable: false })
  })
})

describe('helpers', () => {
  it('isSecretAttribute ignores case and attribute options', () => {
    expect(isSecretAttribute('userPassword')).toBe(true)
    expect(isSecretAttribute('unicodePwd;binary')).toBe(true)
    expect(isSecretAttribute('mail')).toBe(false)
  })

  it('attributeDisplayName prefers the schema spelling', () => {
    expect(attributeDisplayName('displayname')).toBe('displayName')
    expect(attributeDisplayName('x', new Map([['x', 'X']]))).toBe('X')
  })

  it('exportAttributeNames follows the visible order by heading and skips non-attributes', () => {
    const cols = buildUserAttributeColumns(['givenname', 'cn'], { groupsKey: 'g' })
    expect(exportAttributeNames(['dn', 'givenname', 'g', 'cn', 'unknown'], cols, ['dn', 'g']))
      .toEqual(['givenName', 'cn'])
  })
})

describe('useOperationalAttributesToggle', () => {
  afterEach(() => localStorage.clear())

  it('starts off, remembers the choice per table key, and follows a changing key', async () => {
    const key = ref('a')
    const on = useOperationalAttributesToggle(key)
    expect(on.value).toBe(false)

    on.value = true
    await nextTick()
    expect(localStorage.getItem('ldapportal:operational-attributes:a')).toBe('true')

    key.value = 'b'
    await nextTick()
    expect(on.value).toBe(false)
    expect(useOperationalAttributesToggle('a').value).toBe(true)
  })
})
