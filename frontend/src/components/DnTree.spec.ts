// SPDX-License-Identifier: Apache-2.0
/**
 * Tests DnTree's exposed API and page handling:
 *
 * - refreshNode — the mechanism the Directory Browser uses to update the
 *   tree after a delete/rename/move or a manual branch refresh. The key
 *   regression: it must match the target node by a normalized DN, so a
 *   formatting-only difference (case / whitespace around the RDN separator)
 *   between the server's node DN and the DN the app hands back still
 *   updates the tree instead of silently leaving it stale.
 * - Capped pages — a truncated listing shows a "Showing N of M" footer with
 *   Load all, which re-requests the branch with `all: true`.
 * - filterNode / getFilter — the branch filter re-lists a node's children
 *   with a filter and reports it in the footer, with Clear to drop it.
 */
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DnTree, { type ChildPage, type LoadChildrenFn } from './DnTree.vue'

const ALICE = { dn: 'uid=alice,ou=people,dc=x', rdn: 'uid=alice', hasChildren: false }
const BOB = { dn: 'uid=bob,ou=people,dc=x', rdn: 'uid=bob', hasChildren: false }

function mountTree() {
  return mount(DnTree, {
    props: {
      nodes: [{ dn: 'ou=people,dc=x', rdn: 'ou=people', hasChildren: true }],
      selectedDn: '',
      loadChildren: async () => [ALICE, BOB],
    },
  })
}

async function expandRoot(wrapper: ReturnType<typeof mountTree>) {
  await wrapper.find('button[aria-label="Toggle children"]').trigger('click')
  await flushPromises()
}

describe('DnTree.refreshNode', () => {
  it('removes a deleted child when refreshed with the new listing', async () => {
    const wrapper = mountTree()
    const vm = wrapper.vm as unknown as { refreshNode: (dn: string, c: unknown[]) => boolean }
    await expandRoot(wrapper)
    expect(wrapper.text()).toContain('uid=alice')
    expect(wrapper.text()).toContain('uid=bob')

    // Alice deleted → parent relists with bob only.
    vm.refreshNode('ou=people,dc=x', [BOB])
    await flushPromises()

    expect(wrapper.text()).not.toContain('uid=alice')
    expect(wrapper.text()).toContain('uid=bob')
  })

  it('matches the target node by normalized DN (case / spacing variant)', async () => {
    const wrapper = mountTree()
    const vm = wrapper.vm as unknown as { refreshNode: (dn: string, c: unknown[]) => boolean }
    await expandRoot(wrapper)
    expect(wrapper.text()).toContain('uid=alice')

    // A formatting variant of the same DN — uppercase + space after comma —
    // as a backend-assembled parent DN might arrive. Strict equality would
    // miss it and leave alice in the tree; normalized matching removes her.
    vm.refreshNode('OU=People, DC=x', [BOB])
    await flushPromises()

    expect(wrapper.text()).not.toContain('uid=alice')
    expect(wrapper.text()).toContain('uid=bob')
  })

  it('drops the child list when the last child is removed', async () => {
    const wrapper = mountTree()
    const vm = wrapper.vm as unknown as { refreshNode: (dn: string, c: unknown[]) => boolean }
    await expandRoot(wrapper)
    expect(wrapper.text()).toContain('uid=alice')

    vm.refreshNode('ou=people,dc=x', [])
    await flushPromises()

    expect(wrapper.text()).not.toContain('uid=alice')
    expect(wrapper.text()).not.toContain('uid=bob')
  })
})

// ── Capped pages, load-all, branch filter ────────────────────────────────

const PEOPLE = 'ou=people,dc=x'

type TreeApi = {
  filterNode: (dn: string, filter: string) => Promise<boolean>
  loadAll: (dn: string) => Promise<boolean>
  getFilter: (dn: string) => string | null
  revealNode: (ancestors: string[], targetDn: string) => Promise<boolean>
  reloadNode: (dn: string) => Promise<boolean>
}

