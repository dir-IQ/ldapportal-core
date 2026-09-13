// SPDX-License-Identifier: Apache-2.0
/**
 * Tests the Directory Browser's branch paging and filter wiring:
 *
 * - every tree listing asks the server for a bounded page (BRANCH_PAGE_SIZE)
 *   rather than the whole branch;
 * - typing in the branch-filter box re-lists the selected node's children
 *   with that filter after a debounce, and Enter applies it immediately;
 * - Load all on a truncated branch re-requests it unbounded (limit 0);
 * - the selection is mirrored into ?dir=&dn= (replace on landing, push
 *   between entries, nothing when the URL already says so);
 * - after a write the affected branch is re-listed on the tree's own terms
 *   rather than from the listing the mutation returned.
 *
 * DnTree is mounted for real so the wiring is exercised end to end; the
 * entry-detail side panel's heavier children are stubbed.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { AxiosResponse } from 'axios'
import DirectoryBrowserView from './DirectoryBrowserView.vue'
import { browse, deleteEntry } from '@/api/browse'
import { listDirectories } from '@/api/directories'

// Mutable so each test can set the ?dir=&dn= deep-link before mounting.
const route = vi.hoisted(() => ({ query: {} as Record<string, string> }))
// The view mirrors its selection into the URL; capture those writes.
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => router }))
vi.mock('@/api/directories', () => ({ listDirectories: vi.fn() }))
vi.mock('@/api/browse', () => ({
  browse: vi.fn(),
  deleteEntry: vi.fn(),
  moveEntry: vi.fn(),
  renameEntry: vi.fn(),
  exportLdif: vi.fn(),
}))

function ok<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: {} } as unknown as AxiosResponse<T>
}

const DIR = 'dir-1'
const BASE = 'dc=example,dc=com'
const PEOPLE = 'ou=people,' + BASE
const ALICE_DN = 'uid=alice,' + PEOPLE

const rootPage = {
  dn: BASE,
  attributes: { objectClass: ['domain'] },
  children: [{ dn: PEOPLE, rdn: 'ou=people', hasChildren: true }],
  truncated: false,
  childCount: 1,
  childCountApproximate: false,
}

function peoplePage(opts: { filter?: string, all?: boolean } = {}) {
  const alice = { dn: 'uid=alice,' + PEOPLE, rdn: 'uid=alice', hasChildren: false }
  const bob = { dn: 'uid=bob,' + PEOPLE, rdn: 'uid=bob', hasChildren: false }
  if (opts.filter) {
    return { dn: PEOPLE, attributes: { ou: ['people'] }, children: [alice], truncated: false, childCount: null }
  }
  return {
    dn: PEOPLE,
    attributes: { ou: ['people'] },
    children: opts.all ? [alice, bob] : [alice],
    truncated: !opts.all,
    childCount: 2,
    childCountApproximate: false,
  }
}

// GroupMembersPanel takes an `attributes` prop, which collides with the
// auto-stub's own `attributes` getter — give it an explicit empty stub.
const stubs = {
  CreateEntryForm: true,
  EditEntryForm: true,
  GroupMembersPanel: { props: ['attributes'], template: '<div />' },
  LdifImportModal: true,
  DnPicker: true,
  CopyButton: true,
}

const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

/**
 * The tree <li> for a given RDN. Ancestors' <li>s contain the descendant's
 * text too, so take the innermost (last) match rather than the first.
 */
function treeRow(wrapper: Awaited<ReturnType<typeof mountView>>, rdn: string) {
  const rows = wrapper.findAll('.dn-tree li').filter(li => li.text().includes(rdn))
  return rows[rows.length - 1]
}

