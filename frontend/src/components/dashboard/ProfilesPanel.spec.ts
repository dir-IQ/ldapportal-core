// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for ProfilesPanel.vue.
 *
 * Focus: when the dashboard service can't reach a profile's directory it returns
 * a -1 user/group count (rendered as an em-dash). The panel must say *why* —
 * surface a "Directory unavailable" cue — rather than show a bare dash that
 * reads like "no data".
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ProfilesPanel from './ProfilesPanel.vue'

type Profile = {
  id: string
  name: string
  directoryName?: string | null
  targetUserDn?: string | null
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
  it('shows a "Directory unavailable" cue and em-dashes when counts are -1', () => {
    const w = mountWith([{ ...base, name: 'INT', userCount: -1, groupCount: -1 }])
    const text = w.text()
    expect(text).toContain('Directory unavailable')
    // Counts render as em-dashes, never the raw -1 sentinel.
    expect(text).toContain('—')
    expect(text).not.toContain('-1')
  })

  it('does not show the cue when the directory is reachable (counts >= 0)', () => {
    const w = mountWith([base])
    expect(w.text()).not.toContain('Directory unavailable')
    expect(w.text()).toContain('113')
  })

  it('flags the row when only one of the counts failed', () => {
    const w = mountWith([{ ...base, userCount: 50, groupCount: -1 }])
    expect(w.text()).toContain('Directory unavailable')
    expect(w.text()).toContain('50')
  })
})
