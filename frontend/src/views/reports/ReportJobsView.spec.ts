// SPDX-License-Identifier: Apache-2.0
/**
 * Gating tests for the scheduled-reports re-homing into core: the Scheduled Jobs
 * entry point is now gated on the REPORTS_SCHEDULE feature (not the GOVERNANCE
 * entitlement), so a community admin with the feature sees it. PDF / S3 options
 * stay behind the compliance entitlement, and the Email Subject field is gone
 * (the backend generates the subject).
 */
import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const hoisted = vi.hoisted(() => ({
  hasFeature: vi.fn((_k: string) => true),
  isComplianceEnabled: false,
  isIsvaIntegrationEnabled: false,
  isSuperadmin: false,
}))

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { dirId: 'dir-1' } }) }))

vi.mock('@/api/reports', () => ({
  listReportJobs: vi.fn().mockResolvedValue({ data: { content: [] } }),
  createReportJob: vi.fn().mockResolvedValue({ data: {} }),
  updateReportJob: vi.fn().mockResolvedValue({ data: {} }),
  deleteReportJob: vi.fn().mockResolvedValue({ data: {} }),
  setReportJobEnabled: vi.fn().mockResolvedValue({ data: {} }),
  runReportJobNow: vi.fn().mockResolvedValue({ data: {} }),
  runOperationalReport: vi.fn().mockResolvedValue({ data: new Blob() }),
  runOperationalReportData: vi.fn().mockResolvedValue({ data: {} }),
  runOperationalReportPdf: vi.fn().mockResolvedValue({ data: new Blob() }),
}))
vi.mock('@/api/directories', () => ({ listDirectories: vi.fn().mockResolvedValue({ data: [] }) }))
vi.mock('@/api/browse', () => ({ checkIntegrity: vi.fn().mockResolvedValue({ data: {} }) }))
vi.mock('@/api/audit', () => ({ getAuditActions: vi.fn().mockResolvedValue({ data: [] }) }))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasFeature: hoisted.hasFeature,
    isComplianceEnabled: hoisted.isComplianceEnabled,
    isIsvaIntegrationEnabled: hoisted.isIsvaIntegrationEnabled,
    isSuperadmin: hoisted.isSuperadmin,
  }),
}))
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }),
}))
vi.mock('@/stores/profilePicker', () => ({
  useProfilePickerStore: () => ({ selectedProfile: null }),
}))

import ReportJobsView from './ReportJobsView.vue'

// Render AppModal's default slot so the schedule form (with the format/delivery
// selects and the recipient row) is in the DOM for option-gating assertions.
const AppModalSlotStub = {
  props: ['modelValue', 'title', 'size'],
  template: '<div class="modal"><slot /></div>',
}

function mountView() {
  return mount(ReportJobsView, {
    global: {
      stubs: {
        AppModal: AppModalSlotStub,
        ConfirmDialog: true,
        DnPicker: true,
        ResultsTable: true,
        FormField: true,
      },
    },
  })
}

function scheduledJobsButton(wrapper: ReturnType<typeof mountView>) {
  return wrapper.findAll('button').find(b => b.text().includes('Scheduled Jobs'))
}

