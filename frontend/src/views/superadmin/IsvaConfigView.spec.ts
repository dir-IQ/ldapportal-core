// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for IsvaConfigView's topology-exposure logic:
 *   - selector hidden + mode pinned when one mode is exposed
 *   - selector shown when both are exposed
 *   - mismatch note when a persisted config uses a now-unexposed mode
 *
 * Router + the isvaConfig API are mocked at the module level. FormField is
 * stubbed — these tests assert structure (selector / panel / note), not the
 * DIT inputs.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const hoisted = vi.hoisted(() => ({
  getIsvaConfig: vi.fn(),
  getIsvaUiOptions: vi.fn(),
  upsertIsvaConfig: vi.fn(),
  probeIsvaConfig: vi.fn(),
  notifSuccess: vi.fn(),
  notifError: vi.fn(),
  confirmFn: vi.fn().mockResolvedValue(true),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'dir-1' } }),
  useRouter: () => ({ push: vi.fn() }),
  // The view declares onBeforeRouteLeave for unsaved-changes
  // protection — no-op in tests because there's no router instance
  // to call the hook.
  onBeforeRouteLeave: () => {},
}))

vi.mock('@/api/isvaConfig', () => ({
  getIsvaConfig: hoisted.getIsvaConfig,
  getIsvaUiOptions: hoisted.getIsvaUiOptions,
  upsertIsvaConfig: hoisted.upsertIsvaConfig,
  probeIsvaConfig: hoisted.probeIsvaConfig,
}))

// useNotificationStore is Pinia-backed and the tests don't install a
// Pinia instance — mock at the module level so the view's calls to
// notif.success / notif.error route through hoisted spies.
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({
    success: hoisted.notifSuccess,
    error: hoisted.notifError,
    info: vi.fn(),
    warning: vi.fn(),
  }),
}))

// useConfirm composable wraps a Pinia store too. Tests only exercise
// the topology-exposure logic, not the discard-on-leave path, so the
// fake always resolves true (i.e. "yes, discard").
vi.mock('@/composables/useConfirm', () => ({
  useConfirm: () => hoisted.confirmFn,
}))

import IsvaConfigView from './IsvaConfigView.vue'

const notFound = { response: { status: 404 } }

function inlineConfigDto() {
  return {
    enabled: true,
    topologyMode: 'INLINE',
    secAuthority: 'Default',
    defaultValidUntilYears: 100,
    deletePolicy: 'DISABLE',
    requireSecGroup: true,
    managementDitBaseDn: null,
    secuserRdnAttribute: null,
    groupMemberTarget: null,
    onDemographicDelete: null,
    createdAt: '', updatedAt: '', updatedBy: 'alice',
  }
}

async function mountView() {
  const wrapper = mount(IsvaConfigView, {
    global: { stubs: { FormField: true } },
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  // Reset every spy on every test — previously partial resets caused
  // cross-test pollution (e.g. upsertIsvaConfig call counts leaking
  // into the probe-gating tests).
  hoisted.getIsvaConfig.mockReset()
  hoisted.getIsvaUiOptions.mockReset()
  hoisted.upsertIsvaConfig.mockReset()
  hoisted.probeIsvaConfig.mockReset()
  hoisted.notifSuccess.mockReset()
  hoisted.notifError.mockReset()
})

describe('IsvaConfigView topology exposure', () => {
  it('hides the selector and pins the mode when only linked is exposed', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['LINKED'] } })
    hoisted.getIsvaConfig.mockRejectedValue(notFound)

    const wrapper = await mountView()

    expect(wrapper.find('input[name="topology"]').exists()).toBe(false)
    // LINKED pinned → the renamed secUser panel is shown.
    expect(wrapper.text()).toContain('Paired secUser settings')
    expect(wrapper.find('[data-testid="topology-mismatch"]').exists()).toBe(false)
  })

  it('shows the selector when both modes are exposed', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['INLINE', 'LINKED'] } })
    hoisted.getIsvaConfig.mockRejectedValue(notFound)

    const wrapper = await mountView()

    expect(wrapper.findAll('input[name="topology"]')).toHaveLength(2)
  })

  it('shows a mismatch note when a persisted mode is no longer exposed', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: inlineConfigDto() })

    const wrapper = await mountView()

    expect(wrapper.find('[data-testid="topology-mismatch"]').exists()).toBe(true)
    expect(wrapper.find('input[name="topology"]').exists()).toBe(false)
    // INLINE config → the linked-only secUser panel is hidden.
    expect(wrapper.text()).not.toContain('Paired secUser settings')
  })
})

