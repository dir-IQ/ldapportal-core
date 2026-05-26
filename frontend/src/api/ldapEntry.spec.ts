// SPDX-License-Identifier: Apache-2.0
/**
 * Spec for the typed user/group entry-modify wrapper. Mocks the
 * shared axios client to assert path routing + body / params shape.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./client', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn(),
  },
}))

// eslint-disable-next-line import/first
import client from './client'
// eslint-disable-next-line import/first
import { updateEntry, getEntry, type AttributeModification } from './ldapEntry'

const directoryId = '11111111-2222-3333-4444-555555555555'
const dn = 'cn=alice,ou=people,dc=example,dc=com'
const okResponse = { data: { dn, attributes: {} }, status: 200, statusText: 'OK', headers: {}, config: {} as never }

describe('updateEntry', () => {
  beforeEach(() => vi.clearAllMocks())

  it('routes user classification to the users/entry path', async () => {
    ;(client.put as ReturnType<typeof vi.fn>).mockResolvedValue(okResponse)
    const mods: AttributeModification[] = [
      { operation: 'REPLACE', attribute: 'mail', values: ['a@x.example'] },
    ]
    await updateEntry(directoryId, 'user', dn, mods)
    expect(client.put).toHaveBeenCalledTimes(1)
    expect(client.put).toHaveBeenCalledWith(
      `/directories/${directoryId}/users/entry`,
      { modifications: mods },
      { params: { dn } },
    )
  })

  it('routes group classification to the groups/entry path', async () => {
    ;(client.put as ReturnType<typeof vi.fn>).mockResolvedValue(okResponse)
    const mods: AttributeModification[] = [
      { operation: 'DELETE', attribute: 'description', values: [] },
    ]
    await updateEntry(directoryId, 'group', 'cn=devs,ou=groups,dc=example', mods)
    expect(client.put).toHaveBeenCalledWith(
      `/directories/${directoryId}/groups/entry`,
      { modifications: mods },
      { params: { dn: 'cn=devs,ou=groups,dc=example' } },
    )
  })

  it('passes the modifications array through verbatim', async () => {
    ;(client.put as ReturnType<typeof vi.fn>).mockResolvedValue(okResponse)
    const mods: AttributeModification[] = [
      { operation: 'REPLACE', attribute: 'mail', values: ['a@x.example'] },
      { operation: 'REPLACE', attribute: 'givenName', values: ['Alicia'] },
    ]
    await updateEntry(directoryId, 'user', dn, mods)
    const body = (client.put as ReturnType<typeof vi.fn>).mock.calls[0][1]
    expect(body).toEqual({ modifications: mods })
  })

  it('returns the AxiosResponse from the underlying client unchanged', async () => {
    ;(client.put as ReturnType<typeof vi.fn>).mockResolvedValue(okResponse)
    const result = await updateEntry(directoryId, 'user', dn, [])
    expect(result).toBe(okResponse)
  })

  it('propagates rejections from the underlying client', async () => {
    const err = Object.assign(new Error('boom'), { response: { status: 500 } })
    ;(client.put as ReturnType<typeof vi.fn>).mockRejectedValue(err)
    await expect(updateEntry(directoryId, 'user', dn, [])).rejects.toBe(err)
  })
})

describe('getEntry', () => {
  beforeEach(() => vi.clearAllMocks())

  it('routes user classification to the users/entry GET path', async () => {
    ;(client.get as ReturnType<typeof vi.fn>).mockResolvedValue(okResponse)
    await getEntry(directoryId, 'user', dn)
    expect(client.get).toHaveBeenCalledWith(
      `/directories/${directoryId}/users/entry`,
      { params: { dn, attributes: '' } },
    )
  })

  it('routes group classification to the groups/entry GET path', async () => {
    ;(client.get as ReturnType<typeof vi.fn>).mockResolvedValue(okResponse)
    await getEntry(directoryId, 'group', 'cn=devs,ou=groups,dc=example', 'cn,member')
    expect(client.get).toHaveBeenCalledWith(
      `/directories/${directoryId}/groups/entry`,
      { params: { dn: 'cn=devs,ou=groups,dc=example', attributes: 'cn,member' } },
    )
  })
})
