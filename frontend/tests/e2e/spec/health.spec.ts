import { test, expect } from '../fixtures'

// Smoke baseline: prove the harness wires the backend, frontend, and auth
// fixtures together end-to-end. Tagged @smoke so it runs on every PR via
// e2e-smoke.yml; the full suite (slower, broader) runs on a separate cadence.
test.describe('App health @smoke', () => {
  test('superadmin can load the dashboard', async ({ superadmin }) => {
    await superadmin.goto('/')
    // The router redirects authenticated SUPERADMIN sessions from `/` to `/dashboard`.
    // The regex requires one of those segments at the URL tail; bare `/` would mean
    // the redirect didn't happen.
    await expect(superadmin).toHaveURL(/\/(dashboard|superadmin|home)$/i)
  })

  test('actuator health endpoint returns UP', async ({ request }) => {
    const response = await request.get('http://localhost:8080/actuator/health')
    expect(response.ok()).toBe(true)
    const body = await response.json()
    expect(body.status).toBe('UP')
  })

  test('OpenAPI spec is publicly reachable', async ({ request }) => {
    const response = await request.get('http://localhost:8080/api/v1/openapi')
    expect(response.ok()).toBe(true)
    const body = await response.json()
    expect(body.openapi).toMatch(/^3\./)
  })
})
