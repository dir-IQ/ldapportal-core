// SPDX-License-Identifier: Apache-2.0
/**
 * Sidebar profile picker → active page reload, with the unsaved-changes veto.
 *
 * Picking a profile must reload the page the admin is on (remount the
 * RouterView child) so it shows the new profile's data. When that page has
 * registered unsaved work, the admin is asked first and can keep the
 * current profile. A switch into another directory navigates instead, and
 * must not reload the page twice.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h, ref } from 'vue'

const state = vi.hoisted(() => ({
  mounts: [] as string[],   // dirId of every UsersPage mount, in order
  dirty: { value: false },  // what the UsersPage guard reports
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    isSuperadmin: false, isComplianceEnabled: false, isAlertingEnabled: false,
    isHrEnabled: false, isDirectorySyncEnabled: false, isAnyApprovalEnabled: false,
    username: 'admin', hasSuperadminPermission: () => false, hasFeature: () => true, logout: vi.fn(),
  }),
}))
vi.mock('@/composables/usePermissions', () => ({ usePermissions: () => ({ hasFeature: () => true }) }))
vi.mock('@/stores/settings', () => ({ useSettingsStore: () => ({ init: vi.fn(), appName: 'LDAPPortal' }) }))
vi.mock('@/composables/useKeyboardShortcuts', () => ({ useKeyboardShortcuts: () => ({ showHelp: ref(false) }) }))
vi.mock('@/composables/useVersionCheck', () => ({
  useVersionCheck: () => ({ clientSha: 'abc', serverSha: ref(''), serverImageVersion: ref(''), skewDetected: ref(false) }),
}))
vi.mock('@/api/auth', () => ({
  myProfiles: vi.fn().mockResolvedValue({ data: [
    { id: 'p1', name: 'People',   directoryId: 'd1', targetUserDn: 'ou=people,dc=x' },
    { id: 'p2', name: 'Staff',    directoryId: 'd1', targetUserDn: 'ou=staff,dc=x' },
    { id: 'p3', name: 'Partners', directoryId: 'd2', targetUserDn: 'ou=partners,dc=y' },
  ] }),
}))
vi.mock('@/api/approvals', () => ({
  countPendingApprovals: vi.fn().mockResolvedValue({ data: { pending: 0, configured: false } }),
}))
vi.mock('@/ee', () => ({
  listCampaigns: vi.fn().mockResolvedValue({ data: { totalElements: 0 } }),
  getAlertSummary: vi.fn().mockResolvedValue({ data: {} }),
}))

import AppLayout from './AppLayout.vue'
import { useProfilePickerStore } from '@/stores/profilePicker'
import { useUnsavedChangesGuard } from '@/composables/useUnsavedChangesGuard'

// Stand-in for UserListView: records each mount and registers a guard whose
// dirtiness the test controls.
const UsersPage = defineComponent({
  setup() {
    const route = router.currentRoute.value
    state.mounts.push(String(route.params.dirId))
    useUnsavedChangesGuard('Users', () => state.dirty.value)
    return () => h('div', 'users page')
  },
})
const Blank = defineComponent({ setup: () => () => h('div') })

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/dashboard', name: 'adminDashboard', component: Blank },
    { path: '/directories/:dirId/users', name: 'users', component: UsersPage },
  ],
})

// Minimal ConfirmDialog: shows the message and two buttons the test can click.
const ConfirmStub = defineComponent({
  props: ['modelValue', 'title', 'message', 'confirmLabel', 'cancelLabel', 'confirmClass', 'danger'],
  emits: ['confirm', 'update:modelValue'],
  setup(props, { emit }) {
    return () => props.modelValue
      ? h('div', { 'data-testid': 'confirm' }, [
          h('p', props.message),
          h('button', { 'data-testid': 'no', onClick: () => emit('update:modelValue', false) }, props.cancelLabel),
          h('button', { 'data-testid': 'yes', onClick: () => emit('confirm') }, props.confirmLabel),
        ])
      : null
  },
})

async function mountOnUsers() {
  await router.push('/directories/d1/users')
  await router.isReady()
  const wrapper = mount(AppLayout, {
    global: {
      plugins: [router],
      stubs: {
        KeyboardShortcutsHelp: true, UserPreferencesDialog: true, NotificationBell: true,
        CommandPalette: true, LicenseExpirationBanner: true, UpgradeModal: true,
        ConfirmDialog: ConfirmStub,
      },
    },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

function picker(wrapper: ReturnType<typeof mount>): HTMLSelectElement {
  return wrapper.find('select').element as HTMLSelectElement
}

async function pick(wrapper: ReturnType<typeof mount>, id: string) {
  const sel = wrapper.find('select')
  await sel.setValue(id)     // sets .value and fires change
  await flushPromises()
}

describe('AppLayout profile picker', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    state.mounts = []
    state.dirty.value = false
    document.body.innerHTML = ''
  })

  it('auto-selects the profile matching the route directory and mounts the page once', async () => {
    const w = await mountOnUsers()
    expect(picker(w).value).toBe('p1')
    expect(useProfilePickerStore().selectedId).toBe('p1')
    expect(state.mounts).toEqual(['d1'])
    w.unmount()
  })

  it('reloads the current page when switching to another profile in the same directory', async () => {
    const w = await mountOnUsers()
    await pick(w, 'p2')
    expect(useProfilePickerStore().selectedId).toBe('p2')
    expect(router.currentRoute.value.path).toBe('/directories/d1/users')
    expect(state.mounts).toEqual(['d1', 'd1'])
    expect(w.find('[data-testid="confirm"]').exists()).toBe(false)
    w.unmount()
  })

  it('asks before switching when the page has unsaved changes, and keeps the profile on No', async () => {
    const w = await mountOnUsers()
    state.dirty.value = true
    await pick(w, 'p2')
    const dialog = w.find('[data-testid="confirm"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.find('p').text()).toBe(
      "The Users page has unsaved changes that will be lost if it's reloaded for profile Staff. Switch anyway?",
    )
    expect(dialog.find('[data-testid="no"]').text()).toBe('No')
    expect(dialog.find('[data-testid="yes"]').text()).toBe('Yes')

    await dialog.find('[data-testid="no"]').trigger('click')
    await flushPromises()
    expect(useProfilePickerStore().selectedId).toBe('p1')
    expect(picker(w).value).toBe('p1')          // control snapped back
    expect(state.mounts).toEqual(['d1'])         // no reload
    w.unmount()
  })

  it('switches and reloads when the admin answers Yes', async () => {
    const w = await mountOnUsers()
    state.dirty.value = true
    await pick(w, 'p2')
    await w.find('[data-testid="yes"]').trigger('click')
    await flushPromises()
    expect(useProfilePickerStore().selectedId).toBe('p2')
    expect(state.mounts).toEqual(['d1', 'd1'])
    w.unmount()
  })

  it('navigates to the same page under the new directory, reloading exactly once', async () => {
    const w = await mountOnUsers()
    await pick(w, 'p3')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/directories/d2/users')
    expect(state.mounts).toEqual(['d1', 'd2'])
    w.unmount()
  })
})
