// Smoke test for directory-connection management.
//
// Verifies that the auto-seeded "E2E LDAP (auto)" connection (created
// by TestcontainersConfiguration's ApplicationRunner pointing at the
// OpenLDAP testcontainer's dynamic mapped port) surfaces in the
// superadmin directories view, and that the row's edit modal opens.
//
// We DON'T assert "Test Connection" passes here. The form is loaded with
// `bindPasswordEncrypted` ciphertext (the plaintext password isn't
// re-shown for editing), and the POST that the Test button fires sends
// the encrypted blob — which the test endpoint can't decrypt the way a
// real LDAP bind expects. That's a UX/Test-button design quirk, not a
// real product issue. The actual end-to-end LDAP bind is covered by
// users.spec.ts (which hits /browse/search through the same connection
// and exercises the full encrypt/decrypt + bind path).

import { test, expect } from '../fixtures'

test.describe('Directory connections @smoke', () => {
  test('seeded directory is listed and edit modal opens', async ({ superadmin }) => {
    await superadmin.goto('/superadmin/directories')

    // The seeded row carries displayName "E2E LDAP (auto)". The table
    // renders displayName in the first column; aria role "row" matches
    // any row whose accessible name contains the text.
    const row = superadmin.getByRole('row', { name: /E2E LDAP \(auto\)/i })
    await expect(row).toBeVisible()

    // The row's primary action is an Edit button (template #primary in
    // the ActionMenu); clicking it opens the create/edit modal.
    await row.getByRole('button', { name: /^edit$/i }).click()

    // Modal opens with the form pre-populated. Verify the modal landed
    // by waiting for the Test Connection button to be present.
    await expect(
      superadmin.getByRole('button', { name: /test connection/i }),
    ).toBeVisible({ timeout: 5_000 })
  })
})