function mountWith(loadChildren: LoadChildrenFn) {
  const wrapper = mount(DnTree, {
    props: {
      nodes: [{ dn: PEOPLE, rdn: 'ou=people', hasChildren: true }],
      selectedDn: '',
      loadChildren,
    },
  })
  return { wrapper, api: wrapper.vm as unknown as TreeApi }
}

const footer = (w: ReturnType<typeof mountTree>) => w.find('[data-testid="dn-tree-footer"]')
const buttonNamed = (w: ReturnType<typeof mountTree>, label: string) =>
  w.findAll('button').find(b => b.text() === label)

describe('DnTree paging', () => {
  it('treats a plain array as a complete listing — no footer', async () => {
    const { wrapper } = mountWith(async () => [ALICE, BOB])
    await expandRoot(wrapper)

    expect(footer(wrapper).exists()).toBe(false)
    expect(wrapper.text()).toContain('2') // badge = shown count
  })

  it('shows "Showing N of M" with Load all for a truncated page', async () => {
    const load = vi.fn<LoadChildrenFn>(async () => ({
      children: [ALICE, BOB], truncated: true, childCount: 40,
    }))
    const { wrapper } = mountWith(load)
    await expandRoot(wrapper)

    expect(footer(wrapper).text()).toContain('Showing 2 of 40 entries')
    expect(buttonNamed(wrapper, 'Load all')).toBeDefined()
    expect(wrapper.text()).toContain('40') // badge = known total
  })

  it('labels an approximate count and falls back when none is known', async () => {
    const approx = mountWith(async () => ({
      children: [ALICE], truncated: true, childCount: 40, childCountApproximate: true,
    }))
    await expandRoot(approx.wrapper)
    expect(footer(approx.wrapper).text()).toContain('Showing 1 of about 40 entries')
    expect(approx.wrapper.text()).toContain('~40')

    const unknown = mountWith(async () => ({ children: [ALICE, BOB], truncated: true }))
    await expandRoot(unknown.wrapper)
    expect(footer(unknown.wrapper).text()).toContain('Showing the first 2 entries')
    expect(unknown.wrapper.text()).toContain('2+')
  })

  it('Load all re-requests the branch with all: true and clears the footer', async () => {
    const load = vi.fn<LoadChildrenFn>()
    load.mockResolvedValueOnce({ children: [ALICE], truncated: true, childCount: 2 })
    load.mockResolvedValueOnce({ children: [ALICE, BOB], truncated: false, childCount: 2 })
    const { wrapper } = mountWith(load)
    await expandRoot(wrapper)
    expect(wrapper.text()).not.toContain('uid=bob')

    await buttonNamed(wrapper, 'Load all')!.trigger('click')
    await flushPromises()

    expect(load).toHaveBeenLastCalledWith(PEOPLE, { all: true, filter: undefined })
    expect(wrapper.text()).toContain('uid=bob')
    expect(footer(wrapper).exists()).toBe(false)
  })
})

