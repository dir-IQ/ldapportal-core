// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'

const notif = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/stores/notifications', () => ({ useNotificationStore: () => notif }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ isSuperadmin: true }) }))
vi.mock('@/composables/useDirectoryPicker', () => ({
  useDirectoryPicker: () => ({
    dirId: ref('dir-2'), directories: ref([]), selectedDir: ref('dir-2'), showPicker: ref(true),
  }),
}))
const downloadBlob = vi.hoisted(() => vi.fn())
vi.mock('@/composables/useApi', () => ({
  useApi: () => ({ loading: ref(false), call: (fn: () => Promise<unknown>) => fn() }),
  downloadBlob,
}))
vi.mock('@/api/audit', () => ({
  getAuditLog: vi.fn(() => Promise.resolve({ data: { content: [] } })),
  getAuditActions: vi.fn(() => Promise.resolve({ data: [] })),
  exportAuditLog: vi.fn(() => Promise.resolve({
    data: new Blob(['"occurredAt"']),
    headers: { 'x-audit-export-matched': '3', 'x-audit-export-truncated': 'false' },
  })),
}))

import AuditLogView from './AuditLogView.vue'
import * as auditApi from '@/api/audit'

function button(label: string): HTMLButtonElement | undefined {
  return Array.from(document.querySelectorAll('button')).find((b) => b.textContent?.trim() === label)
}

async function chooseSource(value: string) {
  const select = document.getElementById('audit-source') as HTMLSelectElement
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flushPromises()
}

describe('AuditLogView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('offers a Directory sync source and filters the log by it', async () => {
    const wrapper = mount(AuditLogView, { attachTo: document.body })
    await flushPromises()
    const options = Array.from((document.getElementById('audit-source') as HTMLSelectElement).options)
    expect(options.map((o) => o.textContent?.trim())).toEqual(['All sources', 'Directory sync', 'IVIA'])

    await chooseSource('directory-sync')
    button('Filter')!.click()
    await flushPromises()

    expect(auditApi.getAuditLog).toHaveBeenLastCalledWith(expect.objectContaining({
      source: 'directory-sync', directoryId: 'dir-2', size: 200,
    }))
    wrapper.unmount()
  })

  it('exports every matching event from the server, with the same filters', async () => {
    const wrapper = mount(AuditLogView, { attachTo: document.body })
    await flushPromises()
    await chooseSource('directory-sync')

    button('Export CSV')!.click()
    await flushPromises()

    expect(auditApi.exportAuditLog).toHaveBeenCalledWith(expect.objectContaining({
      source: 'directory-sync', directoryId: 'dir-2',
    }))
    expect(auditApi.exportAuditLog).toHaveBeenCalledWith(expect.not.objectContaining({ page: expect.anything() }))
    expect(downloadBlob).toHaveBeenCalledWith(expect.any(Blob), 'audit-log.csv')
    expect(notif.success).toHaveBeenCalledWith('Exported 3 events')
    wrapper.unmount()
  })

  it('says when the export was cut short', async () => {
    vi.mocked(auditApi.exportAuditLog).mockResolvedValueOnce({
      data: new Blob(['']),
      headers: { 'x-audit-export-matched': '60000', 'x-audit-export-truncated': 'true' },
    } as never)
    const wrapper = mount(AuditLogView, { attachTo: document.body })
    await flushPromises()

    button('Export CSV')!.click()
    await flushPromises()

    expect(notif.error).toHaveBeenCalledWith(expect.stringContaining('Narrow the date range'))
    wrapper.unmount()
  })
})
