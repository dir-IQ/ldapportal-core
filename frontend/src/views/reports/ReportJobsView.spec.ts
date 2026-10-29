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
import { createPinia, setActivePinia } from 'pinia'

const hoisted = vi.hoisted(() => ({
  hasFeature: vi.fn((_k: string) => true),
  isComplianceEnabled: false,
  isIsvaIntegrationEnabled: false,
  isSuperadmin: false,
  selectedProfile: null as { targetUserDn?: string | null, targetGroupDn?: string | null } | null,
  profiles: [] as { directoryId?: string, targetUserDn?: string | null }[],
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
  useProfilePickerStore: () => ({ selectedProfile: hoisted.selectedProfile, profiles: hoisted.profiles }),
}))

// The view registers an unsaved-changes guard (and reads the sidebar
// picker) through real Pinia stores; give every test a fresh instance.
beforeEach(() => setActivePinia(createPinia()))

import ReportJobsView from './ReportJobsView.vue'
import DnPicker from '@/components/DnPicker.vue'

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
    hoisted.selectedProfile = null
    hoisted.profiles = []
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

  it('runs a Missing Data report with the branch DN and a parsed attribute list', async () => {
    const reports = await import('@/api/reports')
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('#rj-report-type').setValue('MISSING_DATA')
    // Branch DN picker (stubbed) + attribute list + object-type narrowing.
    expect(wrapper.find('#rj-attributes').exists()).toBe(true)
    expect(wrapper.find('#rj-object-type').exists()).toBe(true)
    await wrapper.find('#rj-attributes').setValue(' mail, sn,,Mail telephoneNumber ')
    await wrapper.find('#rj-object-type').setValue('USER')

    const runButton = wrapper.findAll('button').find(b => b.text() === 'Run Report')
    await runButton!.trigger('click')
    await flushPromises()

    expect(reports.runOperationalReportData).toHaveBeenCalledTimes(1)
    const body = (reports.runOperationalReportData as unknown as Mock).mock.calls[0][1]
    expect(body.reportType).toBe('MISSING_DATA')
    expect(body.reportParams.attributes).toEqual(['mail', 'sn', 'telephoneNumber'])
    expect(body.reportParams.objectType).toBe('USER')
    expect(body.reportParams).toHaveProperty('branchDn')
  })

  it.each(['USERS_IN_BRANCH', 'MISSING_DATA'])(
    'browses the %s branch picker from the admin\'s profile OU, not the superadmin endpoint',
    async (type) => {
      hoisted.selectedProfile = { targetUserDn: 'ou=people,dc=example,dc=com' }
      const wrapper = mountView()
      await flushPromises()
      await wrapper.find('#rj-report-type').setValue(type)

      const picker = wrapper.findComponent(DnPicker)
      expect(picker.props('superadmin')).toBe(false)
      expect(picker.props('authorizedRoots')).toEqual(['ou=people,dc=example,dc=com'])
    },
  )

  it('falls back to every profile OU in the directory when no profile is picked', async () => {
    hoisted.profiles = [
      { directoryId: 'dir-1', targetUserDn: 'ou=people,dc=example,dc=com' },
      { directoryId: 'dir-1', targetUserDn: 'OU=People,dc=example,dc=com' },
      { directoryId: 'dir-1', targetUserDn: 'ou=staff,dc=example,dc=com' },
      { directoryId: 'dir-2', targetUserDn: 'ou=other,dc=example,dc=com' },
    ]
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('#rj-report-type').setValue('USERS_IN_BRANCH')

    expect(wrapper.findComponent(DnPicker).props('authorizedRoots'))
      .toEqual(['ou=people,dc=example,dc=com', 'ou=staff,dc=example,dc=com'])
  })

  it('lets a superadmin browse the whole tree for the branch picker', async () => {
    hoisted.isSuperadmin = true
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('#rj-report-type').setValue('MISSING_DATA')

    const picker = wrapper.findComponent(DnPicker)
    expect(picker.props('superadmin')).toBe(true)
    expect(picker.props('authorizedRoots')).toEqual([])
  })

  it('scopes the admin\'s group picker to the picked profile\'s target group OU', async () => {
    hoisted.selectedProfile = { targetUserDn: 'ou=people,dc=example,dc=com', targetGroupDn: 'ou=groups,dc=example,dc=com' }
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('#rj-report-type').setValue('USERS_IN_GROUP')

    const picker = wrapper.findComponent(DnPicker)
    expect(picker.props('scope')).toBe('group')
    expect(picker.props('baseDn')).toBe('ou=groups,dc=example,dc=com')
  })

  it('leaves the group picker unscoped for a superadmin', async () => {
    hoisted.isSuperadmin = true
    hoisted.selectedProfile = { targetGroupDn: 'ou=groups,dc=example,dc=com' }
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('#rj-report-type').setValue('USERS_IN_GROUP')

    expect(wrapper.findComponent(DnPicker).props('baseDn')).toBe('')
  })

  it('hides the attribute list for other report types', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('#rj-report-type').setValue('USERS_IN_BRANCH')
    expect(wrapper.find('#rj-attributes').exists()).toBe(false)
  })

  it('persists the attribute list and object type for a scheduled Missing Data job', async () => {
    const reports = await import('@/api/reports')
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('MISSING_DATA')
    expect(wrapper.find('#rj-job-attributes').exists()).toBe(true)
    await wrapper.find('#rj-job-attributes').setValue('mail, manager')
    await wrapper.find('#rj-job-object-type').setValue('USER')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(reports.createReportJob).toHaveBeenCalled()
    const params = (reports.createReportJob as unknown as Mock).mock.calls[0][1].reportParams
    expect(params.attributes).toEqual(['mail', 'manager'])
    expect(params.objectType).toBe('USER')
  })

  it('pins a new admin job to the picked profile\'s OU', async () => {
    hoisted.selectedProfile = { targetUserDn: 'ou=people,dc=example,dc=com' }
    const reports = await import('@/api/reports')
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('DISABLED_ACCOUNTS')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const params = (reports.createReportJob as unknown as Mock).mock.calls[0][1].reportParams
    expect(params.scopeBaseDn).toBe('ou=people,dc=example,dc=com')
  })

  it.each(['USERS_IN_BRANCH', 'MISSING_DATA'])(
    'leaves scopeBaseDn off a %s job so the backend derives it from the branch',
    async (type) => {
      hoisted.selectedProfile = { targetUserDn: 'ou=people,dc=example,dc=com' }
      const reports = await import('@/api/reports')
      const wrapper = mountView()
      await flushPromises()
      await wrapper.find('summary').trigger('click')
      await flushPromises()

      await wrapper.find('#rj-job-report-type').setValue(type)
      if (type === 'MISSING_DATA') await wrapper.find('#rj-job-attributes').setValue('mail')
      await wrapper.find('form').trigger('submit')
      await flushPromises()

      const params = (reports.createReportJob as unknown as Mock).mock.calls[0][1].reportParams
      expect(params).toHaveProperty('branchDn')
      expect(params).not.toHaveProperty('scopeBaseDn')
    },
  )

  it('does not add a profile scope to a superadmin\'s new job', async () => {
    hoisted.isSuperadmin = true
    hoisted.selectedProfile = { targetUserDn: 'ou=people,dc=example,dc=com' }
    const reports = await import('@/api/reports')
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('DISABLED_ACCOUNTS')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const params = (reports.createReportJob as unknown as Mock).mock.calls[0][1].reportParams
    expect(params).not.toHaveProperty('scopeBaseDn')
  })

  it('keeps the scope an edited job already stores', async () => {
    hoisted.selectedProfile = { targetUserDn: 'ou=other,dc=example,dc=com' }
    const reports = await import('@/api/reports')
    ;(reports.listReportJobs as unknown as Mock).mockResolvedValue({
      data: { content: [
        { id: 'job-1', name: 'Weekly', reportType: 'DISABLED_ACCOUNTS', cronExpression: '0 8 * * 1',
          enabled: true, timezone: 'UTC', outputFormat: 'CSV', deliveryMethod: 'EMAIL', recipientEmail: 'a@b.com',
          reportParams: { scopeBaseDn: 'ou=people,dc=example,dc=com' } },
      ] },
    })
    const wrapper = mountView()
    await flushPromises()
    await scheduledJobsButton(wrapper)!.trigger('click')
    await flushPromises()

    await wrapper.findAll('button').find(b => b.text() === 'Edit')!.trigger('click')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(reports.updateReportJob).toHaveBeenCalled()
    const params = (reports.updateReportJob as unknown as Mock).mock.calls[0][2].reportParams
    expect(params.scopeBaseDn).toBe('ou=people,dc=example,dc=com')
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

describe('ReportJobsView — result cells', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    hoisted.hasFeature = vi.fn((_k: string) => true)
    hoisted.isSuperadmin = false
    hoisted.selectedProfile = null
    hoisted.profiles = []
  })

  it('shows the whole DN, clipped only by the column, so widening a column shows more', async () => {
    const reports = await import('@/api/reports')
    const dn = 'uid=jsmith,ou=engineering,ou=people,dc=example,dc=com'
    ;(reports.runOperationalReportData as unknown as Mock).mockResolvedValueOnce({
      data: { columns: ['dn', 'mail'], rows: [{ dn, mail: 'jsmith@example.com' }] },
    })
    const wrapper = mount(ReportJobsView, {
      global: { stubs: { AppModal: AppModalSlotStub, ConfirmDialog: true, DnPicker: true, FormField: true } },
    })
    await flushPromises()

    await wrapper.findAll('button').find(b => b.text() === 'Run Report')!.trigger('click')
    await flushPromises()

    const cells = wrapper.findAll('td span.font-mono')
    const dnCell = cells.find(c => c.attributes('title') === dn)!
    expect(dnCell.text()).toBe(dn)
    // No fixed cap: the span truncates against the column's set width.
    for (const c of cells) expect(c.classes()).not.toContain('max-w-xs')
  })
})

