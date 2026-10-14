// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { dirId: 'd1' } }) }))
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))
// Features the mocked admin holds; tests narrow it (e.g. to a read-only
// admin's export-only set) and the beforeEach below restores the default.
const DEFAULT_FEATURES = ['bulk.import', 'bulk.export', 'csv_template.manage']
const grantedFeatures = vi.hoisted(() => new Set<string>())
// Which build is "running"; the group-import hint drops SoD on community.
const edition = vi.hoisted(() => ({ community: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasFeature: (f: string) => grantedFeatures.has(f),
    isSuperadmin: false,
    get isCommunityDistribution() { return edition.community },
  }),
}))
// The active profile comes from the sidebar picker store.
vi.mock('@/stores/profilePicker', () => ({
  useProfilePickerStore: () => ({ selectedId: 'p1', selectedProfile: null, profiles: [] }),
}))
vi.mock('@/composables/useApi', () => ({ downloadBlob: vi.fn() }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => () => Promise.resolve(true) }))
vi.mock('@/api/schema', () => ({
  listObjectClasses: vi.fn(() => Promise.resolve({ data: [] })),
  getObjectClassesBulk: vi.fn(() => Promise.resolve({ data: {} })),
}))
vi.mock('@/api/profiles', () => ({
  listProfiles: vi.fn(() => Promise.resolve({ data: [
    { id: 'p1', name: 'Engineers', themeColor: '#2563eb', targetUserDn: 'ou=eng,dc=x',
      objectClassNames: ['inetOrgPerson'], rdnAttribute: 'uid' },
  ] })),
}))
vi.mock('@/api/csvTemplates', () => ({
  listCsvTemplates: vi.fn(() => Promise.resolve({ data: [
    { id: 't1', name: 'Staff', targetKeyAttribute: 'uid', conflictHandling: 'SKIP',
      objectClass: 'inetOrgPerson', skipHeaderRow: true, dnSourceColumn: '', entries: [] },
  ] })),
  createCsvTemplate: vi.fn(), updateCsvTemplate: vi.fn(), deleteCsvTemplate: vi.fn(),
  previewCsv: vi.fn(() => Promise.resolve({ data: {
    totalRows: 1, rows: [{ rowNumber: 1, computedDn: 'uid=a,ou=eng,dc=x', attributes: {} }] } })),
  importCsv: vi.fn(() => Promise.resolve({ status: 200, data: {
    totalRows: 1, created: 1, updated: 0, skipped: 0, errors: 0, rows: [] } })),
  exportCsv: vi.fn(),
  previewGroupCsv: vi.fn(), importGroupCsv: vi.fn(), exportGroupCsv: vi.fn(),
  checkContainerExists: vi.fn(() => Promise.resolve({ data: { exists: true } })),
  createContainer: vi.fn(),
}))

import { previewCsv, importCsv, listCsvTemplates, updateCsvTemplate, previewGroupCsv, importGroupCsv } from '@/api/csvTemplates'
import { getObjectClassesBulk, listObjectClasses } from '@/api/schema'
// The view registers an unsaved-changes guard (and reads the sidebar
// picker) through real Pinia stores; give every test a fresh instance.
beforeEach(() => {
  setActivePinia(createPinia())
  grantedFeatures.clear()
  DEFAULT_FEATURES.forEach(f => grantedFeatures.add(f))
})

import BulkView from './BulkView.vue'
import { FEATURE_PERMISSION_LABELS } from '@/constants/featurePermissions'

const global = {
  stubs: {
    PageContainer: { template: '<div><slot/></div>' },
    DnPicker: true, AppModal: true, FormField: true, ConfirmDialog: true, BulkDeleteSection: true,
  },
}

async function attachUserFile(w: ReturnType<typeof mount>) {
  const input = w.find('input[type="file"][aria-label="CSV File"]')
  const file = new File(['uid\na\n'], 'u.csv', { type: 'text/csv' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}
function btnByText(w: ReturnType<typeof mount>, text: string) {
  return w.findAll('button').find(b => b.text().includes(text))!
}

describe('BulkView — user import scoped to the active (sidebar) profile', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows the Active-profile field and gates Perform Import on the profile name', async () => {
    const w = mount(BulkView, { global })
    await flushPromises() // onMounted loads profiles + templates

    // The read-only Active-profile field reflects the sidebar selection.
    expect(w.text()).toContain('Active profile')
    expect(w.text()).toContain('Engineers')

    await w.find('#bulk-import-template').setValue('t1')
    await attachUserFile(w)

    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()
    // Preview targets the active profile, no parent DN.
    expect(previewCsv).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ profileId: 'p1' }))

    // Perform Import is disabled until the profile name is typed.
    expect(btnByText(w, 'Perform Import').attributes('disabled')).toBeDefined()
    await w.find('input[aria-label="Type the profile name to confirm"]').setValue('Engineers')
    expect(btnByText(w, 'Perform Import').attributes('disabled')).toBeUndefined()

    await btnByText(w, 'Perform Import').trigger('click')
    await flushPromises()
    expect(importCsv).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ profileId: 'p1' }))
  })
})

describe('BulkView — user import preview issues', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows why a DN could not be resolved instead of just "missing"', async () => {
    vi.mocked(previewCsv).mockResolvedValueOnce({ data: {
      totalRows: 1,
      rows: [{ rowNumber: 1, computedDn: null, attributes: {}, missingRequired: ['dn'],
        message: "DN 'cn=Jane,ou=contractors,dc=x' is not within the import container 'ou=eng,dc=x'" }],
    } } as never)
    const w = mount(BulkView, { global })
    await flushPromises()
    await w.find('#bulk-import-template').setValue('t1')
    await attachUserFile(w)

    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()

    const issues = w.find('[data-testid="user-preview-issues"]').text()
    expect(issues).toContain('is not within the import container')
    expect(issues).not.toContain('Missing: dn')
  })
})

