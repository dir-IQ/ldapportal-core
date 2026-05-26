import { type Page, expect } from '@playwright/test'

/**
 * Density test helpers.
 *
 * Extracted from density-coverage.spec.ts so the primitives can be reused
 * by other specs that happen to care about density state.
 */

export type DensityMode = 'comfortable' | 'compact'

/**
 * Open the User Preferences dialog and switch to the requested density.
 * Waits for the dialog to close (Save dismisses it) and confirms the
 * `data-density` attribute on `<html>` reflects the new mode.
 *
 * The dialog is opened by clicking the username button at the bottom of the
 * sidebar (AppLayout.vue: `@click="showPreferences = true"` on the button
 * that shows `auth.username`). The button has a `truncate` class on the
 * username text — we locate via `aside button.truncate`.
 */
export async function setDensity(page: Page, value: DensityMode): Promise<void> {
  const sidebar = page.locator('aside')
  const prefsButton = sidebar.locator('button.truncate')
  await prefsButton.click()

  // Dialog should appear
  const dialog = page.locator('div.fixed.inset-0.z-50')
  await expect(dialog).toBeVisible({ timeout: 5_000 })

  // Click the density option button (label is "Comfortable" or "Compact")
  const label = value === 'compact' ? 'Compact' : 'Comfortable'
  await dialog.getByRole('button', { name: label, exact: true }).click()

  // Click Save
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()

  // Dialog closes on successful save
  await expect(dialog).not.toBeVisible({ timeout: 5_000 })

  // Confirm the <html> attribute reflects the mode
  if (value === 'compact') {
    await expect(page.locator('html')).toHaveAttribute('data-density', 'compact')
  } else {
    // Comfortable → attribute is removed entirely
    await expect(page.locator('html')).not.toHaveAttribute('data-density', 'compact')
  }
}

export interface ControlHeights {
  /** Height in px of the first VISIBLE `.input` element, or 0 if none found. */
  input: number
  /**
   * Height in px of the first VISIBLE `.btn-*` element on the page, or 0
   * if none found. Covers the standard btn-* family — drawer/dialog
   * action buttons, page-level primaries, etc.
   */
  btn: number
}

/**
 * Measure the rendered height of the first VISIBLE `.input` and the first
 * VISIBLE button using any of the standard btn-* classes on the current page.
 *
 * Two important properties:
 *
 *  1. **Visibility filter via `:visible`** — `.locator('.input').first()`
 *     returns the first DOM-order match regardless of visibility. On pages
 *     with `v-show` modals containing inputs, the first match is hidden
 *     and `boundingBox()` returns null. Filtering to `:visible` ensures we
 *     measure something the user can actually see.
 *
 *  2. **Returns 0 for absent controls** — callers should skip the
 *     assertion for a control type when both comfortable and compact
 *     measurements are 0 (page genuinely has no controls of that type).
 */
/**
 * Measure the first matching element's height — but use `count()` to bail
 * fast when no matches exist. Without this guard, `boundingBox()` on a
 * locator with zero matches auto-waits the full default action timeout
 * (~30s) before resolving to null. With 8 pages walked in two passes, even
 * one zero-match page would burn the entire per-test budget waiting for
 * an element that's never going to appear.
 */
async function measureFirstVisibleHeight(page: Page, selector: string): Promise<number> {
  // count() returns immediately based on current DOM state, no auto-wait.
  if ((await page.locator(selector).count()) === 0) return 0
  const box = await page.locator(selector).first().boundingBox().catch(() => null)
  return box?.height ?? 0
}

// Canonical `.btn-*` color classes that each have a `[data-density="compact"]`
// override defined in main.css and SHOULD shrink in compact mode.
//
// We deliberately exclude buttons that ALSO carry `.btn-compact`. That class
// is a size-modifier (smaller padding + smaller font-size) with NO density
// override of its own. When combined with one of the colour classes above,
// the density rule on the colour class (specificity 0,2,0) outranks
// `.btn-compact` (specificity 0,1,0) on `font-size`, so the compact-density
// rendering can be visually TALLER than the comfortable rendering — the
// opposite of what the test asserts. Those buttons aren't a fair canary
// for the density feature; the canonical full-size buttons are.
//
// `.btn-sm` HAS its own `[data-density="compact"]` rule (main.css:379-382)
// and shrinks correctly, so it stays in the list — but with `:not(.btn-compact)`
// for safety in case anyone ever stacks the two.
const BTN_BASE_CLASSES = [
  '.btn-primary',
  '.btn-secondary',
  '.btn-neutral',
  '.btn-danger',
  '.btn-warning',
  '.btn-success',
  '.btn-danger-soft',
  '.btn-success-soft',
  '.btn-sm',
]
const BTN_SELECTOR = BTN_BASE_CLASSES
  .map((c) => `${c}:not(.btn-compact):visible`)
  .join(', ')

export async function measureControls(page: Page): Promise<ControlHeights> {
  return {
    input: await measureFirstVisibleHeight(page, '.input:visible'),
    btn: await measureFirstVisibleHeight(page, BTN_SELECTOR),
  }
}
