// SPDX-License-Identifier: Apache-2.0
/**
 * Directory Search: the size-limited page and the "Load all" path. The
 * search endpoint returns a page plus whether it was cut short and how many
 * entries matched in all; the view shows that below the results and offers
 * to re-run the search unbounded.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import type { AxiosResponse } from 'axios'

vi.mock('vue-router', () => ({ RouterLink: { template: '<a><slot /></a>' } }))
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isDirectorySearchInlineEditEnabled: false, hasSuperadminPermission: () => true }),
}))
vi.mock('@/stores/preferences', () => ({
  usePreferencesStore: () => ({ read: () => ({}), write: vi.fn() }),
}))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => vi.fn().mockResolvedValue(true) }))
vi.mock('@/api/directories', () => ({ listDirectories: vi.fn() }))
vi.mock('@/api/browse', () => ({ searchEntries: vi.fn() }))
vi.mock('@/api/schema', () => ({ listAttributeTypes: vi.fn().mockResolvedValue({ data: [] }) }))

import DirectorySearchView from './DirectorySearchView.vue'
import { listDirectories } from '@/api/directories'
import { searchEntries } from '@/api/browse'

function ok<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: {} } as unknown as AxiosResponse<T>
}

const stubs = {
  DnPicker: { props: ['modelValue'], template: '<input />' },
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
  ResultsTable: { props: ['rows'], template: '<div data-results><slot name="toolbar" /></div>' },
  EditableResultsTable: { props: ['rows'], template: '<div />' },
  LdapFilterBuilder: { template: '<div />' },
}

function entries(n: number) {
  return Array.from({ length: n }, (_, i) => ({ dn: `cn=p${i},dc=example,dc=com`, attributes: { cn: [`p${i}`] } }))
}

async function mountAndSearch() {
  const wrapper = mount(DirectorySearchView, {
    global: { stubs, directives: { 'dialog-a11y': {} } },
  })
  await flushPromises()
  await (wrapper.vm as unknown as { doSearch: () => Promise<void> }).doSearch()
  await flushPromises()
  return wrapper
}

describe('DirectorySearchView size limit and Load all', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listDirectories).mockResolvedValue(ok([{ id: 'd1', displayName: 'Example', directoryType: 'OPENLDAP' }]))
  })

  it('searches with a 1000-entry page by default', async () => {
    vi.mocked(searchEntries).mockResolvedValue(ok({ entries: entries(3), truncated: false, total: 3, totalIsLowerBound: false }))
    const wrapper = await mountAndSearch()

    expect(searchEntries).toHaveBeenCalledWith('d1', expect.objectContaining({ limit: 1000 }))
    expect(wrapper.find('[data-testid="search-truncated"]').exists()).toBe(false)
  })

  it('tells the operator how many entries matched and offers Load all when the page was cut short', async () => {
    vi.mocked(searchEntries).mockResolvedValue(ok({ entries: entries(1000), truncated: true, total: 2345, totalIsLowerBound: false }))
    const wrapper = await mountAndSearch()

    const notice = wrapper.find('[data-testid="search-truncated"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text().replace(/\s+/g, ' ')).toContain('First 1,000 of 2,345 entries returned.')
    expect(notice.text()).toContain('Load All')
    expect(notice.text()).toContain('narrow the search filter')
  })

  it('Load all re-runs the same search unbounded and drops the action once everything is loaded', async () => {
    vi.mocked(searchEntries)
      .mockResolvedValueOnce(ok({ entries: entries(1000), truncated: true, total: 1500, totalIsLowerBound: false }))
      .mockResolvedValueOnce(ok({ entries: entries(1500), truncated: false, total: 1500, totalIsLowerBound: false }))
    const wrapper = await mountAndSearch()

    await wrapper.find('[data-testid="search-load-all"]').trigger('click')
    await flushPromises()

    expect(searchEntries).toHaveBeenLastCalledWith('d1', expect.objectContaining({ limit: 0 }))
    expect(wrapper.find('[data-testid="search-truncated"]').exists()).toBe(false)
  })

  it('reports a lower bound and no Load all when even the unbounded search hit the server ceiling', async () => {
    vi.mocked(searchEntries)
      .mockResolvedValueOnce(ok({ entries: entries(1000), truncated: true, total: 50000, totalIsLowerBound: true }))
      .mockResolvedValueOnce(ok({ entries: entries(50000), truncated: true, total: 50000, totalIsLowerBound: true }))
    const wrapper = await mountAndSearch()
    await wrapper.find('[data-testid="search-load-all"]').trigger('click')
    await flushPromises()

    const notice = wrapper.find('[data-testid="search-truncated"]')
    expect(notice.text().replace(/\s+/g, ' ')).toContain('First 50,000 of more than 50,000 entries returned.')
    expect(wrapper.find('[data-testid="search-load-all"]').exists()).toBe(false)
    expect(notice.text()).toContain('Narrow the search filter')
  })
})
