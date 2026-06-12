// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { resolveGroupMembers } from './groupMembers'

describe('resolveGroupMembers', () => {
  it('reads the backend lower-cased uniquemember key', () => {
    // The regression: backend responses lower-case attribute keys, so a
    // camelCase `attributes.uniqueMember` lookup made every
    // groupOfUniqueNames group render as empty.
    expect(resolveGroupMembers({
      cn: ['Engineering'],
      uniquemember: ['uid=alice,ou=people,dc=x'],
    })).toEqual({
      memberAttr: 'uniqueMember',
      members: ['uid=alice,ou=people,dc=x'],
    })
  })

  it('reads member and memberuid conventions', () => {
    expect(resolveGroupMembers({ member: ['uid=a,dc=x'] }))
      .toEqual({ memberAttr: 'member', members: ['uid=a,dc=x'] })
    expect(resolveGroupMembers({ memberuid: ['alice'] }))
      .toEqual({ memberAttr: 'memberUid', members: ['alice'] })
  })

  it('still accepts camelCase keys (defensive)', () => {
    expect(resolveGroupMembers({ uniqueMember: ['uid=a,dc=x'] }).memberAttr)
      .toBe('uniqueMember')
  })

  it('prefers member when several conventions carry values', () => {
    expect(resolveGroupMembers({
      member: ['uid=a,dc=x'],
      uniquemember: ['uid=b,dc=x'],
    }).memberAttr).toBe('member')
  })

  it('wraps a scalar value as a single member', () => {
    expect(resolveGroupMembers({ uniquemember: 'uid=a,dc=x' }).members)
      .toEqual(['uid=a,dc=x'])
  })

  it('defaults to member with no values for an empty or absent map', () => {
    expect(resolveGroupMembers({})).toEqual({ memberAttr: 'member', members: [] })
    expect(resolveGroupMembers(undefined)).toEqual({ memberAttr: 'member', members: [] })
  })
})
