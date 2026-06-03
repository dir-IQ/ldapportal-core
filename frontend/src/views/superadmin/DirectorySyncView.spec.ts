// SPDX-License-Identifier: Apache-2.0
/**
 * Component test for the reconciliation findings review flow in
 * DirectorySyncView: open run history → review a run's findings → select a
 * proposed finding → apply it. The replication API, directories API,
 * notifications, confirm, and router are mocked at module level. AppModal is
 * stubbed to render its slots only when open; ActionMenu renders its items as
 * buttons so the row action is clickable.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  listReplicationLinks: vi.fn(),
  listReplicationEvents: vi.fn(),
  reconcileNow: vi.fn(),
  listReconciliationRuns: vi.fn(),
  getReconciliationFindings: vi.fn(),
  applyReconciliationFindings: vi.fn(),
  dismissReconciliationFindings: vi.fn(),
  notifSuccess: vi.fn(),
  notifError: vi.fn(),
}))

vi.mock('@/api/replication', () => ({
  listReplicationLinks: api.listReplicationLinks,
  createReplicationLink: vi.fn(),
  updateReplicationLink: vi.fn(),
  deleteReplicationLink: vi.fn(),
  listReplicationEvents: api.listReplicationEvents,
  retryReplicationEvent: vi.fn(),
  skipReplicationEvent: vi.fn(),
  acknowledgeReplicationEvent: vi.fn(),
  reconcileNow: api.reconcileNow,
  listReconciliationRuns: api.listReconciliationRuns,
  getReconciliationFindings: api.getReconciliationFindings,
  applyReconciliationFindings: api.applyReconciliationFindings,
  dismissReconciliationFindings: api.dismissReconciliationFindings,
}))

vi.mock('@/api/directories', () => ({ listDirectories: vi.fn().mockResolvedValue({ data: [] }) }))

vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: api.notifSuccess, error: api.notifError, info: vi.fn(), warning: vi.fn() }),
}))

vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => vi.fn().mockResolvedValue(true) }))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import DirectorySyncView from './DirectorySyncView.vue'

const stubs = {
  // Render modal content only when open (mirrors v-model semantics).
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  // Render each action item as a button so row actions are clickable.
  ActionMenu: {
    props: ['items'],
    template: '<div><button v-for="(it,i) in items" :key="i" class="am-item" @click="it.onClick && it.onClick()">{{ it.label }}</button><slot name="primary" /></div>',
  },
  RelativeTime: { template: '<span />' },
  PageContainer: { template: '<div><slot /></div>' },
  ConfirmDialog: { template: '<div />' },
  FormField: { template: '<input />' },
}

function link() {
  return {
    id: 'link-1', displayName: 'Corp → DR',
    sourceDirectoryId: 's', targetDirectoryId: 't',
    enabled: true, autoCreateOnMissing: false,
    pendingCount: 0, failedCount: 0, deadLetteredCount: 0,
    reconcileEnabled: true, reconcileMode: 'REVIEW', reconcileDeleteAction: 'REVIEW',
  }
}

function run() {
  return {
    id: 'run-1', trigger: 'MANUAL', mode: 'REVIEW', status: 'COMPLETED',
    startedAt: new Date().toISOString(), finishedAt: new Date().toISOString(),
    sourceEntryCount: 10, targetEntryCount: 9,
    missingCount: 1, driftCount: 0, extraCount: 0, suppressedCount: 0, appliedCount: 0,
  }
}

function finding() {
  return {
    id: 'f-1', findingType: 'MISSING_IN_TARGET', suggestedOp: 'ADD',
    sourceDn: 'uid=b,dc=x', targetDn: 'uid=b,dc=x',
    detail: { attributes: { cn: ['Bob'] } }, status: 'PROPOSED', eventId: null,
  }
}

const byText = (wrapper: ReturnType<typeof mount>, text: string) =>
  wrapper.findAll('button').filter(b => b.text() === text)

describe('DirectorySyncView reconciliation findings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listReplicationLinks.mockResolvedValue({ data: [link()] })
    api.listReconciliationRuns.mockResolvedValue({ data: { content: [run()] } })
    api.getReconciliationFindings.mockResolvedValue({ data: { content: [finding()] } })
    api.applyReconciliationFindings.mockResolvedValue({ data: { applied: 1 } })
  })

  it('reviews a run and applies a selected finding', async () => {
    const wrapper = mount(DirectorySyncView, { global: { stubs } })
    await flushPromises()

    // Open run history from the row action.
    byText(wrapper, 'Reconciliation history')[0].trigger('click')
    await flushPromises()
    expect(api.listReconciliationRuns).toHaveBeenCalledWith('link-1', expect.anything())

    // Open the findings review for the run.
    byText(wrapper, 'Review findings')[0].trigger('click')
    await flushPromises()
    expect(api.getReconciliationFindings).toHaveBeenCalledWith('run-1', expect.objectContaining({ status: 'PROPOSED' }))

    // Select the proposed finding and apply.
    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    // The last checkbox is the finding row (others: select-all + form toggles in closed modals are not rendered).
    await checkboxes[checkboxes.length - 1].setValue(true)
    await byText(wrapper, 'Apply selected')[0].trigger('click')
    await flushPromises()

    expect(api.applyReconciliationFindings).toHaveBeenCalledWith('run-1', { findingIds: ['f-1'] })
    expect(api.notifSuccess).toHaveBeenCalled()
  })

  // Drives history → review for a single finding of the given shape.
  async function openFindingsWith(f: Record<string, unknown>) {
    api.getReconciliationFindings.mockResolvedValue({ data: { content: [f] } })
    const wrapper = mount(DirectorySyncView, { global: { stubs } })
    await flushPromises()
    byText(wrapper, 'Reconciliation history')[0].trigger('click')
    await flushPromises()
    byText(wrapper, 'Review findings')[0].trigger('click')
    await flushPromises()
    return wrapper
  }

  it('warns on a destructive Extra/DELETE finding and shows attribute values', async () => {
    const wrapper = await openFindingsWith({
      id: 'f-x', findingType: 'EXTRA_IN_TARGET', suggestedOp: 'DELETE',
      sourceDn: null, targetDn: 'uid=tmp,dc=x',
      detail: { currentTarget: { cn: ['Temp Account'], mail: ['tmp@x'] } },
      status: 'PROPOSED', eventId: null,
    })

    // The link's REVIEW delete policy surfaces as a row note, no expand needed.
    expect(wrapper.text()).toContain('held for review')

    // Expand → destructive warning + the entry's actual attribute values.
    byText(wrapper, '▸')[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('would be deleted')
    expect(wrapper.text()).toContain('Destructive.')
    expect(wrapper.text()).toContain('Temp Account')
  })

  it('renders a current-vs-expected table for an attribute-drift finding', async () => {
    const wrapper = await openFindingsWith({
      id: 'f-d', findingType: 'ATTRIBUTE_DRIFT', suggestedOp: 'MODIFY',
      sourceDn: 'uid=a,dc=x', targetDn: 'uid=a,dc=x',
      detail: { modifications: [{ name: 'mail', values: ['a@new'] }], before: { mail: ['a@old'] } },
      status: 'PROPOSED', eventId: null,
    })

    expect(wrapper.text()).toContain('1 attribute differs')

    byText(wrapper, '▸')[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Current (target)')
    expect(wrapper.text()).toContain('a@old')
    expect(wrapper.text()).toContain('a@new')
  })

  it('triggers reconcile now from the row action', async () => {
    api.reconcileNow.mockResolvedValue({ data: { runId: 'run-9' } })
    const wrapper = mount(DirectorySyncView, { global: { stubs } })
    await flushPromises()

    byText(wrapper, 'Reconcile now')[0].trigger('click')
    await flushPromises()

    expect(api.reconcileNow).toHaveBeenCalledWith('link-1')
  })
})