describe('IsvaConfigView save / discard flow', () => {
  beforeEach(() => {
    hoisted.upsertIsvaConfig.mockReset()
    hoisted.notifSuccess.mockReset()
    hoisted.notifError.mockReset()
  })

  it('action bar is hidden when the form matches the loaded config', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['INLINE', 'LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: inlineConfigDto() })

    const wrapper = await mountView()

    // 'Unsaved changes' is the bar's label; absent → bar hidden.
    expect(wrapper.text()).not.toContain('Unsaved changes')
  })

  it('action bar appears when a field is edited and disappears after save', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['INLINE', 'LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: inlineConfigDto() })
    hoisted.upsertIsvaConfig.mockImplementation((_dir, payload) =>
      Promise.resolve({ data: { ...inlineConfigDto(), ...payload } }))

    const wrapper = await mountView()

    // Edit the 'enabled' checkbox — flips the form away from pristine.
    const enabled = wrapper.find('#enabled')
    await enabled.setValue(false)
    expect(wrapper.text()).toContain('Unsaved changes')

    // Click the in-bar Save button.
    const saveBtn = wrapper
      .findAll('button')
      .find((b) => b.text() === 'Save' && b.attributes('class')?.includes('btn-primary'))
    expect(saveBtn).toBeDefined()
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(hoisted.upsertIsvaConfig).toHaveBeenCalledWith('dir-1', expect.objectContaining({ enabled: false }))
    expect(hoisted.notifSuccess).toHaveBeenCalled()
    // After successful save, pristine is updated to match the form,
    // so the bar disappears.
    expect(wrapper.text()).not.toContain('Unsaved changes')
  })

  it('Discard reverts the form to pristine and hides the bar', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['INLINE', 'LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: inlineConfigDto() })

    const wrapper = await mountView()

    const enabled = wrapper.find('#enabled')
    expect((enabled.element as HTMLInputElement).checked).toBe(true)
    await enabled.setValue(false)
    expect(wrapper.text()).toContain('Unsaved changes')

    const discardBtn = wrapper.findAll('button').find((b) => b.text() === 'Discard')
    expect(discardBtn).toBeDefined()
    await discardBtn!.trigger('click')

    expect(wrapper.text()).not.toContain('Unsaved changes')
    expect((enabled.element as HTMLInputElement).checked).toBe(true)
    // Save wasn't called — Discard is purely a local revert.
    expect(hoisted.upsertIsvaConfig).not.toHaveBeenCalled()
  })

  it('save error fires an error toast and leaves the bar visible', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['INLINE', 'LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: inlineConfigDto() })
    hoisted.upsertIsvaConfig.mockRejectedValue({
      response: { data: { detail: 'managementDitBaseDn is required' } },
    })

    const wrapper = await mountView()

    const enabled = wrapper.find('#enabled')
    await enabled.setValue(false)
    const saveBtn = wrapper
      .findAll('button')
      .find((b) => b.text() === 'Save' && b.attributes('class')?.includes('btn-primary'))
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(hoisted.notifError).toHaveBeenCalledWith('managementDitBaseDn is required')
    // Bar is still up — pristine wasn't updated, the user can retry
    // or discard.
    expect(wrapper.text()).toContain('Unsaved changes')
  })
})

describe('IsvaConfigView probe gating', () => {
  beforeEach(() => {
    hoisted.probeIsvaConfig.mockReset()
  })

  it('Probe button is disabled while the form is dirty', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['INLINE', 'LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: inlineConfigDto() })

    const wrapper = await mountView()

    const probeBtn = wrapper.findAll('button').find((b) => b.text() === 'Probe')
    expect(probeBtn).toBeDefined()
    expect(probeBtn!.attributes('disabled')).toBeUndefined()

    await wrapper.find('#enabled').setValue(false)
    expect(probeBtn!.attributes('disabled')).toBeDefined()
    expect(probeBtn!.attributes('title')).toContain('Save your changes first')
  })

  it('Probe runs against the persisted config without saving first', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['INLINE', 'LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: inlineConfigDto() })
    hoisted.probeIsvaConfig.mockResolvedValue({
      data: { reachable: true, sampleSecUserFound: false, warnings: [] },
    })

    const wrapper = await mountView()
    const probeBtn = wrapper.findAll('button').find((b) => b.text() === 'Probe')
    await probeBtn!.trigger('click')
    await flushPromises()

    expect(hoisted.probeIsvaConfig).toHaveBeenCalledWith('dir-1')
    // Critically: probe did NOT implicitly save.
    expect(hoisted.upsertIsvaConfig).not.toHaveBeenCalled()
  })
})