// Renders the template modal's slot so its form can be driven.
const modalGlobal = {
  stubs: { ...global.stubs, AppModal: { template: '<div><slot/></div>' } },
}

// RDN attribute cn is one of the mapped attributes, so the form can be saved.
const editableTemplate = {
  id: 't1', name: 'Staff', targetKeyAttribute: 'cn', conflictHandling: 'SKIP',
  objectClass: 'inetOrgPerson', skipHeaderRow: true, dnSourceColumn: '',
  fieldDelimiter: ',',
  entries: [
    { csvColumn: 'email', ldapAttribute: 'mail', ignored: false },
    { csvColumn: 'surname', ldapAttribute: 'sn', ignored: false },
    { csvColumn: 'name', ldapAttribute: 'cn', ignored: false },
  ],
}

async function openEditTemplate(template: object = editableTemplate) {
  vi.mocked(listCsvTemplates).mockResolvedValueOnce({ data: [template] } as never)
  const w = mount(BulkView, { global: modalGlobal })
  await flushPromises()
  await w.find('#bulk-import-template').setValue('t1')
  await btnByText(w, 'Template').trigger('click')
  await btnByText(w, 'Edit Template').trigger('click')
  await flushPromises()
  return w
}

/** Typed attribute per visible mapping row, keyed by column. */
function rowMap(w: ReturnType<typeof mount>) {
  return w.findAll('[data-column]').map(r => {
    const attr = r.find('input[role="combobox"]').element as HTMLInputElement
    return [r.attributes('data-column'), attr.value]
  })
}

describe('BulkView — template column mappings', () => {
  beforeEach(() => vi.clearAllMocks())

  it('does not fill the list from the object classes', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(
      { data: { required: ['cn', 'sn'], optional: ['mail', 'uid', 'givenName'] } } as never)
    const w = await openEditTemplate({ ...editableTemplate, entries: [] })
    expect(w.findAll('[data-column]')).toHaveLength(0)
    expect(w.text()).toContain('Choose a sample file, or add input columns')
  })

  it('opens saved mappings exactly as saved', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(
      { data: { required: ['cn', 'sn'], optional: ['mail', 'uid'] } } as never)
    const w = await openEditTemplate()
    expect(rowMap(w)).toEqual([['email', 'mail'], ['surname', 'sn'], ['name', 'cn']])
  })

  it('adds and removes typed input columns', async () => {
    const w = await openEditTemplate({ ...editableTemplate, entries: [] })
    await btnByText(w, '+ Add input column').trigger('click')
    await w.find('input[aria-label="CSV column for row 1"]').setValue('login')
    expect(rowMap(w)).toEqual([['login', '']])
    await w.find('button[aria-label="Remove login"]').trigger('click')
    expect(w.findAll('[data-column]')).toHaveLength(0)
  })
})

