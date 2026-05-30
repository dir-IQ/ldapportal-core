// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for UserPreferencesDialog.
 *
 * Targeted at the two regressions fixed in commit 375800d:
 *   1. Density wasn't persisting because the dialog stayed open after
 *      Save and a subsequent click-outside reverted the localStorage
 *      write via cancelDialog → revertDensityIfChanged().
 *   2. Save button didn't close the dialog ('close' was never emitted).
 *
 * The fixes:
 *   - doSavePrefs bumps `initialDensity` to the saved value, so a
 *     subsequent revert sees no diff.
 *   - doSavePrefs emit('close') on success.
 *   - (Historical) Backdrop-click handling guarded against mid-flight
 *     dismissal while saving. Backdrop click is now a no-op
 *     unconditionally — see UserPreferencesDialog.vue and the
 *     matching AppModal change — so the saving-guard branch the
 *     original regression spawned no longer fires by any path.
 *
 * The auth/api/composable dependencies are mocked at the module level
 * — these are pure component tests, not integration tests.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

// Mock the auth API: updatePreferences returns success by default.
vi.mock('@/api/auth', () => ({
  updatePreferences: vi.fn().mockResolvedValue({ data: {} }),
  changePassword: vi.fn().mockResolvedValue({ data: {} }),
}))
import { updatePreferences } from '@/api/auth'
const mockUpdatePreferences = vi.mocked(updatePreferences)

// useDensity is the real composable — we want the real localStorage
// + data-density side-effects to be exercised, since that's the
// surface the regression bit on.
import { useDensity } from '@/composables/useDensity'

import UserPreferencesDialog from './UserPreferencesDialog.vue'

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  document.documentElement.removeAttribute('data-density')
  // useDensity is a module-level singleton: its `density` ref persists
  // across tests in the same file and is NOT reset by clearing
  // localStorage. Without this explicit setDensity('comfortable'),
  // the second and subsequent tests would inherit the previous test's
  // density value as their `initialDensity`, making
  // revertDensityIfChanged a no-op when it should revert.
  useDensity().setDensity('comfortable')
  mockUpdatePreferences.mockClear()
  mockUpdatePreferences.mockResolvedValue({
    data: {},
    status: 200,
    statusText: 'OK',
    headers: {},
    config: {},
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any)
})

function clickDensityButton(w: ReturnType<typeof mount>, label: string) {
  const btn = w.findAll('button').find(b => b.text() === label)
  if (!btn) throw new Error(`density button "${label}" not found`)
  return btn.trigger('click')
}

describe('UserPreferencesDialog density save behaviour', () => {
  it('saves density on Save and emits close', async () => {
    const w = mount(UserPreferencesDialog)

    await clickDensityButton(w, 'Compact')
    // Live preview applied.
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')

    const saveBtn = w.findAll('button').find(b => b.text() === 'Save')!
    await saveBtn.trigger('click')
    await flushPromises()

    // Save call fired with the density preference in the payload — guards
    // against silent payload-shape drift when fields are added/removed.
    expect(mockUpdatePreferences).toHaveBeenCalledTimes(1)
    expect(mockUpdatePreferences).toHaveBeenCalledWith(
      expect.objectContaining({ densityPreference: 'compact' }),
    )
    // Dialog emitted close (the bug-#2 fix).
    expect(w.emitted('close')).toBeTruthy()
    expect(w.emitted('close')!.length).toBe(1)
    // Density is still compact in localStorage and on <html>.
    expect(localStorage.getItem('ldapportal-density')).toBe('compact')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
  })

  it('density survives cancelDialog called after a successful save (initialDensity bump)', async () => {
    // Direct guard for the `initialDensity = form.value.densityPreference`
    // line in doSavePrefs. Without that bump, calling cancelDialog after
    // a save would see form.densityPreference !== initialDensity (because
    // initialDensity would still be the pre-save value) and revert via
    // setDensity(initialDensity) — silently undoing the user's save.
    //
    // In normal runtime use the dialog closes on save and the user can't
    // reach cancelDialog. But the bump is the actual safety net; without
    // it any future change that keeps the dialog open after save (e.g.
    // queueing multiple edits) would reintroduce the bug. cancelDialog
    // is exposed via defineExpose specifically for this test.
    const w = mount(UserPreferencesDialog)

    await clickDensityButton(w, 'Compact')
    const saveBtn = w.findAll('button').find(b => b.text() === 'Save')!
    await saveBtn.trigger('click')
    await flushPromises()
    expect(localStorage.getItem('ldapportal-density')).toBe('compact')

    // Now invoke cancelDialog directly and assert the density stays.
    // If the initialDensity bump were removed, this call would revert
    // density to 'comfortable'.
    const vm = w.vm as unknown as { cancelDialog: () => void }
    vm.cancelDialog()

    expect(localStorage.getItem('ldapportal-density')).toBe('compact')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
    const { density } = useDensity()
    expect(density.value).toBe('compact')
  })

  it('reverts density on Cancel', async () => {
    const w = mount(UserPreferencesDialog)

    await clickDensityButton(w, 'Compact')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')

    const cancelBtn = w.findAll('button').find(b => b.text() === 'Cancel')!
    await cancelBtn.trigger('click')

    // Cancel reverts the live preview.
    expect(document.documentElement.getAttribute('data-density')).toBe(null)
    expect(localStorage.getItem('ldapportal-density')).toBe('comfortable')
    // And emits close.
    expect(w.emitted('close')).toBeTruthy()
  })

  it('keeps the dialog open and shows error on save failure', async () => {
    mockUpdatePreferences.mockRejectedValueOnce({
      response: { data: { detail: 'backend exploded' } },
    })

    const w = mount(UserPreferencesDialog)

    await clickDensityButton(w, 'Compact')
    const saveBtn = w.findAll('button').find(b => b.text() === 'Save')!
    await saveBtn.trigger('click')
    await flushPromises()

    // Dialog did NOT close on save failure.
    expect(w.emitted('close')).toBeFalsy()
    // Error message visible.
    expect(w.text()).toContain('backend exploded')
    // Live-preview density is still applied (user can retry without
    // re-clicking Compact).
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
  })
})
