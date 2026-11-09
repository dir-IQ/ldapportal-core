// SPDX-License-Identifier: Apache-2.0
/**
 * Recent Activity rows must always say what they affected. LDAP-entry actions
 * carry a targetDn; workflow/system actions (account admin, approvals, …) don't
 * — their subject lives in the audit detail. The panel surfaces a detail-derived
 * fallback so those rows aren't blank.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import RecentActivityPanel from './RecentActivityPanel.vue'

const stubs = {
  RelativeTime: { props: ['value'], template: '<span />' },
  RouterLink: { props: ['to'], template: '<a><slot /></a>' },
}

interface Evt {
  id: string
  action: string
  targetDn?: string | null
  actorUsername?: string | null
  occurredAt: string
  detail?: Record<string, unknown> | null
}

function row(id: string, action: string, extra: Partial<Evt>): Evt {
  return { id, action, occurredAt: '2026-01-01T00:00:00Z', ...extra }
}

function mountWith(events: Evt[]) {
  return mount(RecentActivityPanel, { props: { events }, global: { stubs } })
}

describe('RecentActivityPanel affected-entity column', () => {
  it('shows the short DN for LDAP-entry actions', () => {
    const w = mountWith([row('1', 'USER_UPDATE', { targetDn: 'uid=alice,ou=people,dc=x' })])
    expect(w.text()).toContain('uid=alice')
  })

  it('falls back to the account username when there is no targetDn', () => {
    const w = mountWith([row('2', 'ACCOUNT_CREATE', { targetDn: null, detail: { username: 'bob', role: 'ADMIN' } })])
    expect(w.text()).toContain('bob')
  })

  it('falls back to a humanized approval request type', () => {
    const w = mountWith([row('3', 'APPROVAL_SUBMITTED', { targetDn: null, detail: { requestType: 'CREATE_USER' } })])
    expect(w.text()).toContain('Create user')
  })

  it('names the entry a single-DN bulk delete removed', () => {
    const w = mountWith([row('5', 'USER_DELETE', { targetDn: null,
      detail: { operation: 'bulkDelete', deletedDns: ['uid=a,ou=people,dc=x'] } })])
    expect(w.find('li span.truncate').text()).toBe('uid=a')
  })

  it('names the first entry and counts the rest for a multi-DN bulk delete, listing all on hover', () => {
    const dns = ['uid=a,ou=people,dc=x', 'uid=b,ou=people,dc=x', 'uid=c,ou=people,dc=x']
    const w = mountWith([row('6', 'USER_DELETE', { targetDn: null, detail: { operation: 'bulkDelete', deletedDns: dns } })])
    const cell = w.find('li span.truncate')
    expect(cell.text()).toBe('uid=a +2 more')
    expect(cell.attributes('title')).toBe(dns.join('\n'))
  })

  it('prefers the DN list over the container target', () => {
    const w = mountWith([row('7', 'USER_CREATE', { targetDn: 'ou=people,dc=x',
      detail: { operation: 'bulkImport', createdDns: ['uid=a,ou=people,dc=x', 'uid=b,ou=people,dc=x'] } })])
    expect(w.find('li span.truncate').text()).toBe('uid=a +1 more')
  })

  it('caps the hover list for very large bulk operations', () => {
    const dns = Array.from({ length: 25 }, (_, i) => `uid=u${i},ou=people,dc=x`)
    const w = mountWith([row('8', 'USER_DELETE', { targetDn: null, detail: { deletedDns: dns } })])
    const lines = w.find('li span.truncate').attributes('title')!.split('\n')
    expect(lines).toHaveLength(21)
    expect(lines[20]).toBe('+5 more')
  })

  it('counts the entries a bulk attribute update changed, with the container when recorded', () => {
    const w = mountWith([
      row('9', 'BULK_ATTRIBUTE_UPDATE', { targetDn: null, detail: { totalDns: 12, updated: 12, errors: 0 } }),
      row('10', 'BULK_ATTRIBUTE_UPDATE', { targetDn: 'ou=people,dc=x', detail: { totalDns: 3, updated: 1, errors: 2 } }),
    ])
    const cells = w.findAll('li span.truncate').map(c => c.text())
    expect(cells).toEqual(['12 entries', 'ou=people (1 entry)'])
  })

  it('renders an em-dash when no entity can be derived', () => {
    const w = mountWith([row('4', 'CAMPAIGN_EXPIRED', { targetDn: null, detail: {} })])
    expect(w.text()).toContain('—')
  })
})