describe('BulkView — template field delimiter', () => {
  beforeEach(() => vi.clearAllMocks())

  it('saves a preset delimiter on the template', async () => {
    vi.mocked(updateCsvTemplate).mockResolvedValueOnce({ data: {} } as never)
    const w = await openEditTemplate()

    await w.find('#bulk-template-field-delimiter').setValue('\t')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(updateCsvTemplate).toHaveBeenCalledWith('d1', 't1',
      expect.objectContaining({ fieldDelimiter: '\t' }))
  })

  it('accepts a custom single character and blocks save on a double quote', async () => {
    vi.mocked(updateCsvTemplate).mockResolvedValueOnce({ data: {} } as never)
    const w = await openEditTemplate()

    await w.find('#bulk-template-field-delimiter').setValue('other')
    const custom = w.find('#bulk-template-field-delimiter-other')
    await custom.setValue('"')
    expect(btnByText(w, 'Save').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain('Enter one character other than a double quote.')

    await custom.setValue('~')
    expect(btnByText(w, 'Save').attributes('disabled')).toBeUndefined()
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(updateCsvTemplate).toHaveBeenCalledWith('d1', 't1',
      expect.objectContaining({ fieldDelimiter: '~' }))
  })

  it('opens a template with a non-preset delimiter on "Other"', async () => {
    const w = await openEditTemplate({ ...editableTemplate, fieldDelimiter: '~' })
    expect((w.find('#bulk-template-field-delimiter').element as HTMLSelectElement).value).toBe('other')
    expect((w.find('#bulk-template-field-delimiter-other').element as HTMLInputElement).value).toBe('~')
  })

  it("sends the selected template's delimiter with the import preview", async () => {
    vi.mocked(listCsvTemplates).mockResolvedValueOnce({ data: [
      { ...editableTemplate, fieldDelimiter: ';' },
    ] } as never)
    const w = mount(BulkView, { global })
    await flushPromises()
    await w.find('#bulk-import-template').setValue('t1')
    await attachUserFile(w)
    expect(w.text()).toContain('Fields are separated by')

    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()
    expect(previewCsv).toHaveBeenCalledWith('d1', expect.any(File),
      expect.objectContaining({ fieldDelimiter: ';' }))
  })
})

// Real FormField + rendered modal slot so the template editor can be driven.
const editorGlobal = {
  stubs: {
    PageContainer: { template: '<div><slot/></div>' },
    AppModal: { template: '<div><slot/></div>' },
    DnPicker: true, ConfirmDialog: true, BulkDeleteSection: true,
  },
}

async function editTemplate(dnSourceColumn: string | null) {
  vi.mocked(listCsvTemplates).mockResolvedValueOnce({ data: [
    { id: 't1', name: 'Staff', targetKeyAttribute: 'uid', conflictHandling: 'SKIP',
      objectClass: 'inetOrgPerson', skipHeaderRow: true, dnSourceColumn, entries: [] },
  ] } as never)
  vi.mocked(updateCsvTemplate).mockResolvedValueOnce({ data: {} } as never)
  const w = mount(BulkView, { global: editorGlobal })
  await flushPromises()
  await w.find('#bulk-import-template').setValue('t1')
  await btnByText(w, 'Template').trigger('click')
  await btnByText(w, 'Edit Template').trigger('click')
  await flushPromises()
  return w
}

const dnColumnInput = (w: ReturnType<typeof mount>) => w.find('input[placeholder="dn"]')

describe('BulkView — template "Read DN from CSV column"', () => {
  beforeEach(() => vi.clearAllMocks())

  it('saves the column when switched on without typing (placeholder becomes the value)', async () => {
    const w = await editTemplate(null)
    await w.find('#bulk-template-dn-source').setValue('column')
    expect((dnColumnInput(w).element as HTMLInputElement).value).toBe('dn')

    await w.find('form').trigger('submit')
    await flushPromises()
    // Regression: a blank column used to be sent as null — saved as
    // "build DN from RDN", so the option looked like it never stuck.
    expect(updateCsvTemplate).toHaveBeenCalledWith('d1', 't1',
      expect.objectContaining({ dnSourceColumn: 'dn' }))
  })

  it('marks the DN column field required in column mode', async () => {
    const w = await editTemplate(null)
    await w.find('#bulk-template-dn-source').setValue('column')
    expect(dnColumnInput(w).attributes('required')).toBeDefined()
  })

  it('shows the red required star on the DN column label in column mode', async () => {
    const w = await editTemplate(null)
    await w.find('#bulk-template-dn-source').setValue('column')
    const label = w.find(`label[for="${dnColumnInput(w).attributes('id')}"]`)
    expect(label.text()).toBe('DN column *')
    expect(label.find('span.text-red-500').text()).toBe('*')
  })

  it('blocks save while column mode has no column name', async () => {
    const w = await editTemplate(null)
    await w.find('#bulk-template-dn-source').setValue('column')
    await dnColumnInput(w).setValue('')

    expect(btnByText(w, 'Save').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain("Enter the CSV column that holds each entry's DN")
  })

  it('reopens a template that reads the DN from a column and keeps it on save', async () => {
    const w = await editTemplate('employeeDn')
    expect((w.find('#bulk-template-dn-source').element as HTMLSelectElement).value).toBe('column')
    expect((dnColumnInput(w).element as HTMLInputElement).value).toBe('employeeDn')

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(updateCsvTemplate).toHaveBeenCalledWith('d1', 't1',
      expect.objectContaining({ dnSourceColumn: 'employeeDn' }))
  })
})

describe('BulkView — selected template summary', () => {
  beforeEach(() => vi.clearAllMocks())

  async function selectTemplate(dnSourceColumn: string | null) {
    vi.mocked(listCsvTemplates).mockResolvedValueOnce({ data: [
      { id: 't1', name: 'Staff', targetKeyAttribute: 'uid', conflictHandling: 'SKIP',
        objectClass: 'inetOrgPerson', skipHeaderRow: true, dnSourceColumn,
        entries: [
          { csvColumn: 'user', ldapAttribute: 'uid', ignored: false },
          { csvColumn: 'email', ldapAttribute: 'mail', ignored: false },
        ] },
    ] } as never)
    const w = mount(BulkView, { global })
    await flushPromises()
    await w.find('#bulk-import-template').setValue('t1')
    return w
  }

  it('shows the DN column instead of the RDN attribute when the DN is read from a column', async () => {
    const w = await selectTemplate('employeeDn')
    expect(w.find('#bulk-template-rdn-attribute').exists()).toBe(false)
    expect((w.find('#bulk-template-dn-column').element as HTMLInputElement).value).toBe('employeeDn')
    // uid isn't the key any more, so it's listed with the other mapped attributes.
    expect((w.find('#bulk-template-other-attributes').element as HTMLInputElement).value).toBe('uid, mail')
  })

  it('shows the RDN attribute when the DN is built from it', async () => {
    const w = await selectTemplate(null)
    expect(w.find('#bulk-template-dn-column').exists()).toBe(false)
    expect((w.find('#bulk-template-rdn-attribute').element as HTMLInputElement).value).toBe('uid')
    expect((w.find('#bulk-template-other-attributes').element as HTMLInputElement).value).toBe('mail')
  })
})

describe('BulkView — import result', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists skipped rows as information, separate from errors', async () => {
    vi.mocked(importCsv).mockResolvedValueOnce({ status: 200, data: {
      totalRows: 2, created: 0, updated: 0, skipped: 1, errors: 1, rows: [
        { rowNumber: 1, dn: 'uid=a,ou=eng,dc=x', status: 'SKIPPED',
          message: 'Already exists — skipped (template conflict handling: Skip existing)' },
        { rowNumber: 2, dn: 'uid=b,ou=eng,dc=x', status: 'ERROR', message: 'Object class violation' },
      ] } } as never)
    const w = mount(BulkView, { global })
    await flushPromises()
    await w.find('#bulk-import-template').setValue('t1')
    await attachUserFile(w)
    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()
    await w.find('input[aria-label="Type the profile name to confirm"]').setValue('Engineers')
    await btnByText(w, 'Perform Import').trigger('click')
    await flushPromises()

    const skipped = w.find('[data-testid="user-import-skipped"]')
    expect(skipped.text()).toContain('Skipped (1)')
    expect(skipped.text()).toContain('Row 1 (uid=a,ou=eng,dc=x): Already exists — skipped')
    // Informational styling — not the red error treatment.
    expect(skipped.find('li').classes()).not.toContain('text-red-600')
    const errors = w.findAll('li.text-red-600')
    expect(errors).toHaveLength(1)
    expect(errors[0].text()).toContain('Row 2')
  })
})

