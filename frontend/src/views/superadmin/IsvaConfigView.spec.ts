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
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'dir-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/api/isvaConfig', () => ({
  getIsvaConfig: hoisted.getIsvaConfig,
  getIsvaUiOptions: hoisted.getIsvaUiOptions,
  upsertIsvaConfig: hoisted.upsertIsvaConfig,
  probeIsvaConfig: hoisted.probeIsvaConfig,
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
  hoisted.getIsvaConfig.mockReset()
  hoisted.getIsvaUiOptions.mockReset()
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
