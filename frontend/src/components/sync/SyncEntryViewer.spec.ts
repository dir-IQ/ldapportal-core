// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SyncEntryViewer, { type EntrySide } from './SyncEntryViewer.vue'

vi.mock('@/api/browse', () => ({ browse: vi.fn() }))
import { browse } from '@/api/browse'

const SOURCE: EntrySide = { key: 'source', label: 'Source', directoryId: 'dir-src', dn: 'uid=js,ou=staff,dc=corp' }
const TARGET: EntrySide = { key: 'target', label: 'Target', directoryId: 'dir-hr', dn: 'uid=js,ou=people,dc=hr',
  missingText: 'Not on the target: that’s the drift.' }

/** browse() answering per DN: attributes, or an error to throw. */
function entries(byDn: Record<string, Record<string, string[]> | Error>) {
  vi.mocked(browse).mockImplementation(((_dir: string, dn: string) => {
    const v = byDn[dn]
    return v instanceof Error ? Promise.reject(v) : Promise.resolve({ data: { dn, attributes: v ?? {} } })
  }) as never)
}

function mountViewer(props: Record<string, unknown> = {}) {
  return mount(SyncEntryViewer, {
    props: { show: true, title: 'Content drift: uid=js', sides: [SOURCE, TARGET], ...props },
    global: { stubs: {
      AppModal: { props: ['modelValue', 'title'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
    } },
  })
}
/** A row's cells (the attribute, then each side), each as its text. */
const rowText = (w: ReturnType<typeof mountViewer>, key: string) =>
  w.find(`[data-testid="entry-attr-${key}"]`).findAll('th, td').map(c => c.text().replace(/\s+/g, ' ')).join(' | ')

describe('SyncEntryViewer', () => {
  beforeEach(() => vi.clearAllMocks())

  it('reads each side live and shows them attribute by attribute, flagged ones first', async () => {
    entries({
      'uid=js,ou=staff,dc=corp': { cn: ['Jo Smith'], title: ['Senior'], objectClass: ['person'], userPassword: ['{SSHA}x'] },
      'uid=js,ou=people,dc=hr': { cn: ['Jo Smith'], title: ['Junior'], objectClass: ['person'], mail: ['js@hr'] },
    })
    const w = mountViewer({ flaggedAttributes: ['Title', 'manager'] })
    await flushPromises()

    expect(browse).toHaveBeenCalledWith('dir-src', 'uid=js,ou=staff,dc=corp', { limit: 1 })
    expect(browse).toHaveBeenCalledWith('dir-hr', 'uid=js,ou=people,dc=hr', { limit: 1 })
    const keys = w.findAll('tbody tr').map(r => r.attributes('data-testid'))
    // Flagged (matched in any case, including one neither side has), then objectClass, then A–Z.
    expect(keys).toEqual(['entry-attr-manager', 'entry-attr-title', 'entry-attr-objectclass', 'entry-attr-cn',
      'entry-attr-mail', 'entry-attr-userpassword'])
    expect(rowText(w, 'title')).toBe('title (flagged) | Senior | Junior')
    expect(w.find('[data-testid="entry-attr-title"]').classes()).toContain('bg-amber-50')
    expect(rowText(w, 'manager')).toBe('manager (flagged) | (none) | (none)')
    expect(rowText(w, 'mail')).toBe('mail | (none) | js@hr')
    // Password material is never shown.
    expect(rowText(w, 'userpassword')).toBe('userPassword | (hidden) | (none)')
    expect(w.text()).not.toContain('{SSHA}')
  })

  it('narrows to the flagged attributes on request', async () => {
    entries({ 'uid=js,ou=staff,dc=corp': { cn: ['Jo'], title: ['A'] }, 'uid=js,ou=people,dc=hr': { cn: ['Jo'], title: ['B'] } })
    const w = mountViewer({ flaggedAttributes: ['title'] })
    await flushPromises()
    await w.find('input[type="checkbox"]').setValue(true)
    expect(w.findAll('tbody tr').map(r => r.attributes('data-testid'))).toEqual(['entry-attr-title'])
  })

  it('says when a side has no entry, and why a read failed', async () => {
    entries({
      'uid=js,ou=staff,dc=corp': { cn: ['Jo'] },
      'uid=js,ou=people,dc=hr': {}, // read fine, nothing there
    })
    let w = mountViewer()
    await flushPromises()
    expect(w.find('[data-testid="entry-side-target"]').text()).toContain('Not on the target: that’s the drift.')
    // Only the side that exists gets a column.
    expect(w.findAll('thead th').map(t => t.text())).toEqual(['Attribute', 'Source'])
    w.unmount()

    entries({
      'uid=js,ou=staff,dc=corp': Object.assign(new Error('x'), { response: { status: 400,
        data: { detail: 'LDAP error: No Such Object' } } }),
      'uid=js,ou=people,dc=hr': Object.assign(new Error('x'), { response: { status: 502,
        data: { detail: 'Directory unreachable' } } }),
    })
    w = mountViewer()
    await flushPromises()
    expect(w.find('[data-testid="entry-side-source"]').text()).toContain('No entry here.')
    expect(w.find('[data-testid="entry-side-target"] [role="alert"]').text()).toBe('Directory unreachable')
    expect(w.find('[data-testid="entry-attributes"]').exists()).toBe(false)
  })

  it('links each side to the Directory Browser in a new tab, for editing', async () => {
    entries({ 'uid=js,ou=staff,dc=corp': { cn: ['Jo'] }, 'uid=js,ou=people,dc=hr': { cn: ['Jo'] } })
    const w = mountViewer()
    await flushPromises()
    const a = w.find('[data-testid="entry-side-target"] a')
    expect(a.attributes('target')).toBe('_blank')
    expect(a.attributes('rel')).toBe('noopener')
    expect(a.attributes('href')).toBe('/superadmin/directory-browser?dir=dir-hr&dn=uid%3Djs%2Cou%3Dpeople%2Cdc%3Dhr')
  })

  it('doesn\'t read a side with no DN or directory, and closes on request', async () => {
    entries({})
    const w = mountViewer({ sides: [{ key: 'source', label: 'Source', directoryId: null, dn: 'uid=x',
      missingText: 'Unknown directory.' }] })
    await flushPromises()
    expect(browse).not.toHaveBeenCalled()
    expect(w.text()).toContain('Unknown directory.')
    await w.find('button').trigger('click')
    expect(w.emitted('close')).toHaveLength(1)
  })
})
