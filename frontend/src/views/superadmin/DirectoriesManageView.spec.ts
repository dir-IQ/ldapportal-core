// SPDX-License-Identifier: Apache-2.0
/**
 * Component test for the live reachability Status column on the Directory
 * Connections page. The status dot must reflect the per-directory probe
 * (GET /{id}/status), not the `enabled` config flag: an enabled-but-down
 * directory reads "Unreachable" (red), a reachable one "Online" (green), and
 * a disabled one "Disabled" without being probed at all.
 *
 * The directories API, entra API, notifications, and auth stores are mocked
 * at module level; child components are stubbed.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  listDirectories: vi.fn(),
  getDirectoryStatus: vi.fn(),
}))

vi.mock('@/api/directories', () => ({
  listDirectories: api.listDirectories,
  createDirectory: vi.fn(),
  updateDirectory: vi.fn(),
  deleteDirectory: vi.fn(),
  testDirectory: vi.fn(),
  evictPool: vi.fn(),
  getDirectoryStatus: api.getDirectoryStatus,
}))

vi.mock('@/api/entra', () => ({ testEntraConnection: vi.fn() }))

vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isIsvaIntegrationEnabled: false, isDirectorySyncEnabled: false, isHrEnabled: false }),
}))

import DirectoriesManageView from './DirectoriesManageView.vue'

const stubs = {
  PageContainer: { template: '<div><slot /></div>' },
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
  ActionMenu: { props: ['items'], template: '<div><slot name="primary" /></div>' },
  ConfirmDialog: { template: '<div />' },
  EmptyState: { template: '<div />' },
  FormField: { template: '<input />' },
}

function dir(id: string, enabled: boolean) {
  return { id, displayName: `Dir ${id}`, host: 'ldap.example.com', port: 389, sslMode: 'NONE', baseDn: 'dc=x', enabled, directoryType: 'GENERIC' }
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listDirectories.mockResolvedValue({ data: [dir('up', true), dir('down', true), dir('off', false)] })
  api.getDirectoryStatus.mockImplementation((id: string) =>
    Promise.resolve({ data: id === 'up'
      ? { success: true, message: 'Reachable', elapsedMs: 12 }
      : { success: false, message: 'UnknownHostException', elapsedMs: 5 } }),
  )
})

describe('DirectoriesManageView status column', () => {
  it('renders the page title as "Directory Connections"', async () => {
    const wrapper = mount(DirectoriesManageView, { global: { stubs } })
    await flushPromises()
    expect(wrapper.find('h1').text()).toBe('Directory Connections')
  })

  it('shows Online / Unreachable from the probe, and Disabled without probing', async () => {
    const wrapper = mount(DirectoriesManageView, { global: { stubs } })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Online')
    expect(text).toContain('Unreachable')
    expect(text).toContain('Disabled')

    // Reachable dot is green, unreachable is red.
    const html = wrapper.html()
    expect(html).toContain('bg-green-500')
    expect(html).toContain('bg-red-500')

    // Only the two enabled directories are probed; the disabled one is not.
    expect(api.getDirectoryStatus).toHaveBeenCalledTimes(2)
    expect(api.getDirectoryStatus).toHaveBeenCalledWith('up')
    expect(api.getDirectoryStatus).toHaveBeenCalledWith('down')
    expect(api.getDirectoryStatus).not.toHaveBeenCalledWith('off')
  })

  it('marks a directory unreachable when the probe call throws', async () => {
    api.getDirectoryStatus.mockRejectedValue(new Error('network down'))
    const wrapper = mount(DirectoriesManageView, { global: { stubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('Unreachable')
  })
})
