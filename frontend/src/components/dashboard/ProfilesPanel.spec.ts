// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for ProfilesPanel.vue.
 *
 * Covers the "Unavailable" cue when a directory's counts come back as -1, and
 * the Scope column surfacing the group OU when it's a distinct subtree from the
 * user OU.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ProfilesPanel from './ProfilesPanel.vue'

type Profile = {
  id: string
  name: string
  directoryName?: string | null
  targetUserDn?: string | null
  targetGroupDn?: string | null
  userCount: number
  groupCount: number
  pendingApprovals: number
}

const base: Profile = {
  id: '1', name: 'PROD', directoryName: 'OUD1 Primary',
  targetUserDn: 'ou=People,dc=example,dc=com',
  userCount: 113, groupCount: 0, pendingApprovals: 0,
}

const mountWith = (profiles: Profile[]) => mount(ProfilesPanel, { props: { profiles } })

describe('ProfilesPanel directory-unavailable indication', () => {
  it('shows an "Unavailable" cue and em-dashes when counts are -1', () => {
    const w = mountWith([{ ...base, name: 'INT', userCount: -1, groupCount: -1 }])
    const text = w.text()
    expect(text).toContain('Unavailable')
    // Counts render as em-dashes, never the raw -1 sentinel.
    expect(text).toContain('—')
    expect(text).not.toContain('-1')
  })

  it('does not show the cue when the directory is reachable (counts >= 0)', () => {
    const w = mountWith([base])
    expect(w.text()).not.toContain('Unavailable')
    expect(w.text()).toContain('113')
  })

  it('flags the row when only one of the counts failed', () => {
    const w = mountWith([{ ...base, userCount: 50, groupCount: -1 }])
    expect(w.text()).toContain('Unavailable')
    expect(w.text()).toContain('50')
  })
})

describe('ProfilesPanel scope column', () => {
  it('shows the group OU when it is a distinct subtree from the user OU', () => {
    const w = mountWith([{ ...base, targetGroupDn: 'ou=Groups,dc=example,dc=com' }])
    const text = w.text()
    expect(text).toContain('ou=People')
    expect(text).toContain('ou=Groups')
  })

  it('shows only the user OU when the group OU is the same (the default)', () => {
    const w = mountWith([{ ...base, targetGroupDn: 'ou=People,dc=example,dc=com' }])
    const text = w.text()
    expect(text).toContain('ou=People')
    expect(text).not.toContain('ou=Groups')
  })
})
