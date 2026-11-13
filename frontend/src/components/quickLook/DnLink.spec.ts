// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DnLink from './DnLink.vue'
import { useQuickLookStore } from '@/stores/quickLook'

const authState = vi.hoisted(() => ({ isSuperadmin: true }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authState }))

const DN = 'uid=alice,ou=people,dc=example,dc=com'

function mountLink(props: Record<string, unknown> = {}) {
  return mount(DnLink, { props: { dn: DN, directoryId: 'd1', ...props } })
}

describe('DnLink', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    authState.isSuperadmin = true
  })

  it('opens the quick look on a plain click instead of navigating', async () => {
    const w = mountLink()
    const a = w.get('a')
    expect(a.attributes('href')).toBe('/superadmin/directory-browser?dir=d1&dn=' + encodeURIComponent(DN).replace(/%20/g, '+'))

    const ev = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })
    a.element.dispatchEvent(ev)

    expect(ev.defaultPrevented).toBe(true)
    expect(useQuickLookStore().current).toEqual({ directoryId: 'd1', dn: DN })
  })

  it('leaves Ctrl/⌘-click to the browser (opens the Directory Browser in a new tab)', () => {
    const w = mountLink()
    for (const mod of [{ ctrlKey: true }, { metaKey: true }]) {
      const ev = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ...mod })
      w.get('a').element.dispatchEvent(ev)
      expect(ev.defaultPrevented).toBe(false)
    }
    expect(useQuickLookStore().isOpen).toBe(false)
  })

  it('does not let the click reach an enclosing clickable row', async () => {
    const rowClick = vi.fn()
    const w = mount({
      components: { DnLink },
      template: `<div @click="rowClick"><DnLink dn="${DN}" directory-id="d1" /></div>`,
      setup: () => ({ rowClick }),
    })
    await w.get('a').trigger('click')
    expect(rowClick).not.toHaveBeenCalled()
  })

  it('pushes onto the trail when nested in the drawer', async () => {
    const store = useQuickLookStore()
    store.open('d1', 'cn=g,ou=groups,dc=example,dc=com')
    await mountLink({ nested: true }).get('a').trigger('click')
    expect(store.stack.map(t => t.dn)).toEqual(['cn=g,ou=groups,dc=example,dc=com', DN])
  })

  it('renders a button (no browser href) for admins below superadmin', async () => {
    authState.isSuperadmin = false
    const w = mountLink()
    expect(w.find('a').exists()).toBe(false)
    await w.get('button').trigger('click')
    expect(useQuickLookStore().current?.dn).toBe(DN)
  })
})
