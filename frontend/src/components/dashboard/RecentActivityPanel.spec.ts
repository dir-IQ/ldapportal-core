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

  it('renders an em-dash when no entity can be derived', () => {
    const w = mountWith([row('4', 'CAMPAIGN_EXPIRED', { targetDn: null, detail: {} })])
    expect(w.text()).toContain('—')
  })
})
