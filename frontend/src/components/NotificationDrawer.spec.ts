// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import type { AxiosResponse } from 'axios'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('@/api/notifications', () => ({
  getNotifications: vi.fn(),
  markRead: vi.fn(() => Promise.resolve({})),
  markAllRead: vi.fn(() => Promise.resolve({})),
}))

import NotificationDrawer from './NotificationDrawer.vue'
import { getNotifications, markRead, markAllRead } from '@/api/notifications'

const sample = [
  { id: 1, title: 'Unread one', body: 'b1', createdAt: new Date().toISOString(), read: false, link: '/approvals/1' },
  { id: 2, title: 'Read two', body: 'b2', createdAt: new Date().toISOString(), read: true },
]

function ok(content: unknown[], totalPages = 1) {
  return { data: { content, totalPages } } as unknown as AxiosResponse
}

function makeWrapper(props = {}) {
  return mount(NotificationDrawer, {
    props: { modelValue: true, ...props },
    global: { stubs: { teleport: true } },
  })
}

beforeEach(() => {
  push.mockClear()
  // Fresh clones each call — the component mutates `read` on the objects it
  // holds, so a shared fixture would leak read-state across tests.
  vi.mocked(getNotifications).mockReset()
  vi.mocked(getNotifications).mockImplementation(async () => ok(sample.map(s => ({ ...s }))))
  vi.mocked(markRead).mockReset()
  vi.mocked(markRead).mockResolvedValue({} as unknown as AxiosResponse)
  vi.mocked(markAllRead).mockReset()
  vi.mocked(markAllRead).mockResolvedValue({} as unknown as AxiosResponse)
})

describe('NotificationDrawer.vue', () => {
  it('fetches and renders notifications when opened', async () => {
    const w = makeWrapper()
    await flushPromises()
    expect(getNotifications).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(w.text()).toContain('Unread one')
    expect(w.text()).toContain('Read two')
  })

  it('does not fetch while closed; fetches on open', async () => {
    const w = makeWrapper({ modelValue: false })
    await flushPromises()
    expect(getNotifications).not.toHaveBeenCalled()
    await w.setProps({ modelValue: true })
    await flushPromises()
    expect(getNotifications).toHaveBeenCalledTimes(1)
  })

  it('Unread tab filters out read items', async () => {
    const w = makeWrapper()
    await flushPromises()
    const unreadTab = w.findAll('button').find(b => b.text() === 'Unread')!
    await unreadTab.trigger('click')
    expect(w.text()).toContain('Unread one')
    expect(w.text()).not.toContain('Read two')
  })

  it('clicking an unread item marks it read, emits changed, closes, and navigates', async () => {
    const w = makeWrapper()
    await flushPromises()
    const item = w.findAll('li button').find(b => b.text().includes('Unread one'))!
    await item.trigger('click')
    await flushPromises()
    expect(markRead).toHaveBeenCalledWith(1)
    expect(w.emitted('changed')).toBeTruthy()
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual([false])
    expect(push).toHaveBeenCalledWith('/approvals/1')
  })

  it('Mark all read calls the API and emits changed', async () => {
    const w = makeWrapper()
    await flushPromises()
    const btn = w.findAll('button').find(b => b.text() === 'Mark all read')!
    await btn.trigger('click')
    await flushPromises()
    expect(markAllRead).toHaveBeenCalled()
    expect(w.emitted('changed')).toBeTruthy()
    // Mark all read button hides once nothing is unread.
    expect(w.findAll('button').some(b => b.text() === 'Mark all read')).toBe(false)
  })

  it('appends the next page on Load more', async () => {
    vi.mocked(getNotifications)
      .mockResolvedValueOnce(ok([{ ...sample[0] }], 2))
      .mockResolvedValueOnce(ok([{ ...sample[1] }], 2))
    const w = makeWrapper()
    await flushPromises()
    expect(w.text()).toContain('Unread one')
    expect(w.text()).not.toContain('Read two')
    const more = w.findAll('button').find(b => b.text().includes('Load more'))!
    await more.trigger('click')
    await flushPromises()
    expect(getNotifications).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    expect(w.text()).toContain('Read two')
  })

  it('the close button closes the drawer', async () => {
    const w = makeWrapper()
    await flushPromises()
    await w.find('button[aria-label="Close notifications"]').trigger('click')
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual([false])
  })
})