// ── Sample input file + typed mappings ───────────────────────────────────────

const rowStatuses = (w: ReturnType<typeof mount>) =>
  w.findAll('[data-status]').map(r => r.attributes('data-status'))

async function chooseSample(w: ReturnType<typeof mount>, content: string, name = 'sample.csv') {
  const input = w.find('input[type="file"][aria-label="Sample Input File"]')
  const file = new File([content], name, { type: 'text/csv' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

const schema = { data: { required: ['cn', 'sn'], optional: ['mail', 'uid'] } }

async function typeAttr(w: ReturnType<typeof mount>, column: string, value: string) {
  const input = w.find(`input[aria-label="LDAP attribute for ${column}"]`)
  await input.setValue(value)
  await input.trigger('blur')
}

describe('BulkView — template sample input file', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists the sample columns, keeping saved mappings by name (ignoring case)', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    const w = await openEditTemplate()
    await chooseSample(w, 'login,EMAIL,name,surname,CostCenter\nx,y,z,q,1\n', 'hr.csv')
    expect(w.text()).toContain('hr.csv')
    expect(w.find('[data-testid="sample-columns"]').text()).toContain('5 columns')
    expect(rowMap(w)).toEqual([
      ['login', ''], ['EMAIL', 'mail'], ['name', 'cn'], ['surname', 'sn'], ['CostCenter', ''],
    ])
    expect(rowStatuses(w)).toEqual(['ignored', 'mapped', 'mapped', 'mapped', 'ignored'])
  })

  it('keeps a mapped column the sample lacks, flagged, with a remove button', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    const w = await openEditTemplate()
    await chooseSample(w, 'name,surname\n')
    expect(rowMap(w)).toEqual([['name', 'cn'], ['surname', 'sn'], ['email', 'mail']])
    expect(w.text()).toContain('Not in the sample file')
    await w.find('button[aria-label="Remove email"]').trigger('click')
    expect(rowMap(w)).toEqual([['name', 'cn'], ['surname', 'sn']])
  })

  it('saves columns without an attribute as ignored entries', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    vi.mocked(updateCsvTemplate).mockResolvedValueOnce({ data: {} } as never)
    const w = await openEditTemplate()
    await chooseSample(w, 'name,surname,email,CostCenter\n')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(updateCsvTemplate).toHaveBeenCalledWith('d1', 't1', expect.objectContaining({
      entries: [
        { csvColumn: 'name', ldapAttribute: 'cn', ignored: false },
        { csvColumn: 'surname', ldapAttribute: 'sn', ignored: false },
        { csvColumn: 'email', ldapAttribute: 'mail', ignored: false },
        { csvColumn: 'CostCenter', ldapAttribute: null, ignored: true },
      ],
    }))
  })

  it('restores saved ignored columns when editing without a sample file', async () => {
    const w = await openEditTemplate({
      ...editableTemplate,
      entries: [...editableTemplate.entries, { csvColumn: 'CostCenter', ldapAttribute: null, ignored: true }],
    })
    expect(rowMap(w).map(r => r[0])).toEqual(['email', 'surname', 'name', 'CostCenter'])
    expect(rowStatuses(w)).toEqual(['mapped', 'mapped', 'mapped', 'ignored'])
  })

  it('blocks save while a required attribute is not mapped', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(
      { data: { required: ['cn', 'sn', 'mail'], optional: [] } } as never)
    const w = await openEditTemplate({ ...editableTemplate, entries: editableTemplate.entries.slice(1) })
    await chooseSample(w, 'surname,name,email\n')
    expect(w.find('[data-testid="missing-required"]').text()).toContain('mail')
    expect(btnByText(w, 'Save').attributes('disabled')).toBeDefined()
    await typeAttr(w, 'email', 'mail')
    expect(w.find('[data-testid="missing-required"]').exists()).toBe(false)
    expect(btnByText(w, 'Save').attributes('disabled')).toBeUndefined()
  })

  it('requires the RDN attribute when the DN is built from it', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    const w = await openEditTemplate({ ...editableTemplate, targetKeyAttribute: 'uid' })
    expect(w.find('[data-testid="missing-required"]').text()).toContain('uid')
    expect(btnByText(w, 'Save').attributes('disabled')).toBeDefined()
  })

  it('blocks save while the object-class attributes are loading', async () => {
    let resolve!: (v: unknown) => void
    vi.mocked(getObjectClassesBulk).mockReturnValueOnce(new Promise(r => { resolve = r }) as never)
    const w = await openEditTemplate()
    expect(btnByText(w, 'Save').attributes('disabled')).toBeDefined()
    resolve(schema)
    await flushPromises()
    expect(btnByText(w, 'Save').attributes('disabled')).toBeUndefined()
  })

  it('warns, without blocking, when an attribute is not in the selected classes', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    const w = await openEditTemplate()
    await chooseSample(w, 'name,surname,email,EmpType\n')
    await typeAttr(w, 'EmpType', 'emplyeeType')
    expect(w.text()).toContain('Not in the selected object classes')
    expect(btnByText(w, 'Save').attributes('disabled')).toBeUndefined()
  })

  it("re-reads the sample when the template's delimiter changes", async () => {
    const w = await openEditTemplate()
    await chooseSample(w, 'name;surname;email\n')
    expect(w.find('[data-testid="sample-columns"]').text()).toContain('1 columns')
    await w.find('#bulk-template-field-delimiter').setValue(';')
    await flushPromises()
    expect(w.find('[data-testid="sample-columns"]').text()).toContain('3 columns')
  })

  it('suggests attributes by name and alias only when asked', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    const w = await openEditTemplate({ ...editableTemplate, entries: [] })
    await chooseSample(w, 'Email,UserName,LastName,FullName,Notes\n')
    expect(rowMap(w).every(r => r[1] === '')).toBe(true)
    await btnByText(w, 'Suggest by name').trigger('click')
    expect(rowMap(w)).toEqual([
      ['Email', 'mail'], ['UserName', 'uid'], ['LastName', 'sn'], ['FullName', 'cn'], ['Notes', ''],
    ])
  })

  it('hides the DN column from the list, marks it in the sample columns and does not save it', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    vi.mocked(updateCsvTemplate).mockResolvedValueOnce({ data: {} } as never)
    const w = await openEditTemplate({
      ...editableTemplate, dnSourceColumn: 'dn',
      // Older editors saved the DN column as an ignored entry.
      entries: [{ csvColumn: 'dn', ldapAttribute: null, ignored: true }, ...editableTemplate.entries],
    })
    expect(rowMap(w).map(r => r[0])).toEqual(['email', 'surname', 'name'])
    await chooseSample(w, 'DN,name,surname,email\n')
    expect(w.find('[data-testid="sample-dn-column"]').text()).toContain('DN')
    expect(rowMap(w).map(r => r[0])).toEqual(['name', 'surname', 'email'])
    expect(w.find('[data-testid="sample-dn-missing"]').exists()).toBe(false)
    await w.find('form').trigger('submit')
    await flushPromises()
    const saved = vi.mocked(updateCsvTemplate).mock.calls[0][2] as { entries: { csvColumn: string }[] }
    expect(saved.entries.map(e => e.csvColumn)).toEqual(['name', 'surname', 'email'])
  })

  it('maps a sample column named groups to group memberships and saves it as @groups', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    vi.mocked(updateCsvTemplate).mockResolvedValueOnce({ data: {} } as never)
    const w = await openEditTemplate()
    await chooseSample(w, 'name,surname,email,groups\n')
    expect(w.find('[data-testid="sample-groups-column"]').text()).toContain('groups')
    expect(w.find('[data-testid="group-memberships-chip"]').exists()).toBe(true)
    await w.find('form').trigger('submit')
    await flushPromises()
    const saved = vi.mocked(updateCsvTemplate).mock.calls[0][2] as { entries: object[] }
    expect(saved.entries).toContainEqual({ csvColumn: 'groups', ldapAttribute: '@groups', ignored: false })
  })

  it('warns when the sample has no DN column and offers DN-like columns', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    const w = await openEditTemplate({ ...editableTemplate, dnSourceColumn: 'dn' })
    await chooseSample(w, 'DistinguishedName,name,surname\n')
    const warning = w.find('[data-testid="sample-dn-missing"]')
    expect(warning.text()).toContain('The sample file has no dn column')
    expect(rowMap(w).map(r => r[0])).toContain('DistinguishedName')
    await warning.find('button').trigger('click')
    expect(w.find('[data-testid="sample-dn-missing"]').exists()).toBe(false)
    expect(rowMap(w).map(r => r[0])).not.toContain('DistinguishedName')
  })

  it('sends ignored columns with the import request so they are not passed through', async () => {
    vi.mocked(listCsvTemplates).mockResolvedValueOnce({ data: [{
      ...editableTemplate,
      entries: [{ csvColumn: 'email', ldapAttribute: 'mail', ignored: false },
        { csvColumn: 'CostCenter', ldapAttribute: null, ignored: true }],
    }] } as never)
    const w = mount(BulkView, { global })
    await flushPromises()
    await w.find('#bulk-import-template').setValue('t1')
    await attachUserFile(w)
    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()
    expect(previewCsv).toHaveBeenCalledWith('d1', expect.any(File), expect.objectContaining({
      columnMappings: [
        { csvColumn: 'email', ldapAttribute: 'mail', ignored: false },
        { csvColumn: 'CostCenter', ldapAttribute: null, ignored: true },
      ],
    }))
  })
})

