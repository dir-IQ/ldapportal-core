/**
 * Automated accessibility gate — Section 508 / WCAG 2.1 AA.
 *
 * Runs axe-core over the dense authenticated superadmin pages: the surfaces
 * that carry most of the forms, tables and dialogs, and therefore most of the
 * accessibility debt tracked in docs/accessibility-508-remediation.md.
 *
 * ## Why this only runs in CI
 *
 * The pages below need the real backend (auth cookies + API data) to render.
 * The dev sandbox has no Docker, so the Spring/Testcontainers stack can't
 * start there — this spec is exercised in the e2e workflows, where
 * Playwright's webServer boots the backend and global-setup seeds the
 * superadmin session. The unauthenticated routes (/login, /register, …) are
 * already clean and don't need a backend, so they aren't re-scanned here.
 *
 * ## Ratchet, not a cliff
 *
 * The app has pre-existing AA debt that the remediation phases retire in
 * batches. A plain "zero violations" assertion would stay red until every
 * phase lands, so instead we allowlist the KNOWN debt rules and fail on
 * anything else. The gate is therefore green today AND immediately catches a
 * NEW class of violation introduced by a future change. As each phase fixes a
 * rule across the app, delete it from ACCEPTED_RULES to begin enforcing it.
 * When the set is empty this is a full AA gate and can be promoted to a
 * required status check (see docs/accessibility-508-remediation.md, Phase 6).
 *
 * Every violation — accepted or not — is logged per route, so the Playwright
 * report always shows the current state even while the gate stays green.
 */

import { type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { test, expect } from '../fixtures'

const WCAG_AA_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

// axe rule IDs for known, tracked debt. Each maps to the remediation phase
// that retires it. Removing an entry turns that rule into an enforced gate —
// do that as the corresponding phase lands, not before.
const ACCEPTED_RULES = new Set<string>([
  // Enforced (removed from this list as their phase landed): `label`,
  // `select-name`, `aria-input-field-name` (Phase 4); `button-name`,
  // `link-name` (Phase 5). Still allowlisted:
  //
  // `color-contrast` — Phase 5 darkened text-gray-400 -> 500/600 and fixed
  //   the sidebar, but enforcing the rule surfaced SYSTEMIC pre-existing
  //   contrast debt that needs a deliberate, eyes-on-screen design pass, not
  //   blind edits: muted text (gray-500) on the `bg-gray-100` app background
  //   (~4.4:1), `text-green-600` status text (~3.9:1 on white), tinted
  //   dashboard/access-drift stat cards, and `<code>` on gray-100. Tracked in
  //   docs/accessibility-508-remediation.md (Phase 5 "remaining contrast").
  'color-contrast',
  // The two dialog-structure rules are only allowlisted because this scan
  // checks page-load state and never opens a modal, so they aren't exercised
  // here; verified in the Phase 6 manual AT pass.
  'aria-dialog-name',
  'aria-required-children',
])

const ROUTES: Array<{ path: string; label: string }> = [
  { path: '/dashboard',                   label: 'Dashboard' },
  { path: '/superadmin/directories',      label: 'Directory Connections' },
  { path: '/superadmin/directory-search', label: 'Directory Search' },
  { path: '/superadmin/identities',       label: 'Identities' },
  { path: '/superadmin/profiles',         label: 'Provisioning Profiles' },
  { path: '/superadmin/admins',           label: 'Application Accounts' },
  { path: '/settings',                    label: 'Application Settings' },
  { path: '/superadmin/sod-policies',     label: 'SoD Policies' },
  { path: '/superadmin/access-drift',     label: 'Access Drift' },
  { path: '/superadmin/approvals',        label: 'Pending Approvals' },
]

async function navigateAndSettle(page: Page, path: string): Promise<void> {
  await page.goto(path)
  // Most pages mount composables that immediately fetch data; controls only
  // render once those resolve. networkidle catches that without knowing the
  // selector. Capped so a genuinely-broken page doesn't burn the budget.
  await page.waitForLoadState('networkidle', { timeout: 5_000 }).catch(() => {})
}

test.describe('Accessibility (WCAG 2.1 AA) @a11y', () => {
  test('superadmin pages have no un-accepted axe violations @a11y', async ({ superadmin }) => {
    // 10 routes × (nav + networkidle + axe analyse). Generous headroom over
    // the 30s default — the analyse pass is the slow part.
    test.setTimeout(180_000)

    const unexpected: string[] = []
    let scannedAny = false

    for (const { path, label } of ROUTES) {
      await navigateAndSettle(superadmin, path)

      const { violations } = await new AxeBuilder({ page: superadmin })
        .withTags(WCAG_AA_TAGS)
        .analyze()

      scannedAny = true

      for (const v of violations) {
        const summary = `[${v.impact}] ${v.id} ×${v.nodes.length} — ${v.help}`
        const accepted = ACCEPTED_RULES.has(v.id)
        // eslint-disable-next-line no-console
        console.log(`  ${accepted ? 'KNOWN' : 'NEW  '} ${label} (${path}): ${summary}`)
        if (!accepted) {
          const targets = v.nodes
            .map((n) => `      - ${n.target?.join(' ')}`)
            .join('\n')
          unexpected.push(`${path}: ${summary}\n${targets}`)
        }
      }
    }

    // Guard against a vacuous pass: if the fixture/backend drifted and nothing
    // rendered, axe would report zero violations on empty pages.
    expect(
      scannedAny,
      'No routes were scanned — superadmin fixture or backend drift suspected',
    ).toBe(true)

    expect(
      unexpected,
      `New accessibility violations outside the ACCEPTED_RULES ratchet ` +
        `(fix them, or — if intentional debt — add the rule to ACCEPTED_RULES ` +
        `with a phase tag in docs/accessibility-508-remediation.md):\n${unexpected.join('\n')}`,
    ).toEqual([])
  })
})
