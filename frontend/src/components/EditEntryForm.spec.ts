// SPDX-License-Identifier: Apache-2.0
/**
 * Regression: EditEntryForm locks objectClass (and the RDN attribute) against
 * editing, but the READONLY_ATTRS guard used an exact-case match while
 * attribute keys reach the client in whatever case their source used — the
 * browse endpoint passes the directory's base name through
 * (LdapBrowseService), LdapEntryMapper lower-cases. An entry keyed
 * `objectclass` therefore slipped the guard entirely and rendered as editable
 * inputs whose save would emit a REPLACE of the whole objectClass list.
 *
 * These pin the guard as case-insensitive in both directions.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  updateEntry: vi.fn(),
  browseObjectClassesBulk: vi.fn(),
}))

vi.mock('@/api/browse', () => ({
  updateEntry: api.updateEntry,
  browseObjectClassesBulk: api.browseObjectClassesBulk,
}))

import EditEntryForm from './EditEntryForm.vue'

const stubs = { CopyButton: true }

function mountForm(attributes: Record<string, string[]>) {
  return mount(EditEntryForm, {
    props: { directoryId: 'd1', dn: 'uid=jdoe,ou=people,dc=x', attributes },
    global: { stubs },
  })
}

/** The row whose label starts with `name` — labels carry the attribute name. */
function row(wrapper: ReturnType<typeof mountForm>, name: string) {
  return wrapper.findAll('label').find(l => l.text().startsWith(name))!
}

describe('EditEntryForm objectClass lock', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.browseObjectClassesBulk.mockResolvedValue({ data: { required: [], optional: [] } })
    api.updateEntry.mockResolvedValue({ data: { dn: 'uid=jdoe,ou=people,dc=x', attributes: {} } })
  })

  // The case the browse endpoint actually delivers today.
  it('marks objectClass read-only when the key keeps the directory casing', async () => {
    const wrapper = mountForm({ objectClass: ['top', 'inetOrgPerson'], mail: ['j@x.com'] })
    await flushPromises()
    expect(row(wrapper, 'objectClass').text()).toContain('(read-only)')
  })

  // The case that used to slip through: same attribute, lower-cased key.
  it('marks objectclass read-only when the key arrives lower-cased', async () => {
    const wrapper = mountForm({ objectclass: ['top', 'inetOrgPerson'], mail: ['j@x.com'] })
    await flushPromises()
    expect(row(wrapper, 'objectclass').text()).toContain('(read-only)')
  })

  it('renders no input for a lower-cased objectclass and keeps other attrs editable', async () => {
    const wrapper = mountForm({ objectclass: ['top'], mail: ['j@x.com'] })
    await flushPromises()
    // One text input only — mail. objectclass renders as plain text.
    const inputs = wrapper.findAll('input')
    expect(inputs).toHaveLength(1)
    expect((inputs[0].element as HTMLInputElement).value).toBe('j@x.com')
  })

  it('never emits a modification against a lower-cased objectclass', async () => {
    const wrapper = mountForm({ objectclass: ['top'], mail: ['j@x.com'] })
    await flushPromises()
    await wrapper.find('input').setValue('new@x.com')
    await wrapper.findAll('button').find(b => b.text() === 'Save Entry')!.trigger('click')
    await flushPromises()
    expect(api.updateEntry).toHaveBeenCalledTimes(1)
    expect(api.updateEntry.mock.calls[0][2]).toEqual({
      modifications: [{ operation: 'REPLACE', attribute: 'mail', values: ['new@x.com'] }],
    })
  })

  it('keeps objectClass out of the add-attribute picker whatever case the schema uses', async () => {
    // Schema and entry disagree on casing, so the picker's "already on the
    // entry" check can't mask the guard: only the READONLY filter excludes it.
    api.browseObjectClassesBulk.mockResolvedValue({
      data: { required: [], optional: ['description', 'objectclass'] },
    })
    const wrapper = mountForm({ objectClass: ['top'] })
    await flushPromises()
    const options = wrapper.findAll('option').map(o => o.text())
    expect(options).toContain('description')
    expect(options.some(o => o.toLowerCase() === 'objectclass')).toBe(false)
  })

  // The RDN half of the guard already lower-cased both sides — pin it so the
  // shared helper keeps covering it.
  it('still locks the RDN attribute regardless of case', async () => {
    const wrapper = mountForm({ UID: ['jdoe'], mail: ['j@x.com'] })
    await flushPromises()
    expect(row(wrapper, 'UID').text()).toContain('(read-only)')
  })
})
