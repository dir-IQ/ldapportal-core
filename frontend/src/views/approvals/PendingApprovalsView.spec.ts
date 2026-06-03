// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for PendingApprovalsView's action-gating logic:
 *   - superadmins ARE permitted to act on other users' requests
 *     (regression guard for the old `canAct = !isSuperadmin` bug)
 *   - a reviewer can't act on their own request ("Own request" shown)
 *   - request-type labels render, including PLAYBOOK_EXECUTE
 *
 * The approvals API, directory picker, useApi wrapper, and both Pinia
 * stores are mocked at the module level. DataTable / ActionMenu are
 * stubbed so the row-action slots render with their row payload — that's
 * where the gating lives.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const hoisted = vi.hoisted(() => ({
  listPendingApprovals: vi.fn(),
  approveRequest: vi.fn(),
  rejectRequest: vi.fn(),
  updateApprovalPayload: vi.fn(),
  notifSuccess: vi.fn(),
  notifError: vi.fn(),
  principal: { id: 'reviewer-1', accountType: 'SUPERADMIN' },
  isSuperadmin: true,
}))

vi.mock('@/api/approvals', () => ({
  listPendingApprovals: hoisted.listPendingApprovals,
  approveRequest: hoisted.approveRequest,
  rejectRequest: hoisted.rejectRequest,
  updateApprovalPayload: hoisted.updateApprovalPayload,
}))

// Pin a directory so the picker is hidden and onMounted loads immediately.
vi.mock('@/composables/useDirectoryPicker', async () => {
  const { ref, computed } = await import('vue')
  return {
    useDirectoryPicker: () => ({
      dirId: computed(() => 'dir-1'),
      directories: ref([]),
      selectedDir: ref('dir-1'),
      loadingDirs: ref(false),
      showPicker: computed(() => false),
    }),
  }
})

// useApi.call just runs the supplied fn and returns its result.
vi.mock('@/composables/useApi', async () => {
  const { ref } = await import('vue')
  return {
    useApi: () => ({
      loading: ref(false),
      error: ref(null),
      call: (fn: () => unknown) => fn(),
    }),
  }
})

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    principal: hoisted.principal,
    isLoggedIn: true,
    isSuperadmin: hoisted.isSuperadmin,
  }),
}))

vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({
    success: hoisted.notifSuccess,
    error: hoisted.notifError,
    info: vi.fn(),
    warning: vi.fn(),
  }),
}))

import PendingApprovalsView from './PendingApprovalsView.vue'

// Stubs that surface the row-action and type slots with their payload.
const DataTableStub = {
  props: ['rows', 'columns', 'loading', 'rowKey', 'emptyText', 'emptyIcon'],
  template: `
    <div>
      <div v-for="row in rows" :key="row.id" class="dt-row">
        <span class="dt-type"><slot name="cell-requestType" :value="row.requestType" /></span>
        <span class="dt-actions"><slot name="cell-actions" :row="row" /></span>
      </div>
    </div>`,
}
const ActionMenuStub = { template: '<div><slot name="primary" /></div>' }

function mountView() {
  return mount(PendingApprovalsView, {
    global: {
      stubs: {
        DataTable: DataTableStub,
        ActionMenu: ActionMenuStub,
        RelativeTime: true,
        AppModal: true,
        ConfirmDialog: true,
      },
    },
  })
}

function pending(overrides: Record<string, unknown> = {}) {
  return {
    id: 'appr-1',
    requestType: 'USER_CREATE',
    requesterUsername: 'alice',
    requestedBy: 'user-2',
    status: 'PENDING',
    createdAt: '2026-06-01T00:00:00Z',
    ...overrides,
  }
}

describe('PendingApprovalsView action gating', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    hoisted.principal = { id: 'reviewer-1', accountType: 'SUPERADMIN' }
    hoisted.isSuperadmin = true
  })

  it('lets a superadmin approve/reject another user\'s request', async () => {
    hoisted.listPendingApprovals.mockResolvedValue({ data: [pending()] })
    const wrapper = mountView()
    await flushPromises()

    const labels = wrapper.findAll('.dt-actions button').map(b => b.text())
    expect(labels).toContain('Approve')
    expect(labels).toContain('Reject')
    expect(wrapper.text()).not.toContain('Own request')
  })

  it('hides actions and marks the reviewer\'s own request', async () => {
    hoisted.listPendingApprovals.mockResolvedValue({
      data: [pending({ requestedBy: 'reviewer-1' })],
    })
    const wrapper = mountView()
    await flushPromises()

    const labels = wrapper.findAll('.dt-actions button').map(b => b.text())
    expect(labels).toContain('View')
    expect(labels).not.toContain('Approve')
    expect(labels).not.toContain('Reject')
    expect(wrapper.text()).toContain('Own request')
  })

  it('renders a friendly label for PLAYBOOK_EXECUTE requests', async () => {
    hoisted.listPendingApprovals.mockResolvedValue({
      data: [pending({ requestType: 'PLAYBOOK_EXECUTE' })],
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.dt-type').text()).toBe('Playbook Execution')
  })
})