describe('ReportJobsView — scheduled-jobs gating', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    hoisted.hasFeature = vi.fn((_k: string) => true)
    hoisted.isComplianceEnabled = false
    hoisted.isIsvaIntegrationEnabled = false
    hoisted.isSuperadmin = false
  })

  it('shows the Scheduled Jobs button when the REPORTS_SCHEDULE feature is granted', async () => {
    hoisted.hasFeature = vi.fn((k: string) => k === 'reports.schedule')
    const wrapper = mountView()
    await flushPromises()
    expect(scheduledJobsButton(wrapper)).toBeTruthy()
    expect(hoisted.hasFeature).toHaveBeenCalledWith('reports.schedule')
  })

  it('hides the Scheduled Jobs button without the feature, even with compliance on', async () => {
    hoisted.hasFeature = vi.fn((_k: string) => false)
    hoisted.isComplianceEnabled = true
    const wrapper = mountView()
    await flushPromises()
    expect(scheduledJobsButton(wrapper)).toBeFalsy()
  })

  it('offers only CSV + Email in the schedule form when compliance is off', async () => {
    hoisted.isComplianceEnabled = false
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click') // open the add-job form
    await flushPromises()

    const formatValues = wrapper.find('#rj-job-output-format').findAll('option').map(o => o.attributes('value'))
    const deliveryValues = wrapper.find('#rj-job-delivery').findAll('option').map(o => o.attributes('value'))
    expect(formatValues).toEqual(['CSV'])
    expect(deliveryValues).toEqual(['EMAIL'])
  })

  it('offers PDF + S3 when compliance is on', async () => {
    hoisted.isComplianceEnabled = true
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    const formatValues = wrapper.find('#rj-job-output-format').findAll('option').map(o => o.attributes('value'))
    const deliveryValues = wrapper.find('#rj-job-delivery').findAll('option').map(o => o.attributes('value'))
    expect(formatValues).toContain('PDF')
    expect(deliveryValues).toContain('S3')
  })

  it('includes a timezone picker that defaults to a valid zone', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click') // open the add-job form
    await flushPromises()

    const tz = wrapper.find('#rj-job-timezone')
    expect(tz.exists()).toBe(true)
    expect(tz.findAll('option').map(o => o.attributes('value'))).toContain('UTC')
    // v-model defaults to the browser zone, which is always in the option list.
    expect((tz.element as HTMLSelectElement).value).toBeTruthy()
  })

  it('shows group-count criteria and persists them for a Users by Group Count job', async () => {
    const reports = await import('@/api/reports')
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('USERS_WITH_NO_GROUP')
    expect(wrapper.find('#rj-job-group-count-op').exists()).toBe(true)
    await wrapper.find('#rj-job-group-count-op').setValue('>=')
    await wrapper.find('input[aria-label="Group count value"]').setValue(2)

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(reports.createReportJob).toHaveBeenCalled()
    const params = (reports.createReportJob as unknown as Mock).mock.calls[0][1].reportParams
    expect(params.groupCountOp).toBe('>=')
    expect(params.groupCountValue).toBe(2)
  })

  it('shows audit-entries criteria and persists lookback hours + actions', async () => {
    const audit = await import('@/api/audit')
    ;(audit.getAuditActions as unknown as Mock).mockResolvedValue({ data: ['USER_CREATE', 'USER_DELETE'] })
    const reports = await import('@/api/reports')
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('AUDIT_ENTRIES')
    expect(wrapper.find('#rj-job-lookback-hours').exists()).toBe(true)
    await wrapper.find('#rj-job-lookback-hours').setValue(48)

    // Open the action menu and select one action.
    const actionTrigger = wrapper.findAll('button').find(b => b.attributes('aria-label') === 'Action')
    await actionTrigger!.trigger('click')
    await wrapper.find('input[type="checkbox"][value="USER_CREATE"]').setValue(true)

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const params = (reports.createReportJob as unknown as Mock).mock.calls[0][1].reportParams
    expect(params.lookbackHours).toBe(48)
    expect(params.actions).toEqual(['USER_CREATE'])
  })

  it('triggers an immediate run via the Run now button', async () => {
    const reports = await import('@/api/reports')
    // reports.js is untyped JS; cast to the loose Mock surface so the
    // per-test resolved value doesn't need a full AxiosResponse shape.
    ;(reports.listReportJobs as unknown as Mock).mockResolvedValue({
      data: { content: [
        { id: 'job-1', name: 'Weekly', reportType: 'RECENTLY_ADDED', cronExpression: '0 8 * * 1', enabled: true, timezone: 'UTC' },
      ] },
    })
    const wrapper = mountView()
    await flushPromises()
    await scheduledJobsButton(wrapper)!.trigger('click') // openSchedules → loads jobs
    await flushPromises()

    const runBtn = wrapper.findAll('button').find(b => b.text().includes('Run now'))
    expect(runBtn).toBeTruthy()
    await runBtn!.trigger('click')
    await flushPromises()
    expect(reports.runReportJobNow).toHaveBeenCalledWith('dir-1', 'job-1')
  })

  it('expands a run log showing per-run outcome, duration, and trigger', async () => {
    const reports = await import('@/api/reports')
    ;(reports.listReportJobs as unknown as Mock).mockResolvedValue({
      data: { content: [
        {
          id: 'job-1', name: 'Weekly', reportType: 'RECENTLY_ADDED', cronExpression: '0 8 * * 1',
          enabled: true, timezone: 'UTC',
          lastRunStatus: 'SKIPPED',
          lastRunMessage: 'Report generated (12 bytes) but not delivered — SMTP is not configured',
          runHistory: [
            { startedAt: '2026-06-20T08:00:00Z', runAt: '2026-06-20T08:00:03Z', status: 'SUCCESS',
              message: 'Emailed 12 bytes to 1/1 recipient(s)', trigger: 'SCHEDULED' },
            { startedAt: '2026-06-21T08:00:00Z', runAt: '2026-06-21T08:00:01Z', status: 'SKIPPED',
              message: 'Report generated (12 bytes) but not delivered — SMTP is not configured', trigger: 'MANUAL' },
          ],
        },
      ] },
    })
    const wrapper = mountView()
    await flushPromises()
    await scheduledJobsButton(wrapper)!.trigger('click')
    await flushPromises()

    // Collapsed by default — no timeline rendered.
    expect(wrapper.text()).not.toContain('Run log —')
    const logBtn = wrapper.findAll('button').find(b => b.text().trim() === 'Log')
    expect(logBtn).toBeTruthy()
    await logBtn!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Run log —')
    expect(wrapper.text()).toContain('Scheduled')
    expect(wrapper.text()).toContain('Manual')
    expect(wrapper.text()).toContain('SKIPPED')
    expect(wrapper.text()).toContain('SMTP is not configured')
    // Durations: 3s (scheduled) and 1s (manual) runs.
    expect(wrapper.text()).toContain('3.0 s')
    expect(wrapper.text()).toContain('1.0 s')
  })

  // Regression: the Delete confirm dialog was mounted with v-if but no
  // :model-value, so ConfirmDialog's inner `v-if="modelValue"` kept it hidden
  // and the Delete button appeared dead. Render a stub that honours modelValue
  // to prove the dialog shows and confirming reaches the delete endpoint.
  it('opens the confirm dialog from the row Delete button and deletes on confirm', async () => {
    const reports = await import('@/api/reports')
    ;(reports.listReportJobs as unknown as Mock).mockResolvedValue({
      data: { content: [
        { id: 'job-1', name: 'Weekly', reportType: 'RECENTLY_ADDED', cronExpression: '0 8 * * 1', enabled: true, timezone: 'UTC' },
      ] },
    })
    const ConfirmDialogStub = {
      props: ['modelValue', 'message', 'title', 'confirmLabel', 'danger'],
      emits: ['confirm', 'update:modelValue'],
      template: `<div v-if="modelValue" class="confirm-stub">
        <span class="confirm-msg">{{ message }}</span>
        <button class="confirm-ok" @click="$emit('confirm'); $emit('update:modelValue', false)">go</button>
      </div>`,
    }
    const wrapper = mount(ReportJobsView, {
      global: {
        stubs: {
          AppModal: AppModalSlotStub,
          ConfirmDialog: ConfirmDialogStub,
          DnPicker: true, ResultsTable: true, FormField: true,
        },
      },
    })
    await flushPromises()
    await scheduledJobsButton(wrapper)!.trigger('click')
    await flushPromises()

    // No dialog until the row Delete button is clicked.
    expect(wrapper.find('.confirm-stub').exists()).toBe(false)
    const deleteBtn = wrapper.findAll('button').find(b => b.text().trim() === 'Delete')
    await deleteBtn!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.confirm-stub')
    expect(dialog.exists()).toBe(true)
    expect(dialog.find('.confirm-msg').text()).toContain('Weekly')

    await wrapper.find('.confirm-ok').trigger('click')
    await flushPromises()
    expect(reports.deleteReportJob).toHaveBeenCalledWith('dir-1', 'job-1')
  })
})
