// SPDX-License-Identifier: Apache-2.0
/**
 * Member removal must not put the member in a DELETE body: reverse proxies such
 * as WebSEAL don't reliably forward it, and the portal then answers 400.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

import client from './client'
import { removeGroupMember, removeGroupMembersBulk } from './groups'

const GROUP = 'cn=staff,ou=groups,dc=example,dc=com'
const BOB = 'uid=bob,ou=people,dc=example,dc=com'

describe('group member removal', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sends one member in the query of a body-less DELETE', async () => {
    await removeGroupMember('dir-1', GROUP, { memberAttribute: 'member', memberValue: BOB })

    expect(client.delete).toHaveBeenCalledWith('/directories/dir-1/groups/members', {
      params: { dn: GROUP, memberAttribute: 'member', memberValue: BOB },
    })
    const config = vi.mocked(client.delete).mock.calls[0][1] as Record<string, unknown>
    expect(config).not.toHaveProperty('data')
  })

  it('sends several members in the body of a POST', async () => {
    const body = { memberAttribute: 'member', memberValues: [BOB] }
    await removeGroupMembersBulk('dir-1', GROUP, body)

    expect(client.post).toHaveBeenCalledWith('/directories/dir-1/groups/members/bulk/remove', body,
      { params: { dn: GROUP } })
    expect(client.delete).not.toHaveBeenCalled()
  })
})