describe('BulkView — object class type-to-find', () => {
  beforeEach(() => vi.clearAllMocks())

  it('jumps to the first available class starting with the typed letters and adds it on Enter', async () => {
    vi.mocked(listObjectClasses).mockResolvedValueOnce({ data: [
      'account', 'groupOfNames', 'inetLocalMailRecipient', 'inetOrgPerson', 'person',
    ] } as never)
    const w = mount(BulkView, { global: modalGlobal, attachTo: document.body })
    await flushPromises()
    await btnByText(w, 'Template').trigger('click')
    await btnByText(w, 'Add Template').trigger('click')

    const list = w.find('[role="listbox"][aria-label="Available object classes"]')
    for (const k of 'inet') await list.trigger('keydown', { key: k })
    expect(list.find('[aria-selected="true"]').text()).toBe('inetLocalMailRecipient')
    expect(list.attributes('aria-activedescendant')).toBe('oc-av-inetLocalMailRecipient')
    expect(w.text()).toContain('“inet”')

    await list.trigger('keydown', { key: 'o' })
    expect(list.find('[aria-selected="true"]').text()).toBe('inetOrgPerson')

    await list.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    const selected = w.find('[role="listbox"][aria-label="Selected object classes"]')
    expect(selected.text()).toContain('inetOrgPerson')
    expect(getObjectClassesBulk).toHaveBeenCalledWith('d1', ['inetOrgPerson'])
    w.unmount()
  })

  it('flags a prefix that matches nothing', async () => {
    vi.mocked(listObjectClasses).mockResolvedValueOnce({ data: ['account', 'person'] } as never)
    const w = mount(BulkView, { global: modalGlobal })
    await flushPromises()
    await btnByText(w, 'Template').trigger('click')
    await btnByText(w, 'Add Template').trigger('click')
    const list = w.find('[role="listbox"][aria-label="Available object classes"]')
    await list.trigger('keydown', { key: 'z' })
    expect(w.text()).toContain('“z” no match')
    expect(list.find('[aria-selected="true"]').exists()).toBe(false)
  })
})

