/**
 * Compact-density regression smoke test.
 *
 * Behavioural validation that compact mode measurably shrinks form controls
 * and buttons across the key pages of the superadmin workspace.
 *
 * ## Why this exists
 *
 * Source-level audits catch most density issues but miss a class of problems
 * that only show up at render time:
 *
 *   1. Hand-rolled utility classes instead of `.input` / `.btn-*` — the CSS
 *      selectors never match so density has no effect.
 *   2. Near-duplicate components (DnPicker / GroupDnPicker) that look like
 *      the canonical component but have diverged in markup.
 *   3. Pages not in the audited set — new pages added after the last audit.
 *   4. `h-[38px]` or similar arbitrary-value height overrides that prevent
 *      padding reduction from shrinking the rendered height even when the
 *      correct class IS present.
 *
 * This test catches all four: it measures actual bounding-box heights, so it
 * fails regardless of *why* the element didn't shrink.
 *
 * ## Strategy: per-page comparison
 *
 * The test compares each page against ITSELF in the two density modes,
 * never cross-page. This avoids two failure modes the cross-page approach
 * had:
 *
 *   - Different pages have different button heights for legitimate layout
 *     reasons (a button next to a labelled select sits in a different
 *     containing-flex shape than a standalone button). Cross-page assertions
 *     conflate "didn't shrink under compact mode" with "is naturally a
 *     different size from the baseline page".
 *
 *   - One bad baseline page (zero visible controls due to test-fixture state)
 *     would short-circuit the whole test. Per-page lets each page be
 *     independent: a page with no controls is silently skipped, a page with
 *     controls is asserted on its own merits.
 *
 *   1. Toggle to comfortable density (clean state).
 *   2. Walk pages, measuring each — record the comfortable heights.
 *   3. Toggle to compact.
 *   4. Walk the same pages, measuring each — record the compact heights.
 *   5. For each page, assert per-control heights shrunk by ≥ MIN_HEIGHT_DELTA.
 *   6. Restore comfortable for cleanliness.
 *
 * ## What is NOT tested here
 *
 *   - Screenshot / pixel comparison (flaky cross-platform — avoided by design).
 *   - Exhaustive measurement of every control on every page (cost-prohibitive).
 *   - Mobile or responsive layouts (not in scope for the density feature).
 */

import { type Page } from '@playwright/test'
import { test, expect } from '../fixtures'
import { setDensity, measureControls, type ControlHeights } from '../helpers/density'

// Minimum height reduction (px) required to consider the control "shrunken".
// Comfortable .input padding: 0.5rem top + 0.5rem bottom = 16px at 16px/rem.
// Compact .input padding:     0.25rem top + 0.25rem bottom = 8px at 16px/rem.
// Expected delta on padding alone: 8px. We assert ≥ 4px for headroom.
const MIN_HEIGHT_DELTA = 4

const PAGES_WITH_CONTROLS: Array<{ path: string; label: string }> = [
  { path: '/superadmin/identities',      label: 'Identities / HybridSearch' },
  { path: '/superadmin/sod-policies',    label: 'SoD Policies' },
  { path: '/superadmin/access-drift',    label: 'Access Drift Policy' },
  { path: '/superadmin/approvals',       label: 'Pending Approvals' },
  { path: '/superadmin/directories',     label: 'Directory Connections' },
  { path: '/superadmin/profiles',        label: 'Provisioning Profiles' },
  { path: '/settings',                   label: 'Application Settings' },
  { path: '/superadmin/admins',          label: 'Application Accounts' },
]

async function navigateAndSettle(page: Page, path: string): Promise<void> {
  await page.goto(path)
  // Wait for any in-flight API calls to settle. Most pages mount Vue
  // composables that immediately fetch directories / policies / etc.,
  // and the controls (directory pickers especially) only render once
  // those resolve. networkidle catches that without us needing to know
  // which selector to wait for. Capped at 5s — long enough for the
  // local Tomcat APIs, short enough that genuinely-broken pages don't
  // burn the whole test budget.
  await page.waitForLoadState('networkidle', { timeout: 5_000 }).catch(() => {})
}

test.describe('Compact density coverage @smoke', () => {
  test('compact density shrinks controls on each page @smoke', async ({ superadmin }) => {
    // This test walks 8 pages × 2 density passes + 2 setDensity dialog
    // flows + a networkidle wait per nav. On CI the budget can run 60-90s.
    // Default per-test timeout is 30s — bump to 2 minutes for headroom.
    test.setTimeout(120_000)

    // ── 1. Start in comfortable mode (clean previous test state) ──────────
    // Land on a stable page so the sidebar exists for setDensity to click.
    await superadmin.goto('/superadmin/directories')
    await setDensity(superadmin, 'comfortable')

    // ── 2. Comfortable measurements (one pass over all pages) ─────────────
    const comfortableHeights = new Map<string, ControlHeights>()
    for (const { path, label } of PAGES_WITH_CONTROLS) {
      await navigateAndSettle(superadmin, path)
      const m = await measureControls(superadmin)
      comfortableHeights.set(path, m)
      // Diagnostic: log to the Playwright report so flaky measurements are
      // easy to investigate from CI artefacts.
      // eslint-disable-next-line no-console
      console.log(`[density] comfortable "${label}" → input=${m.input} btn=${m.btn}`)
    }

    // ── 3. Switch to compact ──────────────────────────────────────────────
    await navigateAndSettle(superadmin, '/superadmin/directories')
    await setDensity(superadmin, 'compact')

    // ── 4. Compact measurements + per-page assertions ─────────────────────
    let assertedAtLeastOnce = false
    for (const { path, label } of PAGES_WITH_CONTROLS) {
      await navigateAndSettle(superadmin, path)
      const compact = await measureControls(superadmin)
      const comfortable = comfortableHeights.get(path)!
      // eslint-disable-next-line no-console
      console.log(`[density] compact     "${label}" → input=${compact.input} btn=${compact.btn}`)

      // Per-control-type assertions. Skip a control type when EITHER mode
      // returned 0 — the page genuinely doesn't have that control visible
      // (e.g. a page with only buttons and no inputs, or vice versa).
      // The test fails only when a control IS present in BOTH modes but
      // didn't shrink.
      if (comfortable.input > 0 && compact.input > 0) {
        expect(
          compact.input,
          `.input on "${label}" (${path}): compact ${compact.input}px should be < comfortable ${comfortable.input}px - ${MIN_HEIGHT_DELTA}px`,
        ).toBeLessThan(comfortable.input - MIN_HEIGHT_DELTA)
        assertedAtLeastOnce = true
      }

      if (comfortable.btn > 0 && compact.btn > 0) {
        expect(
          compact.btn,
          `btn on "${label}" (${path}): compact ${compact.btn}px should be < comfortable ${comfortable.btn}px - ${MIN_HEIGHT_DELTA}px`,
        ).toBeLessThan(comfortable.btn - MIN_HEIGHT_DELTA)
        assertedAtLeastOnce = true
      }
    }

    // Sanity: at least one page had a measurable control in both modes.
    // If this fails, EVERY page returned zero heights — the test has lost
    // its grip on the application (selector drift, broken fixture, etc.)
    // and any "passes" above were vacuous.
    expect(
      assertedAtLeastOnce,
      'No page had a visible .input or .btn-* in both density modes — fixture or selector drift suspected',
    ).toBe(true)

    // ── 5. Restore comfortable for cleanliness ────────────────────────────
    await navigateAndSettle(superadmin, '/superadmin/directories')
    await setDensity(superadmin, 'comfortable')
  })
})