describe('DnTree branch filter', () => {
  it('filterNode re-lists the node with the filter and shows a footer with Clear', async () => {
    const load = vi.fn<LoadChildrenFn>(async (_dn, opts) => {
      const page: ChildPage = opts?.filter
        ? { children: [ALICE], truncated: false }
        : { children: [ALICE, BOB], truncated: false, childCount: 2 }
      return page
    })
    const { wrapper, api } = mountWith(load)
    await expandRoot(wrapper)
    expect(wrapper.text()).toContain('uid=bob')

    await api.filterNode(PEOPLE, '  ali ')
    await flushPromises()

    expect(load).toHaveBeenLastCalledWith(PEOPLE, { filter: 'ali' })
    expect(wrapper.text()).not.toContain('uid=bob')
    expect(footer(wrapper).text()).toContain('Filtered by "ali" · 1 match')
    expect(api.getFilter(PEOPLE)).toBe('ali')
    expect(api.getFilter('ou=elsewhere,dc=x')).toBeNull()

    await buttonNamed(wrapper, 'Clear filter')!.trigger('click')
    await flushPromises()

    expect(load).toHaveBeenLastCalledWith(PEOPLE, { filter: undefined })
    expect(wrapper.text()).toContain('uid=bob')
    expect(footer(wrapper).exists()).toBe(false)
    expect(api.getFilter(PEOPLE)).toBe('')
  })

  it('filterNode expands a collapsed node and delegates to nested levels', async () => {
    const GROUPS = 'ou=groups,' + PEOPLE
    const load = vi.fn<LoadChildrenFn>(async (dn, opts) => {
      if (dn === PEOPLE) return [{ dn: GROUPS, rdn: 'ou=groups', hasChildren: true }]
      return opts?.filter
        ? [{ dn: 'cn=admins,' + GROUPS, rdn: 'cn=admins', hasChildren: false }]
        : [{ dn: 'cn=admins,' + GROUPS, rdn: 'cn=admins', hasChildren: false },
           { dn: 'cn=users,' + GROUPS, rdn: 'cn=users', hasChildren: false }]
    })
    const { wrapper, api } = mountWith(load)
    await expandRoot(wrapper)
    expect(wrapper.text()).toContain('ou=groups')
    expect(wrapper.text()).not.toContain('cn=users')

    // The nested node is not expanded yet; filtering it must open it.
    const found = await api.filterNode('OU=Groups, ' + PEOPLE, 'adm')
    await flushPromises()

    expect(found).toBe(true)
    expect(load).toHaveBeenLastCalledWith(GROUPS, { filter: 'adm' })
    expect(wrapper.text()).toContain('cn=admins')
    expect(wrapper.text()).not.toContain('cn=users')
    expect(api.getFilter(GROUPS)).toBe('adm')
  })

  it('a refresh drops the branch filter', async () => {
    const load = vi.fn<LoadChildrenFn>(async () => [ALICE])
    const { wrapper, api } = mountWith(load)
    await expandRoot(wrapper)
    await api.filterNode(PEOPLE, 'ali')
    await flushPromises()
    expect(api.getFilter(PEOPLE)).toBe('ali')

    const vm = wrapper.vm as unknown as { refreshNode: (dn: string, c: unknown) => boolean }
    vm.refreshNode(PEOPLE, { children: [ALICE, BOB], truncated: false })
    await flushPromises()

    expect(api.getFilter(PEOPLE)).toBe('')
    expect(footer(wrapper).exists()).toBe(false)
  })
})

describe('DnTree.revealNode', () => {
  const EU = 'ou=eu,' + PEOPLE
  const CAROL = { dn: 'uid=carol,' + EU, rdn: 'uid=carol', hasChildren: false }

  it('opens each level down to the target, loading on demand', async () => {
    const load = vi.fn<LoadChildrenFn>(async (dn) => {
      if (dn === PEOPLE) return [ALICE, { dn: EU, rdn: 'ou=eu', hasChildren: true }]
      if (dn === EU) return [CAROL]
      return []
    })
    const { wrapper, api } = mountWith(load)
    expect(wrapper.text()).not.toContain('ou=eu')

    const found = await api.revealNode([PEOPLE, EU], CAROL.dn)
    await flushPromises()

    expect(found).toBe(true)
    expect(load.mock.calls.map(c => c[0])).toEqual([PEOPLE, EU])
    expect(wrapper.text()).toContain('uid=carol')
  })

  it('re-lists a capped parent with an exact RDN filter when the target is not on the page', async () => {
    const load = vi.fn<LoadChildrenFn>(async (_dn, opts) => opts?.filter
      ? { children: [{ dn: 'uid=zed,' + PEOPLE, rdn: 'uid=zed', hasChildren: false }] }
      : { children: [ALICE, BOB], truncated: true, childCount: 900 })
    const { wrapper, api } = mountWith(load)

    const found = await api.revealNode([PEOPLE], 'uid=zed,' + PEOPLE)
    await flushPromises()

    expect(found).toBe(true)
    expect(load).toHaveBeenLastCalledWith(PEOPLE, { filter: '(uid=zed)' })
    expect(wrapper.text()).toContain('uid=zed')
    expect(api.getFilter(PEOPLE)).toBe('(uid=zed)')
  })

  it('escapes filter specials in the RDN value', async () => {
    const weird = 'cn=a(b)*,' + PEOPLE
    const load = vi.fn<LoadChildrenFn>(async (_dn, opts) => opts?.filter
      ? { children: [{ dn: weird, rdn: 'cn=a(b)*', hasChildren: false }] }
      : { children: [ALICE], truncated: true })
    const { api } = mountWith(load)

    expect(await api.revealNode([PEOPLE], weird)).toBe(true)
    expect(load).toHaveBeenLastCalledWith(PEOPLE, { filter: '(cn=a\\28b\\29\\2a)' })
  })

  it('reports false when the target is genuinely absent or the path does not start here', async () => {
    const load = vi.fn<LoadChildrenFn>(async () => ({ children: [ALICE, BOB], truncated: false }))
    const { api } = mountWith(load)

    expect(await api.revealNode([PEOPLE], 'uid=nobody,' + PEOPLE)).toBe(false)
    // Complete, unfiltered listing → no point re-querying.
    expect(load).toHaveBeenCalledTimes(1)
    expect(await api.revealNode(['ou=other,dc=x'], 'uid=x,ou=other,dc=x')).toBe(false)
  })
})