async function mountView() {
  const wrapper = mount(DirectoryBrowserView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('DirectoryBrowserView branch paging + filter', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listDirectories).mockResolvedValue(ok([{ id: DIR, displayName: 'Example', directoryType: 'OPENLDAP' }]))
    vi.mocked(browse).mockImplementation(async (_dir: string, dn?: string, opts: { filter?: string, limit?: number } = {}) => {
      if (!dn || dn === BASE) return ok(rootPage)
      if (dn === ALICE_DN) return ok({ dn: ALICE_DN, attributes: { uid: ['alice'] }, children: [] })
      return ok(peoplePage({ filter: opts.filter, all: opts.limit === 0 }))
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
    route.query = {}
  })

  it('mirrors the selection into the URL: replace on landing, push between entries', async () => {
    const wrapper = await mountView()
    // Landing with no ?dir= fixes the URL in place (no history entry).
    expect(router.replace).toHaveBeenCalledWith({ query: { dir: DIR, dn: undefined } })
    expect(router.push).not.toHaveBeenCalled()
    // Pretend the router applied it, as it would.
    route.query = { dir: DIR }

    await wrapper.find('button[aria-label="Toggle children"]').trigger('click')
    await flushPromises()
    await treeRow(wrapper, 'ou=people').find('div').trigger('click')
    await flushPromises()

    expect(router.push).toHaveBeenCalledWith({ query: { dir: DIR, dn: PEOPLE } })
  })

  it('does not rewrite the URL when a deep-link already says where we are', async () => {
    route.query = { dir: DIR, dn: ALICE_DN }
    await mountView()
    await flushPromises()

    expect(router.push).not.toHaveBeenCalled()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('re-lists the affected branch after a delete instead of trusting the returned listing', async () => {
    const wrapper = await mountView()
    await wrapper.find('button[aria-label="Toggle children"]').trigger('click')
    await flushPromises()
    await treeRow(wrapper, 'ou=people').find('div').trigger('click') // select + expand ou=people
    await flushPromises()
    // Select alice, the entry we are about to delete.
    await treeRow(wrapper, 'uid=alice').find('div').trigger('click')
    await flushPromises()
    vi.mocked(browse).mockClear()

    // The mutation response carries a stale, unbounded courtesy listing
    // (including an entry the tree must not pick up from it).
    vi.mocked(deleteEntry).mockResolvedValue(ok({
      dn: PEOPLE,
      attributes: { ou: ['people'] },
      children: [{ dn: 'uid=ghost,' + PEOPLE, rdn: 'uid=ghost', hasChildren: false }],
    }))
    const vm = wrapper.vm as unknown as { onDeleteConfirmed: () => Promise<void> }
    await vm.onDeleteConfirmed()
    await flushPromises()

    expect(deleteEntry).toHaveBeenCalledWith(DIR, ALICE_DN, false, false)
    // The branch is re-fetched with the tree's page settings…
    expect(browse).toHaveBeenCalledWith(DIR, PEOPLE, { filter: undefined, limit: 500 })
    // …and rendered from that fetch, not from the mutation response.
    expect(wrapper.text()).not.toContain('uid=ghost')
    expect(wrapper.text()).toContain('uid=alice')
  })

  it('opens the tree down to a ?dn= deep-link and selects the entry', async () => {
    route.query = { dir: DIR, dn: ALICE_DN }
    const wrapper = await mountView()
    await flushPromises()

    // Root page, then the parent branch (bounded), then the entry itself
    // (detail only — the smallest page, its children are ignored).
    expect(browse).toHaveBeenCalledWith(DIR, undefined, { limit: 500 })
    expect(browse).toHaveBeenCalledWith(DIR, PEOPLE, { filter: undefined, limit: 500 })
    expect(browse).toHaveBeenCalledWith(DIR, ALICE_DN, { limit: 1 })

    const selected = wrapper.findAll('.dn-tree li > div').find(d => d.classes().includes('bg-blue-100'))
    expect(selected?.text()).toContain('uid=alice')
    expect(wrapper.find('input[aria-label="Filter children of the selected entry"]').attributes('placeholder'))
      .toBe('Filter children of uid=alice')
  })

  it('still shows the entry when the deep-linked DN is outside the base', async () => {
    const outside = 'uid=x,dc=elsewhere'
    vi.mocked(browse).mockImplementation(async (_dir: string, dn?: string) => {
      if (!dn || dn === BASE) return ok(rootPage)
      if (dn === outside) return ok({ dn: outside, attributes: { uid: ['x'] }, children: [] })
      return ok(peoplePage())
    })
    route.query = { dir: DIR, dn: outside }
    await mountView()
    await flushPromises()

    expect(browse).toHaveBeenCalledWith(DIR, outside, { limit: 1 })
    expect(browse).not.toHaveBeenCalledWith(DIR, PEOPLE, expect.anything())
  })

  it('loads the root as a bounded page', async () => {
    await mountView()
    expect(browse).toHaveBeenCalledWith(DIR, undefined, { limit: 500 })
  })

  it('expanding a branch asks for a bounded page and offers Load all when truncated', async () => {
    const wrapper = await mountView()

    await wrapper.find('button[aria-label="Toggle children"]').trigger('click') // root
    await flushPromises()
    await treeRow(wrapper, 'ou=people').find('button[aria-label="Toggle children"]').trigger('click')
    await flushPromises()

    expect(browse).toHaveBeenCalledWith(DIR, PEOPLE, { filter: undefined, limit: 500 })
    expect(wrapper.text()).toContain('Showing 1 of 2 entries')
    expect(wrapper.text()).not.toContain('uid=bob')

    await wrapper.findAll('button').find(b => b.text() === 'Load all')!.trigger('click')
    await flushPromises()

    expect(browse).toHaveBeenCalledWith(DIR, PEOPLE, { filter: undefined, limit: 0 })
    expect(wrapper.text()).toContain('uid=bob')
  })

  it('typing a branch filter re-lists the selected node after a debounce', async () => {
    const wrapper = await mountView()
    // Select ou=people (click on the row selects + auto-expands it).
    await wrapper.find('button[aria-label="Toggle children"]').trigger('click')
    await flushPromises()
    await treeRow(wrapper, 'ou=people').find('div').trigger('click')
    await flushPromises()
    vi.mocked(browse).mockClear()

    const input = wrapper.find('input[aria-label="Filter children of the selected entry"]')
    expect(input.attributes('placeholder')).toBe('Filter children of ou=people')
    await input.setValue('ali')

    // Not yet — debounced.
    await flushPromises()
    expect(browse).not.toHaveBeenCalled()
    await wait(350)
    await flushPromises()

    expect(browse).toHaveBeenCalledWith(DIR, PEOPLE, { filter: 'ali', limit: 500 })
    expect(wrapper.text()).toContain('Filtered by "ali"')

    // Enter applies immediately, without waiting out the debounce.
    await input.setValue('bo')
    await input.trigger('keyup.enter')
    await flushPromises()
    expect(browse).toHaveBeenCalledWith(DIR, PEOPLE, { filter: 'bo', limit: 500 })
  })
})
