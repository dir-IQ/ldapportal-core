// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { usePreferencesStore } from '@/stores/preferences'
import GroupMembersPanel from './GroupMembersPanel.vue'

// The preferences store debounces a merge-patch to the server after a write;
// stub the API so a persisted height never leaves the test.
vi.mock('@/api/preferences', () => ({
  getPreferences: vi.fn().mockResolvedValue({ data: {} }),
  patchPreferences: vi.fn().mockResolvedValue({ data: {} }),
}))

const MEMBERS = [
  'uid=alice,ou=People,dc=example,dc=com',
  'uid=bmehta,ou=People,dc=example,dc=com',
  'cn=Doe\\, Jane,ou=People,dc=example,dc=com',
]

function mountWith(attributes: Record<string, string[]>) {
  return mount(GroupMembersPanel, { props: { attributes } })
}

describe('GroupMembersPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders nothing for a non-group entry', () => {
    const w = mountWith({
      objectClass: ['inetOrgPerson', 'top'],
      cn: ['Alice'],
      // A user can carry a `member`-looking attribute (e.g. memberOf); only
      // objectClass decides whether the panel shows.
      memberOf: ['cn=engineering,ou=Groups,dc=example,dc=com'],
    })
    expect(w.find('[data-testid="group-members-panel"]').exists()).toBe(false)
  })

  it('lists groupOfUniqueNames members with RDN and parent split apart', () => {
    const w = mountWith({
      objectClass: ['groupOfUniqueNames', 'top'],
      cn: ['engineering'],
      uniqueMember: MEMBERS,
    })
    expect(w.find('[data-testid="member-count"]').text()).toBe('3')
    expect(w.text()).toContain('uniqueMember')
    const rows = w.findAll('li')
    expect(rows).toHaveLength(3)
    expect(rows[0].find('span.font-medium').text()).toBe('uid=alice')
    expect(rows[0].text()).toContain('ou=People,dc=example,dc=com')
  })

  it('keeps an escaped comma inside the RDN', () => {
    const w = mountWith({
      objectClass: ['groupOfNames'],
      member: ['cn=Doe\\, Jane,ou=People,dc=example,dc=com'],
    })
    const row = w.find('li')
    expect(row.find('span.font-medium').text()).toBe('cn=Doe\\, Jane')
    expect(row.findAll('span')[2].text()).toBe('ou=People,dc=example,dc=com')
  })

  it('accepts the lower-cased attribute keys the search mapper produces', () => {
    const w = mountWith({
      objectclass: ['groupofnames'],
      member: MEMBERS.slice(0, 2),
    })
    expect(w.find('[data-testid="group-members-panel"]').exists()).toBe(true)
    expect(w.find('[data-testid="member-count"]').text()).toBe('2')
  })

  it('shows an empty state for a group with no members', () => {
    const w = mountWith({ objectClass: ['groupOfNames'], cn: ['empty'] })
    expect(w.find('[data-testid="group-members-panel"]').exists()).toBe(true)
    expect(w.text()).toContain('This group has no members.')
    expect(w.find('input').exists()).toBe(false)
  })

  it('emits open with the member DN when Open is clicked', async () => {
    const w = mountWith({ objectClass: ['groupOfNames'], member: MEMBERS })
    await w.findAll('li')[1].find('button').trigger('click')
    expect(w.emitted('open')).toEqual([[MEMBERS[1]]])
  })

  it('shows posixGroup memberUid values as-is with no Open link', () => {
    const w = mountWith({
      objectClass: ['posixGroup', 'top'],
      gidNumber: ['5000'],
      memberUid: ['alice', 'bmehta'],
    })
    expect(w.text()).toContain('memberUid')
    const rows = w.findAll('li')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toBe('alice')
    expect(rows[0].find('button').exists()).toBe(false)
  })

  it('filters members case-insensitively and reports an empty match', async () => {
    const w = mountWith({ objectClass: ['groupOfNames'], member: MEMBERS })
    await w.find('input').setValue('BMEHTA')
    let rows = w.findAll('li')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('uid=bmehta')

    await w.find('input').setValue('nobody')
    rows = w.findAll('li')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('No members match')
  })

  it('clears the filter when a different entry is shown', async () => {
    const w = mountWith({ objectClass: ['groupOfNames'], member: MEMBERS })
    await w.find('input').setValue('alice')
    await w.setProps({ attributes: { objectClass: ['groupOfNames'], member: MEMBERS.slice(0, 2) } })
    expect((w.find('input').element as HTMLInputElement).value).toBe('')
    expect(w.findAll('li')).toHaveLength(2)
  })

  describe('resizable height', () => {
    const GROUP = { objectClass: ['groupOfNames'], member: MEMBERS }
    const listMaxHeight = (w: ReturnType<typeof mountWith>) =>
      (w.find('[data-testid="member-list"]').element as HTMLElement).style.maxHeight

    function pointer(type: string, clientY: number): PointerEvent {
      // jsdom lacks a PointerEvent constructor; a MouseEvent carries the
      // fields the handlers read (button, clientY).
      return new MouseEvent(type, { bubbles: true, clientY, button: 0 }) as unknown as PointerEvent
    }

    it('caps the list at 240px by default', () => {
      const w = mountWith(GROUP)
      expect(listMaxHeight(w)).toBe('240px')
      expect(w.find('[data-testid="member-list-grip"]').exists()).toBe(true)
    })

    it('restores a height saved in the panels preference namespace', () => {
      usePreferencesStore().doc = { panels: { 'browser-group-members': 420 } }
      const w = mountWith(GROUP)
      expect(listMaxHeight(w)).toBe('420px')
    })

    it('ignores a malformed stored height', () => {
      usePreferencesStore().doc = { panels: { 'browser-group-members': 'tall' } }
      const w = mountWith(GROUP)
      expect(listMaxHeight(w)).toBe('240px')
    })

    it('follows a pointer drag on the grip and persists the result on release', async () => {
      const w = mountWith(GROUP)
      const grip = w.find('[data-testid="member-list-grip"]')
      grip.element.dispatchEvent(pointer('pointerdown', 100))
      window.dispatchEvent(pointer('pointermove', 180))
      await w.vm.$nextTick()
      expect(listMaxHeight(w)).toBe('320px')
      expect(document.body.style.userSelect).toBe('none')

      window.dispatchEvent(pointer('pointerup', 180))
      await w.vm.$nextTick()
      expect(document.body.style.userSelect).toBe('')
      expect(usePreferencesStore().read('panels', 'browser-group-members', null)).toBe(320)

      // Listeners are gone: further moves do nothing.
      window.dispatchEvent(pointer('pointermove', 400))
      await w.vm.$nextTick()
      expect(listMaxHeight(w)).toBe('320px')
    })

    it('clamps a drag to the minimum height', async () => {
      const w = mountWith(GROUP)
      w.find('[data-testid="member-list-grip"]').element.dispatchEvent(pointer('pointerdown', 500))
      window.dispatchEvent(pointer('pointermove', 0))
      await w.vm.$nextTick()
      expect(listMaxHeight(w)).toBe('96px')
      window.dispatchEvent(pointer('pointerup', 0))
    })

    it('resizes in 24px steps from the keyboard and persists', async () => {
      const w = mountWith(GROUP)
      const grip = w.find('[data-testid="member-list-grip"]')
      await grip.trigger('keydown', { key: 'ArrowDown' })
      expect(listMaxHeight(w)).toBe('264px')
      await grip.trigger('keydown', { key: 'ArrowUp' })
      await grip.trigger('keydown', { key: 'ArrowUp' })
      expect(listMaxHeight(w)).toBe('216px')
      expect(usePreferencesStore().read('panels', 'browser-group-members', null)).toBe(216)
    })
  })

  it('copies every member DN, one per line', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const w = mountWith({ objectClass: ['groupOfNames'], member: MEMBERS })
    await w.find('button.btn-secondary').trigger('click')
    expect(writeText).toHaveBeenCalledWith(MEMBERS.join('\n'))
  })
})
