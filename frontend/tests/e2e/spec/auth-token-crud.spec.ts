// Smoke test for API token CRUD (Prereq A).
//
// No frontend UI for API tokens exists yet — they're managed via API
// only. The smoke test exercises the full lifecycle through the
// superadmin's authenticated session, asserting the contract in
// CreateApiTokenRequest / ApiTokenCreateResponse / ApiTokenResponse:
// create -> rotate -> revoke. All mutating endpoints reject API-token
// callers (defense in depth), so this test must run with cookie auth.

import { test, expect } from '../fixtures'

test.describe('API token CRUD @smoke', () => {
  test('superadmin can create, rotate, and revoke an API token', async ({ superadmin }) => {
    const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString() // +1 day

    // 1. Create — plaintext value is shown ONCE in the response and never again.
    const createResp = await superadmin.request.post('/api/v1/superadmin/api-tokens', {
      data: {
        name: `e2e-smoke-${Date.now()}`,
        description: 'created by SP5 smoke test',
        expiresAt,
      },
    })
    expect(createResp.status()).toBe(201)
    const created = await createResp.json()
    expect(created.token?.id).toBeTruthy()
    expect(created.plaintext).toBeTruthy()
    expect(typeof created.plaintext).toBe('string')
    const tokenId = created.token.id

    // 2. List — the token surfaces with active status.
    const listResp = await superadmin.request.get('/api/v1/superadmin/api-tokens')
    expect(listResp.ok()).toBe(true)
    const list = await listResp.json() as Array<{ id: string }>
    expect(list.some(t => t.id === tokenId)).toBe(true)

    // 3. Rotate — issues a new plaintext, invalidates the old one.
    const rotateResp = await superadmin.request.post(`/api/v1/superadmin/api-tokens/${tokenId}/rotate`)
    expect(rotateResp.ok()).toBe(true)
    const rotated = await rotateResp.json()
    expect(rotated.plaintext).toBeTruthy()
    expect(rotated.plaintext).not.toBe(created.plaintext)
    expect(rotated.token.id).toBe(tokenId) // same row, new secret

    // 4. Revoke — DELETE returns 204; subsequent GET should reflect revoked state.
    const revokeResp = await superadmin.request.delete(`/api/v1/superadmin/api-tokens/${tokenId}`)
    expect(revokeResp.status()).toBe(204)
  })
})
