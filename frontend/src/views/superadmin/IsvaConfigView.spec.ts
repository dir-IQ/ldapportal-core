// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for IsvaConfigView's topology-exposure logic:
 *   - selector hidden + mode pinned when one mode is exposed
 *   - selector shown when both are exposed
 *   - mismatch note when a persisted config uses a now-unexposed mode
 *
 * Router + the isvaConfig API are mocked at the module level. These tests
 * assert structure (selector / panel / note), not the DIT inputs.
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
  getDirectory: vi.fn().mockResolvedValue({ data: { displayName: 'Acme LDAP' } }),
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

// The view fetches the directory's display name for its heading.
vi.mock('@/api/directories', () => ({
  getDirectory: hoisted.getDirectory,
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
    secLoginType: 'Default',
    defaultValidUntilYears: 100,
    requireSecGroup: true,
    secuserObjectClasses: ['secUser'],
    secuserOverlayAttributes: ['secLogin', 'secAcctValid', 'secPwdValid', 'secValidUntil', 'secPwdLastChanged'],
    managementDitBaseDn: null,
    secuserRdnAttribute: null,
    secuserRdnValueSource: null,
    groupMemberTarget: null,
    createdAt: '', updatedAt: '', updatedBy: 'alice',
  }
}

function linkedConfigDto() {
  return {
    ...inlineConfigDto(),
    topologyMode: 'LINKED',
    secuserObjectClasses: ['secUser', 'eUser'],
    managementDitBaseDn: 'secAuthority=Default,o=acme,c=us',
    secuserRdnAttribute: 'principalName',
    secuserRdnValueSource: 'UID',
    groupMemberTarget: 'DEMOGRAPHIC_DN',
  }
}

async function mountView() {
  const wrapper = mount(IsvaConfigView)
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
  it('shows the configured directory name in the heading', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['LINKED'] } })
    hoisted.getIsvaConfig.mockRejectedValue(notFound)

    const wrapper = await mountView()

    expect(wrapper.find('h1').text()).toBe('IBM Verify Identity Access integration - Acme LDAP')
  })

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

  it('describes the secUser object-class write target for the active topology only', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['INLINE', 'LINKED'] } })

    // Inline: the demographic entry is overlaid; no linked ("paired") phrasing.
    hoisted.getIsvaConfig.mockResolvedValue({ data: inlineConfigDto() })
    let help = (await mountView()).find('[data-testid="secuser-oc-help"]').text()
    expect(help).toContain('overlaid onto')
    expect(help).not.toContain('paired')

    // Linked: the paired secUser entry is stamped; no inline ("overlaid") phrasing.
    hoisted.getIsvaConfig.mockResolvedValue({ data: linkedConfigDto() })
    help = (await mountView()).find('[data-testid="secuser-oc-help"]').text()
    expect(help).toContain('paired')
    expect(help).not.toContain('overlaid onto')
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

describe('IsvaConfigView secUser objectClasses + generalized RDN', () => {
  beforeEach(() => {
    hoisted.upsertIsvaConfig.mockReset()
  })

  it('renders configured objectClasses as chips and the free-form RDN attribute', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: linkedConfigDto() })

    const wrapper = await mountView()

    // Both configured classes appear; the RDN attribute is a text input
    // carrying the free-form value.
    expect(wrapper.text()).toContain('secUser')
    expect(wrapper.text()).toContain('eUser')
    const rdnInput = wrapper.find('#secuserRdnAttribute')
    expect((rdnInput.element as HTMLInputElement).value).toBe('principalName')
    // UID value source is selected.
    const uidRadio = wrapper.find('input[name="rdnsource"][value="UID"]')
    expect((uidRadio.element as HTMLInputElement).checked).toBe(true)
  })

  it('adds an objectClass chip and includes it in the save payload', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: linkedConfigDto() })
    hoisted.upsertIsvaConfig.mockImplementation((_dir, payload) =>
      Promise.resolve({ data: { ...linkedConfigDto(), ...payload } }))

    const wrapper = await mountView()

    await wrapper.find('#newObjectClass').setValue('customClass')
    await wrapper.find('#newObjectClass').trigger('keydown.enter')

    const saveBtn = wrapper
      .findAll('button')
      .find((b) => b.text() === 'Save' && b.attributes('class')?.includes('btn-primary'))
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(hoisted.upsertIsvaConfig).toHaveBeenCalledWith('dir-1', expect.objectContaining({
      secuserObjectClasses: ['secUser', 'eUser', 'customClass'],
      secuserRdnAttribute: 'principalName',
      secuserRdnValueSource: 'UID',
    }))
  })

  it('parses a comma/space-delimited list into separate chips, deduping against existing and within the input', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: linkedConfigDto() })
    hoisted.upsertIsvaConfig.mockImplementation((_dir, payload) =>
      Promise.resolve({ data: { ...linkedConfigDto(), ...payload } }))

    const wrapper = await mountView()

    // Mixed comma + space separators, a duplicate of an existing class (eUser,
    // different case) and a within-input duplicate (top twice) — all collapsed.
    await wrapper.find('#newObjectClass').setValue('inetOrgPerson, EUSER  top top')
    await wrapper.find('#newObjectClass').trigger('keydown.enter')

    const saveBtn = wrapper
      .findAll('button')
      .find((b) => b.text() === 'Save' && b.attributes('class')?.includes('btn-primary'))
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(hoisted.upsertIsvaConfig).toHaveBeenCalledWith('dir-1', expect.objectContaining({
      secuserObjectClasses: ['secUser', 'eUser', 'inetOrgPerson', 'top'],
      secuserRdnAttribute: 'principalName',
      secuserRdnValueSource: 'UID',
    }))
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
      data: {
        reachable: true, sampleSecUserFound: false, schemaValid: null,
        disallowedWriteAttributes: [], missingRequiredAttributes: [], warnings: [],
      },
    })

    const wrapper = await mountView()
    const probeBtn = wrapper.findAll('button').find((b) => b.text() === 'Probe')
    await probeBtn!.trigger('click')
    await flushPromises()

    expect(hoisted.probeIsvaConfig).toHaveBeenCalledWith('dir-1')
    // Critically: probe did NOT implicitly save.
    expect(hoisted.upsertIsvaConfig).not.toHaveBeenCalled()
  })

  it('Probe surfaces secUser attribute mismatches', async () => {
    hoisted.getIsvaUiOptions.mockResolvedValue({ data: { exposedTopologyModes: ['INLINE', 'LINKED'] } })
    hoisted.getIsvaConfig.mockResolvedValue({ data: inlineConfigDto() })
    hoisted.probeIsvaConfig.mockResolvedValue({
      data: {
        reachable: true, sampleSecUserFound: true, schemaValid: false,
        disallowedWriteAttributes: ['secLogin', 'secValidUntil'],
        missingRequiredAttributes: [],
        warnings: [],
      },
    })

    const wrapper = await mountView()
    const probeBtn = wrapper.findAll('button').find((b) => b.text() === 'Probe')
    await probeBtn!.trigger('click')
    await flushPromises()

    const mismatch = wrapper.find('[data-testid="probe-attr-mismatch"]')
    expect(mismatch.exists()).toBe(true)
    expect(mismatch.text()).toContain('secLogin')
    expect(mismatch.text()).toContain('secValidUntil')
  })
})
