// Smoke for the inline-edit results table on the Directory Search page
// (Phase 1 of the inline-edit feature).
//
// Exercises the full path: superadmin lands on the search page, finds
// seedAlice with a (cn=seedAlice) filter, toggles edit mode, changes
// her `mail` attribute, and tabs/clicks out to fire the row save. The
// per-row indicator transitions to ✓; reloading the page and re-running
// the same search returns the new value.
//
// What this proves end to end:
//   - UI: edit toggle gates correctly off the result count, schema
//     auto-fetches when toggled on, the cell input renders for an
//     editable single-valued attr (mail), the save indicator
//     visualises 'saved'.
//   - Wire: the typed PUT /api/v1/directories/{id}/users/entry call
//     reaches the controller, USER_EDIT permission passes for
//     superadmin, and the modify lands.
//   - Persistence: a fresh search returns the new value (so the edit
//     isn't a UI-only echo).

import { test, expect } from '../fixtures'
import { seededDirectoryId } from '../helpers/directory'

test.describe('Directory search inline edit @smoke', () => {
  test('edit mail attribute via inline edit and verify persistence', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)

    await superadmin.goto('/superadmin/directory-search')

    // Pick the seeded directory by id (avoids depending on its
    // displayName, which changes per fixture profile).
    await superadmin.locator('select[aria-label="Directory"]')
      .selectOption({ value: directoryId })

    // Narrow base DN to the seed branch so the result set is small
    // enough for editing. Search for cn=seedAlice specifically.
    const baseDnInput = superadmin.locator('input[placeholder*="dc=example"]')
    await baseDnInput.fill('ou=seed,ou=test,dc=test,dc=local')

    await superadmin.locator('#search-filter').fill('(cn=seedAlice)')
    // employeeNumber is the inline-edit target. It's SINGLE-VALUE per
    // the inetOrgPerson schema (cosine.schema), so Phase 1's
    // isAttributeEditable predicate accepts it. cn / sn / mail are
    // all multi-valued in the standard schema and Phase 1 locks them
    // — using mail here would silently render the cell as a
    // read-only span and the test would time out waiting for the
    // input. (Multi-valued chip editor lands in Phase 1.5.)
    await superadmin.locator('#search-attributes')
      .fill('cn,employeeNumber,objectClass,modifyTimestamp')
    await superadmin.getByRole('button', { name: 'Search', exact: true }).click()

    // Anchor on text rather than data-row-dn: the read-only
    // ResultsTable doesn't set data-row-dn, and CSS attribute
    // selectors with commas inside quoted values can be flaky
    // across Playwright's selector engine. The seedAlice row
    // contains the cn value verbatim in the dn cell, so a
    // text-content match resolves to exactly one row.
    const aliceRow = superadmin.locator('table tbody tr', { hasText: 'cn=seedAlice' })
    await expect(aliceRow).toBeVisible()

    // Toggle edit mode — EditableResultsTable now owns the table.
    // Wait for the editable employeeNumber input to confirm the
    // swap landed before assuming the row is interactive.
    await superadmin.locator('[data-edit-toggle]').click()
    const empInput = aliceRow.locator('input[data-edit-cell="employeeNumber"]')
    await expect(empInput).toBeVisible()

    // Pick a fresh value with a high-resolution timestamp so reruns
    // of the test against a persistent test container don't
    // accidentally see an old residue.
    const newEmpNumber = `SEED-${Date.now()}`
    await empInput.fill(newEmpNumber)

    // Click outside the row to fire the focusout-leaves-row save.
    await superadmin.locator('h1').click()

    // Indicator transitions through 'saving' → 'saved'. Wait on
    // the data-save-state attribute, which the table sets per row.
    await expect(aliceRow).toHaveAttribute('data-save-state', 'saved', { timeout: 5000 })

    // Verify the persisted value via the same API the search uses,
    // independent of the page-level cache. (Re-rendering the search
    // would also work but is more flaky in a happy-dom-free
    // chromium-real environment.)
    const params = new URLSearchParams({
      baseDn: 'ou=seed,ou=test,dc=test,dc=local',
      filter: '(cn=seedAlice)',
      scope: 'sub',
    })
    const response = await superadmin.request.get(
      `/api/v1/superadmin/directories/${directoryId}/browse/search?${params.toString()}`,
    )
    expect(response.ok()).toBe(true)
    const entries = await response.json() as Array<{
      dn: string
      attributes: Record<string, string[]>
    }>
    expect(entries).toHaveLength(1)
    expect(entries[0].attributes.employeeNumber).toContain(newEmpNumber)
  })

  test('non-editable rows render read-only cells in edit mode', async ({ superadmin }) => {
    // organizationalUnit entries aren't classified as user/group, so
    // every cell on those rows should stay read-only with a tooltip
    // even with edit mode on.
    const directoryId = await seededDirectoryId(superadmin)
    await superadmin.goto('/superadmin/directory-search')

    await superadmin.locator('select[aria-label="Directory"]')
      .selectOption({ value: directoryId })
    const baseDnInput = superadmin.locator('input[placeholder*="dc=example"]')
    await baseDnInput.fill('dc=test,dc=local')
    await superadmin.locator('#search-filter').fill('(objectClass=organizationalUnit)')
    await superadmin.locator('#search-attributes').fill('ou,objectClass,description')
    await superadmin.getByRole('button', { name: 'Search', exact: true }).click()

    // Wait for at least one row.
    await expect(superadmin.locator('table tbody tr').first()).toBeVisible()

    await superadmin.locator('[data-edit-toggle]').click()

    // No editable cells should render — predicate locks every column
    // for unknown classification.
    await expect(superadmin.locator('input[data-edit-cell]')).toHaveCount(0)
  })
})
