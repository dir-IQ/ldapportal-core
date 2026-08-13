// SPDX-License-Identifier: Apache-2.0
/**
 * Tests DnTree.refreshNode — the mechanism the Directory Browser uses to
 * update the tree after a delete/rename/move or a manual branch refresh.
 * The key regression: refreshNode must match the target node by a
 * normalized DN, so a formatting-only difference (case / whitespace around
 * the RDN separator) between the server's node DN and the DN the app hands
 * back still updates the tree instead of silently leaving it stale.
 */
import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DnTree from './DnTree.vue'

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
