// Smoke test for the public OpenAPI contract endpoint (Prereq C).
//
// Health-spec already does a minimal "endpoint reachable + version 3.x"
// check; this spec goes one level deeper and validates that key endpoints
// the frontend depends on are present in the spec. Catches contract drift
// where a controller is renamed or moved without the API client updating.
//
// Uses base `test` (no fixture) since /api/v1/openapi is permitAll.

import { test, expect } from '@playwright/test'

const BACKEND = 'http://localhost:8080'

test.describe('OpenAPI spec @smoke', () => {
  test('public endpoint returns a well-formed 3.x spec with key paths', async ({ request }) => {
    const response = await request.get(`${BACKEND}/api/v1/openapi`)
    expect(response.ok()).toBe(true)

    const body = await response.json()

    // Top-level shape — OpenAPI 3.x with title and version populated.
    expect(body.openapi).toMatch(/^3\.\d+(\.\d+)?$/)
    expect(body.info?.title).toBeTruthy()
    expect(body.info?.version).toBeTruthy()

    // Sanity-check that controllers the frontend depends on are present.
    // If any of these go missing, generated TypeScript types break and
    // the whole frontend stops typechecking — surface that here.
    expect(body.paths).toBeDefined()
    expect(body.paths['/api/v1/auth/login']).toBeDefined()
    expect(body.paths['/api/v1/superadmin/directories']).toBeDefined()
    expect(body.paths['/api/v1/superadmin/admins']).toBeDefined()
  })

  test('endpoint requires no authentication', async ({ request }) => {
    // Hit it with an explicitly empty cookie jar to confirm the endpoint
    // does NOT redirect or 401 when unauthenticated. (Prereq C contract:
    // OpenAPI must be reachable without a session so external clients can
    // fetch it for codegen.)
    const response = await request.get(`${BACKEND}/api/v1/openapi`, {
      headers: { Cookie: '' },
    })
    expect(response.status()).toBe(200)
  })
})
