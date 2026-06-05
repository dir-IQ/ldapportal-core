// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GroupChips from './GroupChips.vue'

const DNS = [
  'cn=Engineering,ou=Groups,dc=example,dc=com',
  'cn=Finance,ou=Groups,dc=example,dc=com',
  'cn=VPN-Users,ou=Groups,dc=example,dc=com',
  'cn=Domain Admins,ou=Groups,dc=example,dc=com',
]

describe('GroupChips', () => {
  it('renders a muted dash when there are no groups', () => {
    const w = mount(GroupChips, { props: { dns: [] } })
    expect(w.text()).toBe('—')
    expect(w.find('.badge-gray').exists()).toBe(false)
  })

  it('shows group names (leading RDN value), not DNs', () => {
    const w = mount(GroupChips, { props: { dns: DNS, expanded: true } })
    const names = w.findAll('.badge-gray').map(p => p.text())
    expect(names).toContain('Engineering')
    expect(names).toContain('VPN-Users')
    expect(names.join(' ')).not.toContain('ou=Groups')
  })

  it('exposes the full DN as each pill title', () => {
    const w = mount(GroupChips, { props: { dns: DNS, expanded: true } })
    const eng = w.findAll('.badge-gray').find(p => p.text() === 'Engineering')
    expect(eng?.attributes('title')).toBe('cn=Engineering,ou=Groups,dc=example,dc=com')
  })

  it('collapses to collapsedCount names plus a +N overflow pill', () => {
    const w = mount(GroupChips, { props: { dns: DNS, collapsedCount: 3 } })
    expect(w.findAll('.badge-gray')).toHaveLength(3)
    const overflow = w.find('.badge-blue')
    expect(overflow.exists()).toBe(true)
    expect(overflow.text()).toBe('+1')
  })

  it('lists the hidden names in the overflow pill title', () => {
    const w = mount(GroupChips, { props: { dns: DNS, collapsedCount: 3 } })
    // Sorted by name, the last of four ("VPN-Users") is the one hidden.
    expect(w.find('.badge-blue').attributes('title')).toBe('VPN-Users')
  })

  it('shows every group and no overflow pill when expanded', () => {
    const w = mount(GroupChips, { props: { dns: DNS, expanded: true, collapsedCount: 3 } })
    expect(w.findAll('.badge-gray')).toHaveLength(4)
    expect(w.find('.badge-blue').exists()).toBe(false)
  })

  it('does not show an overflow pill when within collapsedCount', () => {
    const w = mount(GroupChips, { props: { dns: DNS.slice(0, 2), collapsedCount: 3 } })
    expect(w.findAll('.badge-gray')).toHaveLength(2)
    expect(w.find('.badge-blue').exists()).toBe(false)
  })
})
