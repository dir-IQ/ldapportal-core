// SPDX-License-Identifier: Apache-2.0
/**
 * Spec for EditableResultsTable. Tasks 4–6 layer on top of one
 * another:
 *   - Task 4: read-only render
 *   - Task 5: editable cells + dirty tracking
 *   - Task 6: save-on-row-blur with optimistic UI (this revision)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('@/api/ldapEntry', () => ({
  updateEntry: vi.fn(),
  getEntry: vi.fn(),
}))
import { updateEntry } from '@/api/ldapEntry'
const mockUpdateEntry = vi.mocked(updateEntry)

import EditableResultsTable from './EditableResultsTable.vue'
import type { ColumnDef } from './ResultsTable.vue'
import type {
  AttributeTypeInfo,
  DirectoryEntry,
} from '@/composables/useEntryClassification'

const columns: ColumnDef[] = [
  { key: 'dn', label: 'DN', alwaysVisible: true },
  { key: 'cn', label: 'cn' },
  { key: 'mail', label: 'mail' },
  { key: 'givenName', label: 'givenName' },
  { key: 'objectClass', label: 'objectClass' },
  { key: 'description', label: 'description' },
  { key: 'modifyTimestamp', label: 'modifyTimestamp' },
]

function buildRow(entry: DirectoryEntry, extras: Record<string, string> = {}) {
  return { dn: entry.dn, __entry: entry, ...extras }
}

const aliceEntry: DirectoryEntry = {
  dn: 'cn=alice,ou=people,dc=example,dc=com',
  attributes: {
    objectClass: ['inetOrgPerson'],
    cn: ['alice'],
    mail: ['alice@x.example'],
    givenName: ['Alice'],
  },
}
const aliceRow = buildRow(aliceEntry, {
  cn: 'alice',
  mail: 'alice@x.example',
  givenName: 'Alice',
  objectClass: 'inetOrgPerson',
  description: '',
  modifyTimestamp: '20260504T143022Z',
})

const ouEntry: DirectoryEntry = {
  dn: 'ou=people,dc=example,dc=com',
  attributes: { objectClass: ['organizationalUnit'], ou: ['people'] },
}
const ouRow = buildRow(ouEntry, { cn: '', mail: '', objectClass: 'organizationalUnit' })

function buildSchema(): Map<string, AttributeTypeInfo> {
  const m = new Map<string, AttributeTypeInfo>()
  const add = (name: string, singleValued = true) => {
    m.set(name.toLowerCase(), {
      name,
      oid: '1.2.3',
      syntaxOid: '1.3.6.1.4.1.1466.115.121.1.15',
      singleValued,
    } as AttributeTypeInfo)
  }
  add('cn')
  add('mail')
  add('givenName')
  add('description', false)  // multi-valued
  add('modifyTimestamp')
  return m
}

describe('EditableResultsTable — read-only render (Task 4)', () => {
  it('renders one cell per column per row', () => {
    const w = mount(EditableResultsTable, {
      props: {
        tableKey: 'spec',
        columns,
        rows: [aliceRow],
        edit: false,
      },
    })
    expect(w.findAll('thead th')).toHaveLength(columns.length)
    expect(w.findAll('tbody tr')).toHaveLength(1)
    expect(w.findAll('tbody td')).toHaveLength(columns.length)
  })

  it('does not render any input elements when edit is false', () => {
    const w = mount(EditableResultsTable, {
      props: { tableKey: 'spec', columns, rows: [aliceRow], edit: false },
    })
    expect(w.findAll('input').length).toBe(0)
  })

  it('renders the empty-state when rows is empty', () => {
    const w = mount(EditableResultsTable, {
      props: { tableKey: 'spec', columns, rows: [], emptyText: 'Nothing here.' },
    })
    expect(w.text()).toContain('Nothing here.')
    expect(w.find('table').exists()).toBe(false)
  })

  it('emits row-click when a row is clicked and rowClickable is true', async () => {
    const w = mount(EditableResultsTable, {
      props: { tableKey: 'spec', columns, rows: [aliceRow], rowClickable: true },
    })
    await w.find('tbody tr').trigger('click')
    expect(w.emitted('row-click')).toBeTruthy()
    expect(w.emitted('row-click')![0][0]).toMatchObject({ dn: aliceEntry.dn })
  })

  it('does NOT emit row-click while in edit mode (cell interactions own the click)', async () => {
    const w = mount(EditableResultsTable, {
      props: { tableKey: 'spec', columns, rows: [aliceRow], rowClickable: true, edit: true },
    })
    await w.find('tbody tr').trigger('click')
    expect(w.emitted('row-click')).toBeFalsy()
  })

  it('renders the toolbar slot', () => {
    const w = mount(EditableResultsTable, {
      props: { tableKey: 'spec', columns, rows: [aliceRow] },
      slots: { toolbar: '<button class="my-btn">Tool</button>' },
    })
    expect(w.find('.my-btn').exists()).toBe(true)
    expect(w.find('.my-btn').text()).toBe('Tool')
  })

  it('renders a per-cell slot when provided', () => {
    const w = mount(EditableResultsTable, {
      props: { tableKey: 'spec', columns, rows: [aliceRow] },
      slots: { 'cell-dn': '<span class="dn-slot">SLOTTED</span>' },
    })
    expect(w.find('.dn-slot').exists()).toBe(true)
    expect(w.find('.dn-slot').text()).toBe('SLOTTED')
  })
})

describe('EditableResultsTable — editable cells (Task 5)', () => {
  const editableProps = {
    tableKey: 'spec',
    columns,
    rows: [aliceRow],
    edit: true,
    directoryId: 'dir-123',
    schemaMap: buildSchema(),
  }

  it('renders <input> for editable cells', () => {
    const w = mount(EditableResultsTable, { props: editableProps })
    // mail and givenName are non-RDN, single-valued, schema-known on a user → editable.
    expect(w.find('input[data-edit-cell="mail"]').exists()).toBe(true)
    expect(w.find('input[data-edit-cell="givenName"]').exists()).toBe(true)
  })

  it('keeps dn / objectClass / RDN cn / operational / multi-valued cells read-only', () => {
    const w = mount(EditableResultsTable, { props: editableProps })
    expect(w.find('input[data-edit-cell="dn"]').exists()).toBe(false)
    expect(w.find('input[data-edit-cell="objectClass"]').exists()).toBe(false)
    expect(w.find('input[data-edit-cell="cn"]').exists()).toBe(false) // RDN of cn=alice,…
    expect(w.find('input[data-edit-cell="modifyTimestamp"]').exists()).toBe(false)
    expect(w.find('input[data-edit-cell="description"]').exists()).toBe(false)
  })

  it('locks every cell when edit=false', () => {
    const w = mount(EditableResultsTable, { props: { ...editableProps, edit: false } })
    expect(w.findAll('input').length).toBe(0)
  })

  it('locks every cell on a row whose objectClass is unknown', () => {
    const w = mount(EditableResultsTable, {
      props: { ...editableProps, rows: [ouRow] },
    })
    // organizationalUnit isn't a user / group; predicate returns false
    // for every column.
    expect(w.findAll('input').length).toBe(0)
  })

  it('locks every cell when the schema map is empty', () => {
    const w = mount(EditableResultsTable, {
      props: { ...editableProps, schemaMap: new Map() },
    })
    // Conservative lock without schema metadata.
    expect(w.findAll('input').length).toBe(0)
  })

  it('typing into a cell populates the dirty map for that (dn, attr)', async () => {
    const w = mount(EditableResultsTable, { props: editableProps })
    const mailInput = w.find('input[data-edit-cell="mail"]')
    await mailInput.setValue('alice@new.example')

    const vm = w.vm as unknown as {
      dirtyMap: Map<string, Map<string, string>>
    }
    expect(vm.dirtyMap.get(aliceEntry.dn)?.get('mail')).toBe('alice@new.example')
    expect(vm.dirtyMap.get(aliceEntry.dn)?.size).toBe(1)
  })

  it('typing then re-rendering shows the dirty value rather than the raw row value', async () => {
    const w = mount(EditableResultsTable, { props: editableProps })
    const mailInput = w.find('input[data-edit-cell="mail"]')
    await mailInput.setValue('alice@new.example')
    // Force a re-render (simulating a parent prop change).
    await w.setProps({ ...editableProps })
    expect((w.find('input[data-edit-cell="mail"]').element as HTMLInputElement).value)
      .toBe('alice@new.example')
  })

  it('locked cells in edit mode carry an explanatory tooltip', () => {
    const w = mount(EditableResultsTable, { props: editableProps })
    // The first read-only span in edit mode is the dn cell — it's
    // rendered via the default slot fallback because no cell-dn
    // slot was provided in this test mount.
    const lockedDn = w.find('tbody tr td:first-child span')
    // Spec: tooltip explains why the lock applies. Don't assert the
    // exact wording (that's UX copy, may evolve).
    expect(lockedDn.attributes('title')).toBeTruthy()
    expect(lockedDn.attributes('title')!.length).toBeGreaterThan(0)
  })
})

describe('EditableResultsTable — save-on-row-blur (Task 6)', () => {
  const baseProps = {
    tableKey: 'spec',
    columns,
    rows: [aliceRow],
    edit: true,
    directoryId: 'dir-123',
    schemaMap: buildSchema(),
  }

  beforeEach(() => {
    mockUpdateEntry.mockReset()
    mockUpdateEntry.mockResolvedValue({
      data: { dn: aliceEntry.dn, attributes: aliceEntry.attributes },
      status: 200,
      statusText: 'OK',
      headers: {},
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config: {} as any,
    })
  })

  it('does not call updateEntry on focusout when nothing is dirty', async () => {
    const w = mount(EditableResultsTable, { props: baseProps })
    const tr = w.find('tbody tr')
    // Move focus from inside the row to outside — relatedTarget is
    // null in jsdom by default for this synthetic, which the handler
    // treats as "left the row".
    await tr.trigger('focusout')
    await flushPromises()
    expect(mockUpdateEntry).not.toHaveBeenCalled()
  })

  it('does not call updateEntry when focus moves between cells of the same row', async () => {
    const w = mount(EditableResultsTable, { props: baseProps })
    const mailInput = w.find('input[data-edit-cell="mail"]')
    await mailInput.setValue('alice@new.example')
    const givenInput = w.find('input[data-edit-cell="givenName"]')
    // relatedTarget is the next input within the same <tr>.
    await mailInput.trigger('focusout', { relatedTarget: givenInput.element })
    await flushPromises()
    expect(mockUpdateEntry).not.toHaveBeenCalled()
  })

  it('saves when focus leaves the row (relatedTarget outside the tr)', async () => {
    const w = mount(EditableResultsTable, { props: baseProps })
    const mailInput = w.find('input[data-edit-cell="mail"]')
    await mailInput.setValue('alice@new.example')

    // relatedTarget = a brand-new element NOT in the row → save fires.
    const outside = document.createElement('button')
    await mailInput.trigger('focusout', { relatedTarget: outside })
    await flushPromises()

    expect(mockUpdateEntry).toHaveBeenCalledTimes(1)
    expect(mockUpdateEntry).toHaveBeenCalledWith(
      'dir-123',
      'user',
      aliceEntry.dn,
      [{ operation: 'REPLACE', attribute: 'mail', values: ['alice@new.example'] }],
    )
  })

  it('blanking an optional attribute fires DELETE not REPLACE [""]', async () => {
    const w = mount(EditableResultsTable, { props: baseProps })
    const givenInput = w.find('input[data-edit-cell="givenName"]')
    await givenInput.setValue('')

    const outside = document.createElement('button')
    await givenInput.trigger('focusout', { relatedTarget: outside })
    await flushPromises()

    expect(mockUpdateEntry).toHaveBeenCalledWith(
      'dir-123',
      'user',
      aliceEntry.dn,
      [{ operation: 'DELETE', attribute: 'givenName', values: [] }],
    )
  })

  it('successful save clears the dirty map and emits row-saved', async () => {
    const w = mount(EditableResultsTable, { props: baseProps })
    const mailInput = w.find('input[data-edit-cell="mail"]')
    await mailInput.setValue('alice@new.example')
    await mailInput.trigger('focusout', { relatedTarget: document.createElement('button') })
    await flushPromises()

    const vm = w.vm as unknown as {
      dirtyMap: Map<string, Map<string, string>>
      saveState: Map<string, string>
    }
    expect(vm.dirtyMap.has(aliceEntry.dn)).toBe(false)
    expect(vm.saveState.get(aliceEntry.dn)).toBe('saved')
    expect(w.emitted('row-saved')).toBeTruthy()
    expect(w.emitted('row-saved')![0][0]).toBe(aliceEntry.dn)
  })

  it('attribute-scoped server error renders under the cell and preserves dirty state', async () => {
    mockUpdateEntry.mockRejectedValueOnce({
      response: {
        status: 400,
        data: {
          code: 'LDAP_CONSTRAINT_VIOLATION',
          attribute: 'mail',
          message: 'mail must be a valid RFC 822 address',
        },
      },
    })

    const w = mount(EditableResultsTable, { props: baseProps })
    const mailInput = w.find('input[data-edit-cell="mail"]')
    await mailInput.setValue('not-an-email')
    await mailInput.trigger('focusout', { relatedTarget: document.createElement('button') })
    await flushPromises()

    const vm = w.vm as unknown as {
      dirtyMap: Map<string, Map<string, string>>
      saveState: Map<string, string>
      cellErrors: Map<string, Map<string, string>>
    }
    expect(vm.saveState.get(aliceEntry.dn)).toBe('error')
    expect(vm.cellErrors.get(aliceEntry.dn)?.get('mail')).toContain('valid RFC 822')
    // Dirty map preserved so user can edit + retry.
    expect(vm.dirtyMap.get(aliceEntry.dn)?.get('mail')).toBe('not-an-email')

    expect(w.find('[data-cell-error="mail"]').exists()).toBe(true)
    expect(w.find('[data-cell-error="mail"]').text()).toContain('valid RFC 822')
  })

  it('non-attribute server error renders the row-level banner', async () => {
    mockUpdateEntry.mockRejectedValueOnce({
      response: {
        status: 500,
        data: { message: 'Directory unreachable' },
      },
    })

    const w = mount(EditableResultsTable, { props: baseProps })
    const mailInput = w.find('input[data-edit-cell="mail"]')
    await mailInput.setValue('alice@x.example')
    await mailInput.trigger('focusout', { relatedTarget: document.createElement('button') })
    await flushPromises()

    expect(w.find(`[data-row-error-for="${aliceEntry.dn}"]`).exists()).toBe(true)
    expect(w.find(`[data-row-error-for="${aliceEntry.dn}"]`).text()).toContain('Directory unreachable')
  })

  it('retry button on the indicator re-issues the save', async () => {
    mockUpdateEntry.mockRejectedValueOnce({
      response: { status: 500, data: { message: 'flaky' } },
    })

    const w = mount(EditableResultsTable, { props: baseProps })
    const mailInput = w.find('input[data-edit-cell="mail"]')
    await mailInput.setValue('alice@x.example')
    await mailInput.trigger('focusout', { relatedTarget: document.createElement('button') })
    await flushPromises()

    expect(mockUpdateEntry).toHaveBeenCalledTimes(1)
    // Indicator shows ⚠ button — clicking retries.
    const retry = w.find('[data-row-state="error"] button')
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')
    await flushPromises()
    expect(mockUpdateEntry).toHaveBeenCalledTimes(2)
  })

  it('locks save when directoryId is empty (defensive)', async () => {
    const w = mount(EditableResultsTable, {
      props: { ...baseProps, directoryId: '' },
    })
    // No editable inputs render because isAttributeEditable doesn't
    // gate on directoryId — but saveRow does guard. Bypass the
    // template by calling the exposed method directly with a forged
    // dirty entry, simulating a race where directoryId blanks
    // mid-flight.
    const vm = w.vm as unknown as {
      dirtyMap: Map<string, Map<string, string>>
      saveRow: (row: typeof aliceRow) => Promise<void>
      rowError: Map<string, string>
      saveState: Map<string, string>
    }
    vm.dirtyMap.set(aliceEntry.dn, new Map([['mail', 'a@x']]))
    await vm.saveRow(aliceRow)
    await flushPromises()
    expect(mockUpdateEntry).not.toHaveBeenCalled()
    expect(vm.saveState.get(aliceEntry.dn)).toBe('error')
    expect(vm.rowError.get(aliceEntry.dn)).toContain('directory')
  })
})