describe('DnTree.reloadNode', () => {
  it('re-fetches with the branch\'s active filter', async () => {
    const load = vi.fn<LoadChildrenFn>(async () => [ALICE])
    const { api } = mountWith(load)
    await api.filterNode(PEOPLE, 'ali')
    load.mockClear()

    expect(await api.reloadNode(PEOPLE)).toBe(true)

    expect(load).toHaveBeenCalledWith(PEOPLE, { filter: 'ali' })
    expect(api.getFilter(PEOPLE)).toBe('ali')
  })

  it('keeps a fully loaded branch fully loaded', async () => {
    const load = vi.fn<LoadChildrenFn>(async (_dn, opts) => opts?.all
      ? { children: [ALICE, BOB], truncated: false }
      : { children: [ALICE], truncated: true, childCount: 2 })
    const { wrapper, api } = mountWith(load)
    await expandRoot(wrapper)
    await api.loadAll(PEOPLE)
    load.mockClear()

    await api.reloadNode(PEOPLE)

    expect(load).toHaveBeenCalledWith(PEOPLE, { all: true, filter: undefined })
    expect(footer(wrapper).exists()).toBe(false)
  })

  it('updates the expand arrow of a node that was never expanded, and delegates', async () => {
    const GROUPS = 'ou=groups,' + PEOPLE
    let groupsHasKids = false
    const load = vi.fn<LoadChildrenFn>(async (dn) => {
      if (dn === PEOPLE) return [{ dn: GROUPS, rdn: 'ou=groups', hasChildren: false }]
      return groupsHasKids ? [{ dn: 'cn=admins,' + GROUPS, rdn: 'cn=admins', hasChildren: false }] : []
    })
    const { wrapper, api } = mountWith(load)
    await expandRoot(wrapper)
    // Innermost <li> for ou=groups (the root <li> contains its text too).
    const groupsRow = wrapper.findAll('li').filter(li => li.text().includes('ou=groups')).at(-1)!
    expect(groupsRow.find('button[aria-label="Toggle children"]').exists()).toBe(false)

    // Something was created under ou=groups elsewhere; the nested level
    // owns the node, so the reload has to be delegated to it.
    groupsHasKids = true
    expect(await api.reloadNode('OU=Groups, ' + PEOPLE)).toBe(true)
    await flushPromises()

    expect(load).toHaveBeenLastCalledWith(GROUPS, { filter: undefined })
    expect(groupsRow.find('button[aria-label="Toggle children"]').exists()).toBe(true)
    // Not auto-expanded — only the arrow changed.
    expect(wrapper.text()).not.toContain('cn=admins')
    expect(await api.reloadNode('ou=nowhere,dc=x')).toBe(false)
  })
})