describe('BulkView — columns the template does not map', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists them in the preview as not imported', async () => {
    vi.mocked(previewCsv).mockResolvedValueOnce({ data: {
      totalRows: 1, rows: [{ rowNumber: 1, computedDn: 'uid=a,ou=eng,dc=x', attributes: {} }],
      unmappedColumns: ['CostCenter', 'Dept'],
    } } as never)
    const w = mount(BulkView, { global })
    await flushPromises()
    await w.find('#bulk-import-template').setValue('t1')
    await attachUserFile(w)
    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()
    const note = w.find('[data-testid="unmapped-columns"]')
    expect(note.text()).toContain('aren’t in the template and won’t be imported')
    expect(note.text()).toContain('CostCenter, Dept')
  })

  it('shows nothing when every column is in the template', async () => {
    const w = mount(BulkView, { global })
    await flushPromises()
    await w.find('#bulk-import-template').setValue('t1')
    await attachUserFile(w)
    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="unmapped-columns"]').exists()).toBe(false)
  })
})

describe('BulkView — tabs follow the admin\'s bulk features', () => {
  beforeEach(() => vi.clearAllMocks())

  const tabLabels = (w: ReturnType<typeof mount>) =>
    w.findAll('button').map(b => b.text()).filter(t => ['Import', 'Export', 'Delete'].includes(t))

  it('shows a read-only admin only the Export tab and opens on it', async () => {
    grantedFeatures.clear()
    grantedFeatures.add('bulk.export')
    const w = mount(BulkView, { global })
    await flushPromises()
    expect(tabLabels(w)).toEqual(['Export'])
    expect(w.text()).toContain('Export Users to CSV')
    expect(w.text()).not.toContain('Import Users from CSV')
    // Template and schema lists back features this admin lacks: not requested.
    expect(listCsvTemplates).not.toHaveBeenCalled()
    expect(listObjectClasses).not.toHaveBeenCalled()
  })

  it('hides the Template menu without template permission but keeps the import', async () => {
    grantedFeatures.delete('csv_template.manage')
    const w = mount(BulkView, { global })
    await flushPromises()
    expect(w.text()).toContain('Import Users from CSV')
    expect(w.findAll('button').some(b => b.text().startsWith('Template'))).toBe(false)
    expect(listObjectClasses).not.toHaveBeenCalled()
  })

  it('shows every tab to a full admin, Delete for users only', async () => {
    grantedFeatures.add('bulk.delete')
    const w = mount(BulkView, { global })
    await flushPromises()
    expect(tabLabels(w)).toEqual(['Import', 'Export', 'Delete'])
    await btnByText(w, 'Groups').trigger('click')
    expect(tabLabels(w)).toEqual(['Import', 'Export'])
  })

  it('moves off Delete when switching to groups', async () => {
    grantedFeatures.clear()
    grantedFeatures.add('bulk.delete')
    grantedFeatures.add('bulk.export')
    const w = mount(BulkView, { global })
    await flushPromises()
    expect(tabLabels(w)).toEqual(['Export', 'Delete'])
    await btnByText(w, 'Delete').trigger('click')
    await btnByText(w, 'Groups').trigger('click')
    expect(w.text()).toContain('Export Groups')
  })

  it('explains when no bulk operation is available', async () => {
    grantedFeatures.clear()
    const w = mount(BulkView, { global })
    await flushPromises()
    expect(tabLabels(w)).toEqual([])
    expect(w.find('[data-testid="bulk-no-access"]').text())
      .toContain('You don’t have permission for bulk user operations')
  })
})

