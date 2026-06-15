// Smoke test for the ISVA config control plane.
//
// We don't drive the full UI flow here — that requires a real ISVA-
// ready directory + the secUser overlay schema loaded, neither of
// which the demo fixture has. Instead we verify the API endpoints
// the IsvaConfigView depends on are wired correctly:
//
//   - GET returns RFC 7807 404 when no config exists (the new-
//     install state the view tolerates by keeping form defaults).
//   - PUT INLINE creates a row and returns the populated DTO.
//   - PUT LINKED rejects empty managementDitBaseDn with 400 +
//     "managementDitBaseDn is required" detail — matches what the
//     view's canSave guard prevents but the server enforces too.
//   - POST /probe returns OK on an inline-mode config.
//
// If any of those break, IsvaConfigView's save-then-load round-trip
// silently breaks and operators get unhelpful 500s. The smoke catches
// the wiring regressions, not the UI-rendering regressions.

import { test, expect } from '../fixtures'
import { seededDirectoryId } from '../helpers/directory'

test.describe('ISVA config control plane @smoke', () => {
  test('GET returns 404 problem when no config exists', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)
    const response = await superadmin.request.get(
      `/api/v1/directories/${directoryId}/isva-config`,
    )
    expect(response.status()).toBe(404)

    const body = await response.json()
    expect(body.status).toBe(404)
    expect(body.detail).toMatch(/PUT a config to create one/i)
  })

  test('PUT inline creates config and round-trips through GET', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)

    // PUT
    const putResponse = await superadmin.request.put(
      `/api/v1/directories/${directoryId}/isva-config`,
      {
        data: {
          enabled: false,
          topologyMode: 'INLINE',
          secAuthority: 'Default',
          defaultValidUntilYears: 100,
          deletePolicy: 'DISABLE',
          requireSecGroup: true,
          managementDitBaseDn: null,
          secuserRdnAttribute: null,
          groupMemberTarget: null,
          onDemographicDelete: null,
        },
      },
    )
    expect(putResponse.status()).toBe(200)
    const created = await putResponse.json()
    expect(created.topologyMode).toBe('INLINE')
    expect(created.enabled).toBe(false)
    expect(created.managementDitBaseDn).toBeNull()
    expect(created.updatedBy).toBeTruthy()

    // GET round-trip
    const getResponse = await superadmin.request.get(
      `/api/v1/directories/${directoryId}/isva-config`,
    )
    expect(getResponse.status()).toBe(200)
    const fetched = await getResponse.json()
    expect(fetched.topologyMode).toBe('INLINE')
    expect(fetched.defaultValidUntilYears).toBe(100)
  })

  test('PUT linked without managementDitBaseDn is rejected with 400', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)

    const response = await superadmin.request.put(
      `/api/v1/directories/${directoryId}/isva-config`,
      {
        data: {
          enabled: true,
          topologyMode: 'LINKED',
          secAuthority: 'Default',
          defaultValidUntilYears: 100,
          deletePolicy: 'DISABLE',
          requireSecGroup: true,
          managementDitBaseDn: '',  // <- the invalid bit
          secuserRdnAttribute: 'secUUID',
          groupMemberTarget: 'DEMOGRAPHIC_DN',
          onDemographicDelete: 'LEAVE',
        },
      },
    )
    expect(response.status()).toBe(400)
    const body = await response.json()
    expect(body.detail || body.message || '').toMatch(/managementDitBaseDn is required/i)
  })

  test('POST /probe succeeds in inline mode (no DIT to probe)', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)
    // Ensure an inline config exists.
    await superadmin.request.put(
      `/api/v1/directories/${directoryId}/isva-config`,
      {
        data: {
          enabled: false,
          topologyMode: 'INLINE',
          secAuthority: 'Default',
          defaultValidUntilYears: 100,
          deletePolicy: 'DISABLE',
          requireSecGroup: true,
          managementDitBaseDn: null,
          secuserRdnAttribute: null,
          groupMemberTarget: null,
          onDemographicDelete: null,
        },
      },
    )

    const response = await superadmin.request.post(
      `/api/v1/directories/${directoryId}/isva-config/probe`,
      { data: {} },
    )
    expect(response.status()).toBe(200)
    const body = await response.json()
    // Inline mode: probe is vacuously OK (no management DIT
    // to reach) and warnings carry the "nothing to probe" note.
    expect(body.reachable).toBe(true)
    expect(body.sampleSecUserFound).toBe(true)
    expect(body.warnings.join(' ')).toMatch(/inline mode/i)
  })
})
