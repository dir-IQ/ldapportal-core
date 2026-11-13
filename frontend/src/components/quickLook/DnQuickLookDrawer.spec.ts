// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { AxiosResponse } from 'axios'
import DnQuickLookDrawer from './DnQuickLookDrawer.vue'
import { useQuickLookStore } from '@/stores/quickLook'
import { browse, directoryBrowse } from '@/api/browse'

const authState = vi.hoisted(() => ({ isSuperadmin: true }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authState }))
vi.mock('@/api/browse', () => ({ browse: vi.fn(), directoryBrowse: vi.fn() }))

const SEC = 'secAuthority=Default,o=ibm,c=us'
const USER = 'uid=alice,ou=people,dc=example,dc=com'
const GROUP = 'cn=admins,ou=groups,dc=example,dc=com'

const entries: Record<string, Record<string, string[]>> = {
  [USER]: { objectClass: ['top', 'inetOrgPerson'], uid: ['alice'], memberOf: [GROUP], secDN: [SEC] },
  [GROUP]: { objectClass: ['groupOfNames'], cn: ['admins'], member: [USER] },
}

function ok<T>(data: T): AxiosResponse<T> {
  return { data } as unknown as AxiosResponse<T>
}

async function mountDrawer() {
  const w = mount(DnQuickLookDrawer, {
    global: {
      stubs: {
        teleport: true,
        CopyButton: true,
        RouterLink: { props: ['to'], template: '<a data-router-link :data-to="JSON.stringify(to)"><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return w
}

describe('DnQuickLookDrawer', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    authState.isSuperadmin = true
    vi.mocked(browse).mockReset()
    vi.mocked(directoryBrowse).mockReset()
    const impl = async (_dir: string, dn?: string) => ok({ dn, attributes: entries[dn ?? ''] ?? {} })
    vi.mocked(browse).mockImplementation(impl)
    vi.mocked(directoryBrowse).mockImplementation(impl)
  })

  it('renders nothing until opened, and has no backdrop when open (page stays usable)', async () => {
    const w = await mountDrawer()
    expect(w.find('[data-testid="dn-quick-look"]').exists()).toBe(false)

    useQuickLookStore().open('d1', USER)
    await flushPromises()

    const panel = w.get('[data-testid="dn-quick-look"]')
    expect(panel.element.tagName).toBe('ASIDE')
    expect(panel.attributes('aria-modal')).toBeUndefined()
    expect(w.find('.bg-black\\/40').exists()).toBe(false)
  })

  it('fetches only the entry (limit 1) and shows its attributes, DN and object classes', async () => {
    const w = await mountDrawer()
    useQuickLookStore().open('d1', USER)
    await flushPromises()

    expect(browse).toHaveBeenCalledWith('d1', USER, { limit: 1 })
    expect(w.get('h2').text()).toBe('alice')
    expect(w.get('[data-testid="quick-look-dn"]').text()).toBe(USER)
    expect(w.text()).toContain('inetOrgPerson')
    // DN-valued attributes are links (secDN, memberOf); plain ones aren't.
    const links = w.findAll('.dn-link').map(l => l.text())
    expect(links).toEqual(expect.arrayContaining([GROUP, SEC]))
    expect(links).not.toContain('alice')
  })

  it('uses the directory-scoped browse API for admins below superadmin', async () => {
    authState.isSuperadmin = false
    const w = await mountDrawer()
    useQuickLookStore().open('d1', USER)
    await flushPromises()

    expect(directoryBrowse).toHaveBeenCalledWith('d1', USER, { limit: 1 })
    expect(browse).not.toHaveBeenCalled()
    // No superadmin-only "Open in Directory Browser" link.
    expect(w.find('[data-router-link]').exists()).toBe(false)
  })

  it('follows a DN inside the drawer and steps back', async () => {
    const w = await mountDrawer()
    const store = useQuickLookStore()
    store.open('d1', USER)
    await flushPromises()

    await w.findAll('.dn-link').find(l => l.text() === GROUP)!.trigger('click')
    await flushPromises()
    expect(w.get('h2').text()).toBe('admins')
    expect(w.get('nav[aria-label="Quick look trail"]').text()).toContain('alice')

    await w.get('button[aria-label="Back"]').trigger('click')
    await flushPromises()
    expect(w.get('h2').text()).toBe('alice')
    expect(w.find('button[aria-label="Back"]').exists()).toBe(false)
  })

  it('reports an entry that is not there', async () => {
    const w = await mountDrawer()
    useQuickLookStore().open('d1', 'uid=ghost,ou=people,dc=example,dc=com')
    await flushPromises()
    expect(w.text()).toContain('No entry with this DN was found')
  })

  it('explains a 403 as out of scope', async () => {
    vi.mocked(browse).mockRejectedValue({ response: { status: 403 } })
    const w = await mountDrawer()
    useQuickLookStore().open('d1', USER)
    await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('outside the part of the directory you can view')
  })

  it('closes on Escape and on the close button', async () => {
    const w = await mountDrawer()
    const store = useQuickLookStore()
    store.open('d1', USER)
    await flushPromises()
    await w.get('[data-testid="dn-quick-look"]').trigger('keydown', { key: 'Escape' })
    expect(store.isOpen).toBe(false)

    store.open('d1', USER)
    await flushPromises()
    await w.get('button[aria-label="Close quick look"]').trigger('click')
    expect(store.isOpen).toBe(false)
  })

  it('offers the full Directory Browser at the entry and closes when taken', async () => {
    const w = await mountDrawer()
    const store = useQuickLookStore()
    store.open('d1', USER)
    await flushPromises()
    const link = w.get('[data-router-link]')
    expect(JSON.parse(link.attributes('data-to')!)).toEqual({ name: 'directoryBrowser', query: { dir: 'd1', dn: USER } })
    await link.trigger('click')
    expect(store.isOpen).toBe(false)
  })
})