describe('BulkView — group import member changes', () => {
  beforeEach(() => vi.clearAllMocks())

  async function openGroupImport() {
    const w = mount(BulkView, { global })
    await flushPromises()
    await btnByText(w, 'Groups').trigger('click')
    const input = w.find('input[type="file"][aria-label="CSV File"]')
    const file = new File(['cn,members\neng,alice\n'], 'g.csv', { type: 'text/csv' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    return w
  }

  async function openFormatHelp(w: ReturnType<typeof mount>) {
    await btnByText(w, 'Input file format').trigger('click')
  }

  it('keeps the file format help in a panel that stays open until closed', async () => {
    const w = await openGroupImport()
    const link = btnByText(w, 'Input file format')
    expect(w.find('[data-testid="file-format-help"]').exists()).toBe(false)
    expect(link.attributes('aria-expanded')).toBe('false')

    await link.trigger('click')
    const panel = w.find('[data-testid="file-format-help"]')
    expect(panel.exists()).toBe(true)
    expect(link.attributes('aria-expanded')).toBe('true')
    expect(panel.text()).toContain('Wrap a members cell that contains DNs in double quotes')
    expect(panel.find('pre').text()).toContain('"uid=jsmith,ou=People,dc=example,dc=com|uid=adoe,ou=People,dc=example,dc=com"')
    expect(panel.findAll('li').length).toBeGreaterThan(5)

    // Hovering away or clicking elsewhere doesn't close it; × and Escape do.
    await w.find('[data-testid="file-format-help"] h3').trigger('mouseleave')
    expect(w.find('[data-testid="file-format-help"]').exists()).toBe(true)
    await w.find('[data-testid="file-format-help"] button[aria-label="Close"]').trigger('click')
    expect(w.find('[data-testid="file-format-help"]').exists()).toBe(false)

    await link.trigger('click')
    await w.find('[data-testid="file-format-help"]').trigger('keydown', { key: 'Escape' })
    expect(w.find('[data-testid="file-format-help"]').exists()).toBe(false)
  })

  it('mentions separation of duties in the hint only outside the community edition', async () => {
    edition.community = false
    const w = await openGroupImport()
    await openFormatHelp(w)
    expect(w.find('[data-testid="group-import-governance-note"]').text())
      .toContain('same approval and separation-of-duties rules')

    edition.community = true
    const w2 = await openGroupImport()
    await openFormatHelp(w2)
    const note = w2.find('[data-testid="group-import-governance-note"]').text()
    expect(note).toContain('same approval rules as editing them by hand')
    expect(note).not.toContain('separation-of-duties')
    edition.community = false
  })

  it('warns admins without group.manage_members that files with members will be refused', async () => {
    const w = await openGroupImport()
    expect(w.find('[data-testid="group-import-no-member-permission"]').exists()).toBe(true)

    grantedFeatures.add('group.manage_members')
    const w2 = await openGroupImport()
    expect(w2.find('[data-testid="group-import-no-member-permission"]').exists()).toBe(false)
  })

  it('previews what each row does and the planned outcome of every member change', async () => {
    vi.mocked(previewGroupCsv).mockResolvedValue({ data: {
      totalRows: 2,
      rows: [
        { rowNumber: 1, computedDn: 'cn=eng,ou=g,dc=x', attributes: {}, missingRequired: [],
          groupAction: 'UPDATE', message: null, memberChanges: [
            { member: 'alice', memberDn: 'uid=alice,dc=x', op: 'ADD', outcome: 'WILL_ADD' },
            { member: 'ghost', memberDn: null, op: 'ADD', outcome: 'MEMBER_NOT_FOUND', message: 'No user with uid=ghost' },
          ] },
        { rowNumber: 2, computedDn: 'cn=ops,ou=g,dc=x', attributes: {}, missingRequired: [],
          groupAction: null, message: 'Group does not exist', memberChanges: [] },
      ],
    } } as never)
    const w = await openGroupImport()

    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()

    const actions = w.findAll('[data-testid="group-preview-action"]').map(c => c.text())
    expect(actions).toEqual(['Update', '—'])
    const membersCell = w.findAll('[data-testid="group-preview-members"]')[0]
    const items = membersCell.findAll('li').map(li => li.findAll('span').map(s => s.text()))
    // The value from the file, then its outcome on its own line; no +/− marker.
    expect(items).toEqual([
      ['alice', 'Will be added'],
      ['ghost', 'Not found — No user with uid=ghost'],
    ])
    expect(membersCell.text()).not.toContain('+')
    expect(w.text()).toContain('Group does not exist')
    expect(w.find('[data-testid="group-preview-member-problems"]').text()).toContain('1 member change')
  })

  it('has no conflict-handling choice, and previews what changes on an existing group', async () => {
    vi.mocked(previewGroupCsv).mockResolvedValue({ data: {
      totalRows: 2,
      rows: [
        { rowNumber: 1, computedDn: 'cn=eng,ou=g,dc=x', attributes: { cn: 'eng', description: 'New' },
          missingRequired: [], groupAction: 'UPDATE', message: null, memberChanges: [],
          attributeChanges: [{ attribute: 'description', currentValue: 'Old', newValue: 'New' }] },
        { rowNumber: 2, computedDn: 'cn=ops,ou=g,dc=x', attributes: { cn: 'ops' },
          missingRequired: [], groupAction: 'UPDATE', message: null, memberChanges: [], attributeChanges: [] },
      ],
    } } as never)
    const w = await openGroupImport()
    expect(w.find('#bulk-group-conflict-handling').exists()).toBe(false)

    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()

    expect(vi.mocked(previewGroupCsv).mock.calls[0][2]).not.toHaveProperty('conflictHandling')
    const attrs = w.findAll('[data-testid="group-preview-attributes"]').map(c => c.text())
    expect(attrs[0]).toContain('description: Old → New')
    expect(attrs[1]).toContain('no attribute changes')
  })

  it('reports queued approvals and failed member changes after import', async () => {
    vi.mocked(previewGroupCsv).mockResolvedValue({ data: { totalRows: 1, rows: [] } } as never)
    vi.mocked(importGroupCsv).mockResolvedValue({ status: 200, data: {
      totalRows: 1, created: 0, updated: 0, skipped: 0, errors: 1,
      rows: [{ rowNumber: 1, dn: 'cn=eng,ou=g,dc=x', status: 'ERROR',
        message: '1 queued for approval; 1 blocked by separation of duties',
        memberChanges: [
          { member: 'alice', op: 'ADD', outcome: 'QUEUED_FOR_APPROVAL', approvalId: 'a1' },
          { member: 'bob', op: 'ADD', outcome: 'BLOCKED', message: 'SoD violation' },
        ] }],
    } } as never)
    const w = await openGroupImport()
    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()
    await w.find('input[aria-label="Type the profile name to confirm"]').setValue('Engineers')

    await btnByText(w, 'Perform Import').trigger('click')
    await flushPromises()

    expect(w.find('[data-testid="group-import-queued"]').text()).toContain('1 member addition is waiting for approval')
    const errors = w.find('[data-testid="group-import-errors"]').text()
    expect(errors).toContain('Row 1: 1 queued for approval; 1 blocked by separation of duties')
    expect(errors).toContain('bob: Blocked by separation of duties — SoD violation')
    expect(errors).not.toContain('alice:')
  })
})

describe('BulkView — feature keys', () => {
  // The view gates its tabs and the Template menu on feature keys from
  // /auth/me. A misspelt key (csv.template_manage instead of
  // csv_template.manage) silently hid the Template menu for everyone while
  // these specs, granting the same misspelling, still passed.
  it('only grants real backend feature keys in these specs', () => {
    for (const key of [...DEFAULT_FEATURES, 'bulk.delete', 'group.manage_members']) {
      expect(FEATURE_PERMISSION_LABELS, key).toHaveProperty([key])
    }
  })
})

describe('BulkView — user import group memberships', () => {
  beforeEach(() => vi.clearAllMocks())

  async function previewWith(rows: object[]) {
    vi.mocked(previewCsv).mockResolvedValueOnce({ data: { totalRows: rows.length, rows } } as never)
    const w = mount(BulkView, { global })
    await flushPromises()
    await w.find('#bulk-import-template').setValue('t1')
    await attachUserFile(w)
    await btnByText(w, 'Preview Import').trigger('click')
    await flushPromises()
    return w
  }

  it('previews each listed group with its outcome, and counts the ones that cannot be applied', async () => {
    const w = await previewWith([
      { rowNumber: 1, computedDn: 'uid=jsmith,ou=eng,dc=x', attributes: { cn: 'John' }, missingRequired: [],
        memberChanges: [
          { member: 'admins', memberDn: 'cn=admins,ou=g,dc=x', op: 'ADD', outcome: 'WILL_ADD' },
          { member: 'engineering', memberDn: null, op: 'ADD', outcome: 'AMBIGUOUS',
            message: "More than one group has cn=engineering; use the group's DN" },
        ] },
      { rowNumber: 2, computedDn: 'uid=adoe,ou=eng,dc=x', attributes: { cn: 'Ann' }, missingRequired: [], memberChanges: [] },
    ])

    const cells = w.findAll('[data-testid="user-preview-groups"]')
    expect(cells).toHaveLength(2)
    expect(cells[0].findAll('li').map(li => li.findAll('span').map(s => s.text()))).toEqual([
      ['admins', 'Will be added'],
      ['engineering', "Ambiguous — More than one group has cn=engineering; use the group's DN"],
    ])
    expect(w.find('[data-testid="user-preview-group-problems"]').text())
      .toContain('1 group change can\'t be applied')
    // A group problem isn't a missing-attribute warning.
    expect(w.text()).not.toContain('missing required attributes')
  })

  it('has no Groups column when no row lists groups', async () => {
    const w = await previewWith([
      { rowNumber: 1, computedDn: 'uid=jsmith,ou=eng,dc=x', attributes: { cn: 'John' }, missingRequired: [] },
    ])
    expect(w.find('[data-testid="user-preview-groups"]').exists()).toBe(false)
  })

  it('reports imported users whose groups could not all be added, and queued memberships', async () => {
    vi.mocked(importCsv).mockResolvedValueOnce({ status: 200, data: {
      totalRows: 2, created: 2, updated: 0, skipped: 0, errors: 0, rows: [
        { rowNumber: 1, dn: 'uid=jsmith,ou=eng,dc=x', status: 'CREATED', memberChanges: [
          { member: 'admins', memberDn: 'cn=admins,ou=g,dc=x', op: 'ADD', outcome: 'APPLIED' },
          { member: 'ghost', memberDn: null, op: 'ADD', outcome: 'MEMBER_NOT_FOUND', message: 'No group with cn=ghost' },
        ] },
        { rowNumber: 2, dn: 'uid=adoe,ou=eng,dc=x', status: 'CREATED', memberChanges: [
          { member: 'vpn', memberDn: 'cn=vpn,ou=g,dc=x', op: 'ADD', outcome: 'QUEUED_FOR_APPROVAL', approvalId: 'a1' },
        ] },
      ] } } as never)
    const w = await previewWith([])
    await w.find('input[aria-label="Type the profile name to confirm"]').setValue('Engineers')
    await btnByText(w, 'Perform Import').trigger('click')
    await flushPromises()

    const failures = w.find('[data-testid="user-import-group-failures"]').text()
    expect(failures).toContain('Row 1 (uid=jsmith,ou=eng,dc=x): imported, but not added to a group')
    expect(failures).toContain('ghost: Not found — No group with cn=ghost')
    expect(failures).not.toContain('admins')
    expect(w.find('[data-testid="user-import-groups-queued"]').text())
      .toContain('1 group membership is waiting for approval')
  })

  it('explains the groups column in the input file format panel', async () => {
    const w = mount(BulkView, { global })
    await flushPromises()
    await btnByText(w, 'Input file format').trigger('click')
    const panel = w.find('[data-testid="file-format-help"]').text()
    expect(panel).toContain('groups to add the user to')
    expect(panel).toContain('Groups are only added, never removed')
    expect(panel).toContain('Wrap any cell that contains DNs in double quotes')
    expect(w.find('[data-testid="user-import-governance-note"]').text()).toContain('separation-of-duties')
  })
})
