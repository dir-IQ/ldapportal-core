// Smoke test for the per-profile ISVA override control plane.
//
// Like isva-config.spec.ts this is API-level, not a full UI drive — it
// pins the wiring the IsvaProfileOverrideControl depends on:
//
//   - GET returns INHERIT for a profile with no override row (the
//     default the control renders unchecked).
//   - PUT FORCE_OFF persists and round-trips through GET (the headline
//     "exempt this profile" toggle).
//   - PUT INHERIT flips it back.
//   - PUT with a missing override is rejected 400 (the @NotNull guard).
//
// If any break, the control's load/save round-trip silently breaks.

import { test, expect } from '../fixtures'
import { seededDirectoryId } from '../helpers/directory'

test.describe('ISVA profile override control plane @smoke', () => {
  test('FORCE_OFF round-trips through GET, INHERIT flips it back', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)

    // Create a throwaway profile to attach the override to (unique
    // name so reruns don't hit the name-uniqueness conflict).
    const name = `e2e-isva-override-${Date.now()}`
    const createRes = await superadmin.request.post(
      `/api/v1/directories/${directoryId}/profiles`,
      {
        data: {
          name,
          targetOuDn: 'ou=people,dc=example,dc=com',
          objectClassNames: ['inetOrgPerson'],
          rdnAttribute: 'uid',
        },
      },
    )
    expect(createRes.status()).toBe(201)
    const profileId = (await createRes.json()).id as string

    const base = `/api/v1/directories/${directoryId}/profiles/${profileId}/isva-override`
    try {
      // Default — no row yet → INHERIT.
      const initial = await superadmin.request.get(base)
      expect(initial.status()).toBe(200)
      expect((await initial.json()).override).toBe('INHERIT')

      // PUT FORCE_OFF.
      const put = await superadmin.request.put(base, { data: { override: 'FORCE_OFF' } })
      expect(put.status()).toBe(200)
      expect((await put.json()).override).toBe('FORCE_OFF')

      // GET round-trip.
      const fetched = await superadmin.request.get(base)
      expect(fetched.status()).toBe(200)
      expect((await fetched.json()).override).toBe('FORCE_OFF')

      // Flip back to INHERIT.
      const back = await superadmin.request.put(base, { data: { override: 'INHERIT' } })
      expect(back.status()).toBe(200)
      expect((await back.json()).override).toBe('INHERIT')

      // Missing override → 400 (the @NotNull guard).
      const bad = await superadmin.request.put(base, { data: {} })
      expect(bad.status()).toBe(400)

      // Unknown profile id under this directory → 404 (FK guard, not a
      // 500 from a DataIntegrityViolation on INSERT).
      const bogus = await superadmin.request.put(
        `/api/v1/directories/${directoryId}/profiles/00000000-0000-0000-0000-000000000000/isva-override`,
        { data: { override: 'FORCE_OFF' } },
      )
      expect(bogus.status()).toBe(404)
    } finally {
      await superadmin.request.delete(
        `/api/v1/directories/${directoryId}/profiles/${profileId}`,
      )
    }
  })
})
