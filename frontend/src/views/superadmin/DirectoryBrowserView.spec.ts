// SPDX-License-Identifier: Apache-2.0
/**
 * Tests the Directory Browser's branch paging and filter wiring:
 *
 * - every tree listing asks the server for a bounded page (BRANCH_PAGE_SIZE)
 *   rather than the whole branch;
 * - typing in the branch-filter box re-lists the selected node's children
 *   with that filter after a debounce, and Enter applies it immediately;
 * - Load all on a truncated branch re-requests it unbounded (limit 0).
 *
 * DnTree is mounted for real so the wiring is exercised end to end; the
 * entry-detail side panel's heavier children are stubbed.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { AxiosResponse } from 'axios'
import DirectoryBrowserView from './DirectoryBrowserView.vue'
import { browse } from '@/api/browse'
import { listDirectories } from '@/api/directories'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
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
      return ok(peoplePage({ filter: opts.filter, all: opts.limit === 0 }))
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
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
