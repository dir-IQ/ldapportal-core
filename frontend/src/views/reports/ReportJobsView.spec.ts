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
vi.mock('@/composables/useApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/composables/useApi')>()),
  downloadBlob: vi.fn(),
}))
vi.mock('@/api/directories', () => ({ listDirectories: vi.fn().mockResolvedValue({ data: [] }) }))
vi.mock('@/api/browse', () => ({ checkIntegrity: vi.fn().mockResolvedValue({ data: {} }) }))
vi.mock('@/api/audit', () => ({ getAuditActions: vi.fn().mockResolvedValue({ data: [] }) }))
vi.mock('@/api/schema', () => ({
  listAttributeTypes: vi.fn().mockResolvedValue({ data: [
    { name: 'givenName' }, { name: 'employeeNumber' }, { name: 'createTimestamp' },
  ] }),
}))

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

  it('offers both IVIA reports, named from the product constant, only with the addon on', async () => {
    const optionsOf = (w: ReturnType<typeof mountView>) =>
      w.find('#rj-report-type').findAll('option').map(o => [o.attributes('value'), o.text()])

    let wrapper = mountView()
    await flushPromises()
    expect(optionsOf(wrapper).map(([v]) => v)).not.toContain('USERS_WITHOUT_IVIA_ACCOUNT')

    hoisted.isIsvaIntegrationEnabled = true
    wrapper = mountView()
    await flushPromises()
    expect(optionsOf(wrapper)).toEqual(expect.arrayContaining([
      ['ORPHANED_IVIA_ACCOUNTS', 'Orphaned IVIA Accounts'],
      ['USERS_WITHOUT_IVIA_ACCOUNT', 'Users Without an IVIA Account'],
    ]))
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

  async function runReport(type: string, data: Record<string, unknown>) {
    const reports = await import('@/api/reports')
    ;(reports.runOperationalReportData as unknown as Mock).mockResolvedValueOnce({ data })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('#rj-report-type').setValue(type)
    await wrapper.findAll('button').find(b => b.text() === 'Run Report')!.trigger('click')
    await flushPromises()
    return { wrapper, reports }
  }
  type Col = { key: string, label: string, defaultHidden?: boolean }
  const tableColumnsOf = (w: Awaited<ReturnType<typeof runReport>>['wrapper']) =>
    w.findComponent({ name: 'ResultsTable' }).props('columns') as Col[]

  it('gives entry reports the Users page columns: real attribute names, the same defaults and order', async () => {
    const attrs = ['cn', 'mail', 'sn', 'givenname', 'uid', 'title', 'ou', 'l', 'employeenumber', 'userpassword']
    const { wrapper } = await runReport('USERS_IN_BRANCH',
      { columns: ['dn', ...attrs, 'groups', 'allgroups'], labels: {}, rows: [] })

    const columns = tableColumnsOf(wrapper)
    // dn → defaults present (their fixed order) → Groups (all groups) → the
    // rest A–Z, direct groups among them; secrets dropped.
    expect(columns.map(c => c.key)).toEqual(
      ['dn', 'cn', 'sn', 'givenname', 'mail', 'uid', 'title', 'allgroups', 'groups', 'employeenumber', 'l', 'ou'])
    // Headed by the attribute name, schema-cased — no friendly names.
    expect(columns.find(c => c.key === 'cn')!.label).toBe('cn')
    expect(columns.find(c => c.key === 'givenname')!.label).toBe('givenName')
    expect(columns.find(c => c.key === 'employeenumber')!.label).toBe('employeeNumber')
    expect(columns.find(c => c.key === 'allgroups')!.label).toBe('groups')
    expect(columns.find(c => c.key === 'groups')!.label).toBe('direct groups')
    expect(columns.filter(c => !c.defaultHidden).map(c => c.key))
      .toEqual(['dn', 'cn', 'sn', 'givenname', 'mail', 'uid', 'title', 'allgroups'])
  })

  it('keeps other reports in the backend order, headed by the backend label or the key', async () => {
    const { wrapper } = await runReport('MISSING_DATA', {
      columns: ['dn', 'missingAttributes', 'objectClass', 'mail'],
      labels: { missingAttributes: 'Missing Attributes' }, rows: [],
    })

    expect(tableColumnsOf(wrapper).map(c => [c.key, c.label])).toEqual([
      ['dn', 'dn'], ['missingAttributes', 'Missing Attributes'], ['objectClass', 'objectClass'], ['mail', 'mail'],
    ])
  })

  it('exports the visible columns, in order, by their schema-cased names', async () => {
    const { wrapper, reports } = await runReport('USERS_IN_BRANCH',
      { columns: ['dn', 'cn', 'givenname', 'groups', 'allgroups'], labels: {}, rows: [] })
    wrapper.findComponent({ name: 'ResultsTable' }).vm.$emit('visible-columns-change', ['dn', 'givenname', 'allgroups'])
    await flushPromises()

    // ResultsTable is stubbed (no toolbar), so call what its Export CSV button calls.
    await (wrapper.vm as unknown as { doExport: (f: string) => Promise<void> }).doExport('CSV')

    // The Groups column exports as the import's direct groups, then all groups.
    expect(reports.runOperationalReport).toHaveBeenCalledWith('dir-1',
      expect.objectContaining({ columns: ['dn', 'givenName', 'groups', 'allgroups'] }), 'CSV')
  })

  it('exports direct groups once when that column is shown as well', async () => {
    const { wrapper, reports } = await runReport('USERS_IN_BRANCH',
      { columns: ['dn', 'groups', 'allgroups'], labels: {}, rows: [] })
    wrapper.findComponent({ name: 'ResultsTable' }).vm.$emit('visible-columns-change', ['dn', 'groups', 'allgroups'])
    await flushPromises()

    await (wrapper.vm as unknown as { doExport: (f: string) => Promise<void> }).doExport('CSV')

    expect(reports.runOperationalReport).toHaveBeenCalledWith('dir-1',
      expect.objectContaining({ columns: ['dn', 'groups', 'allgroups'] }), 'CSV')
  })

  it('asks entry reports for operational attributes when the switch is on', async () => {
    localStorage.setItem('ldapportal:operational-attributes:report-jobs', 'true')
    try {
      const { reports } = await runReport('USERS_IN_BRANCH', { columns: ['dn'], labels: {}, rows: [] })
      expect(reports.runOperationalReportData).toHaveBeenLastCalledWith('dir-1', expect.objectContaining({
        reportParams: expect.objectContaining({ includeOperational: true }),
      }))
    } finally {
      localStorage.removeItem('ldapportal:operational-attributes:report-jobs')
    }
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

  it('loads every page of jobs, not just the first', async () => {
    const reports = await import('@/api/reports')
    const jobOn = (id: string) => ({ id, name: id, reportType: 'RECENTLY_ADDED', cronExpression: '0 8 * * 1', enabled: true })
    ;(reports.listReportJobs as unknown as Mock).mockImplementation((_dir: string, params: { page: number }) =>
      Promise.resolve({ data: {
        content: params.page === 0 ? [jobOn('job-a'), jobOn('job-b')] : [jobOn('job-c')],
        page: { number: params.page, size: 2, totalElements: 3, totalPages: 2 },
      } }))
    const wrapper = mountView()
    await flushPromises()
    await scheduledJobsButton(wrapper)!.trigger('click')
    await flushPromises()

    const calls = (reports.listReportJobs as unknown as Mock).mock.calls
    expect(calls.map(c => c[1].page)).toEqual([0, 1])
    expect(calls[0][1]).toMatchObject({ sort: 'name,asc' })
    expect(wrapper.text()).toContain('job-a')
    expect(wrapper.text()).toContain('job-c')
  })

  it('shows who created each job, in the list and read-only in the edit form', async () => {
    const reports = await import('@/api/reports')
    ;(reports.listReportJobs as unknown as Mock).mockResolvedValue({
      data: { content: [
        { id: 'job-1', name: 'Weekly', reportType: 'RECENTLY_ADDED', cronExpression: '0 8 * * 1',
          enabled: true, timezone: 'UTC', createdByName: 'Alice Admin' },
      ], page: { number: 0, size: 100, totalElements: 1, totalPages: 1 } },
    })
    const wrapper = mountView()
    await flushPromises()
    await scheduledJobsButton(wrapper)!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('th').map(th => th.text())).toContain('Created By')
    expect(wrapper.text()).toContain('Alice Admin')

    await wrapper.findAll('button').find(b => b.text() === 'Edit')!.trigger('click')
    await flushPromises()
    // FormField is stubbed here, so check the props it receives.
    const creator = wrapper.findAllComponents({ name: 'FormField' }).find(f => f.props('label') === 'Created By')
    expect(creator).toBeTruthy()
    expect(creator!.props('modelValue')).toBe('Alice Admin')
    expect(creator!.props('disabled')).toBe(true)
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
  // ── a scheduled file matches the same report run on demand ──

  it('names an exported CSV like the scheduled delivery of the same report', async () => {
    const { wrapper } = await runReport('DISABLED_ACCOUNTS', { columns: ['dn'], labels: {}, rows: [] })
    const { downloadBlob } = await import('@/composables/useApi')

    await (wrapper.vm as unknown as { doExport: (f: string) => Promise<void> }).doExport('CSV')

    expect(downloadBlob).toHaveBeenCalledWith(expect.anything(), 'disabled-accounts.csv')
  })

  it('persists operational attributes for a scheduled entry report', async () => {
    const reports = await import('@/api/reports')
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('DISABLED_ACCOUNTS')
    await wrapper.find('#rj-job-include-operational').setValue(true)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const params = (reports.createReportJob as unknown as Mock).mock.calls[0][1].reportParams
    expect(params.includeOperational).toBe(true)
  })

  it('offers no operational-attributes option for reports that are not entry listings', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('AUDIT_ENTRIES')

    expect(wrapper.find('#rj-job-include-operational').exists()).toBe(false)
  })

  it('persists the columns a scheduled file carries, in order', async () => {
    const reports = await import('@/api/reports')
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('DISABLED_ACCOUNTS')
    await wrapper.find('#rj-job-columns').setValue('dn, mail, cn')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const params = (reports.createReportJob as unknown as Mock).mock.calls[0][1].reportParams
    expect(params.columns).toEqual(['dn', 'mail', 'cn'])
  })

  it('leaves columns off a job that keeps every column', async () => {
    const reports = await import('@/api/reports')
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('DISABLED_ACCOUNTS')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const params = (reports.createReportJob as unknown as Mock).mock.calls[0][1].reportParams
    expect(params).not.toHaveProperty('columns')
  })

  it('copies the on-screen columns into a job for the report on screen', async () => {
    const { wrapper } = await runReport('USERS_IN_BRANCH',
      { columns: ['dn', 'cn', 'givenname', 'groups'], labels: {}, rows: [] })
    wrapper.findComponent({ name: 'ResultsTable' }).vm.$emit('visible-columns-change', ['dn', 'givenname', 'groups'])
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('USERS_IN_BRANCH')
    await wrapper.findAll('button').find(b => b.text() === 'Use on-screen columns')!.trigger('click')

    expect((wrapper.find('#rj-job-columns').element as HTMLInputElement).value).toBe('dn, givenName, groups')
  })

  it('does not offer the on-screen columns for a job of a different report', async () => {
    const { wrapper } = await runReport('USERS_IN_BRANCH',
      { columns: ['dn', 'cn'], labels: {}, rows: [] })
    wrapper.findComponent({ name: 'ResultsTable' }).vm.$emit('visible-columns-change', ['dn', 'cn'])
    await flushPromises()
    await wrapper.find('summary').trigger('click')
    await flushPromises()

    await wrapper.find('#rj-job-report-type').setValue('DISABLED_ACCOUNTS')

    expect(wrapper.findAll('button').some(b => b.text() === 'Use on-screen columns')).toBe(false)
  })

  it('keeps an edited job\'s columns and operational-attributes choice', async () => {
    const reports = await import('@/api/reports')
    ;(reports.listReportJobs as unknown as Mock).mockResolvedValue({
      data: { content: [
        { id: 'job-1', name: 'Weekly', reportType: 'DISABLED_ACCOUNTS', cronExpression: '0 8 * * 1',
          enabled: true, timezone: 'UTC', outputFormat: 'CSV', deliveryMethod: 'EMAIL', recipientEmail: 'a@b.com',
          reportParams: { columns: ['dn', 'mail'], includeOperational: true } },
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

    const params = (reports.updateReportJob as unknown as Mock).mock.calls[0][2].reportParams
    expect(params.columns).toEqual(['dn', 'mail'])
    expect(params.includeOperational).toBe(true)
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

