// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { dirId: 'd1' } }) }))
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasFeature: () => false, isSuperadmin: false }),
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

import { previewCsv, importCsv, listCsvTemplates, updateCsvTemplate } from '@/api/csvTemplates'
import { getObjectClassesBulk, listObjectClasses } from '@/api/schema'
// The view registers an unsaved-changes guard (and reads the sidebar
// picker) through real Pinia stores; give every test a fresh instance.
beforeEach(() => setActivePinia(createPinia()))

import BulkView from './BulkView.vue'

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

// Renders the template modal's slot so its form can be driven.
const modalGlobal = {
  stubs: { ...global.stubs, AppModal: { template: '<div><slot/></div>' } },
}

const editableTemplate = {
  id: 't1', name: 'Staff', targetKeyAttribute: 'uid', conflictHandling: 'SKIP',
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

/** Attribute names in the right-hand mapping stack, top to bottom (empty slots skipped). */
function mappedAttrs(w: ReturnType<typeof mount>) {
  return w.findAll('[data-side="out"][data-attr]').map(c => c.attributes('data-attr'))
}

describe('BulkView — template column mappings undo', () => {
  beforeEach(() => vi.clearAllMocks())

  it('restores removed mappings in reverse order at their original positions', async () => {
    const w = await openEditTemplate()
    expect(mappedAttrs(w)).toEqual(['mail', 'sn', 'cn'])
    expect(w.text()).not.toContain('Undo remove')

    const removeButtons = () => w.findAll('button[aria-label="Remove mapping"]')
    await removeButtons()[1].trigger('click') // sn
    await removeButtons()[0].trigger('click') // mail
    expect(mappedAttrs(w)).toEqual(['cn'])

    // Most recent removal comes back first.
    await btnByText(w, 'Undo remove (mail)').trigger('click')
    expect(mappedAttrs(w)).toEqual(['mail', 'cn'])
    await btnByText(w, 'Undo remove (sn)').trigger('click')
    expect(mappedAttrs(w)).toEqual(['mail', 'sn', 'cn'])
    expect(w.text()).not.toContain('Undo remove')
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

// ── Sample input file + reorderable mapping stacks ───────────────────────────

const stackInputs = (w: ReturnType<typeof mount>) =>
  w.findAll('.stack-chip[data-side="in"]').map(c => {
    const field = c.find('input')
    return field.exists() ? (field.element as HTMLInputElement).value : c.text().replace('⋮⋮', '').trim()
  })
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

describe('BulkView — template sample input file', () => {
  beforeEach(() => vi.clearAllMocks())

  it('reads the sample header into the input stack, keeping saved pairings', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    const w = await openEditTemplate()
    // Saved mappings line up with the schema's attributes (required first).
    expect(mappedAttrs(w)).toEqual(['cn', 'sn', 'mail', 'uid'])
    expect(stackInputs(w)).toEqual(['name', 'surname', 'email', ''])

    await chooseSample(w, 'email,name,surname,login,CostCenter\nx,y,z,q,1\n', 'hr.csv')
    expect(w.text()).toContain('hr.csv')
    expect(w.find('[data-testid="sample-columns"]').text()).toContain('5 columns')
    // Pairings whose column still exists stay; new columns go below as ignored
    // rather than silently pairing with the empty uid row.
    expect(stackInputs(w)).toEqual(['name', 'surname', 'email', 'no input column', 'login', 'CostCenter'])
    expect(rowStatuses(w)).toEqual(['mapped', 'mapped', 'mapped', 'unmapped', 'ignored', 'ignored'])
  })

  it('saves input columns without an attribute as ignored entries', async () => {
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
    expect(stackInputs(w)).toEqual(['email', 'surname', 'name', 'CostCenter'])
    expect(rowStatuses(w)).toEqual(['mapped', 'mapped', 'mapped', 'ignored'])
  })

  it('blocks save while a required attribute has no input column', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(
      { data: { required: ['cn', 'sn', 'uid'], optional: ['mail'] } } as never)
    const w = await openEditTemplate()
    expect(rowStatuses(w)).toContain('missing')
    expect(btnByText(w, 'Save').attributes('disabled')).toBeDefined()
    await w.find('input[aria-label="CSV column for row 3"]').setValue('login')
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

  it('auto-matches inputs to attributes by name and alias', async () => {
    vi.mocked(getObjectClassesBulk).mockResolvedValueOnce(schema as never)
    const w = await openEditTemplate({ ...editableTemplate, entries: [] })
    await chooseSample(w, 'Email,UserName,LastName,FullName\n')
    expect(stackInputs(w)).toEqual(['Email', 'UserName', 'LastName', 'FullName'])
    await btnByText(w, 'Auto-match by name').trigger('click')
    expect(mappedAttrs(w)).toEqual(['cn', 'sn', 'mail', 'uid'])
    expect(stackInputs(w)).toEqual(['FullName', 'LastName', 'Email', 'UserName'])
  })

  it('leaves an empty slot when an attribute is removed so rows below keep their pairing', async () => {
    const w = await openEditTemplate()
    await w.findAll('button[aria-label="Remove mapping"]')[0].trigger('click') // mail
    expect(stackInputs(w)).toEqual(['email', 'surname', 'name'])
    expect(rowStatuses(w)).toEqual(['ignored', 'mapped', 'mapped'])
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
