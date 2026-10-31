// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

const notif = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/stores/notifications', () => ({ useNotificationStore: () => notif }))
const route = vi.hoisted(() => ({ query: {} as Record<string, string> }))
const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', async () => {
  const { reactive: r } = await import('vue')
  const reactiveRoute = r(route)
  return { useRoute: () => reactiveRoute, useRouter: () => ({ push }) }
})
const api = vi.hoisted(() => ({
  getDirectoryDiff: vi.fn(),
  cancelDirectoryDiff: vi.fn(),
}))
vi.mock('@/api/directoryDiff', () => api)

import { useRoute } from 'vue-router'
import DirectoryDiffView from './DirectoryDiffView.vue'
import { diffRun } from '@/components/directoryDiff/fixtures'

const stubs = {
  PageContainer: { template: '<div><slot /></div>' },
  RouterLink: { template: '<a><slot /></a>' },
  DirectoryDiffSetup: { name: 'DirectoryDiffSetup', props: ['prefill'], template: '<div data-testid="setup" />' },
  DirectoryDiffResults: { name: 'DirectoryDiffResults', props: ['run'], template: '<div data-testid="results" />' },
}

let wrapper: VueWrapper | null = null

beforeEach(() => {
  vi.clearAllMocks()
  route.query = {}
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.useRealTimers()
})

async function mountView(query: Record<string, string> = {}) {
  route.query = query
  wrapper = mount(DirectoryDiffView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('DirectoryDiffView', () => {
  it('shows the setup form without a run', async () => {
    const w = await mountView()
    expect(w.find('[data-testid="setup"]').exists()).toBe(true)
    expect(api.getDirectoryDiff).not.toHaveBeenCalled()
  })

  it('opens a started run', async () => {
    const w = await mountView()
    w.findComponent({ name: 'DirectoryDiffSetup' }).vm.$emit('started', diffRun({ id: 'run-9', status: 'RUNNING' }))
    expect(push).toHaveBeenCalledWith({ path: '/superadmin/directory-diff', query: { run: 'run-9' } })
  })

  it('follows a running comparison until it completes', async () => {
    vi.useFakeTimers()
    api.getDirectoryDiff
      .mockResolvedValueOnce({ data: diffRun({ status: 'RUNNING', phase: 'COMPARING', processed: 3, total: 10 }) })
      .mockResolvedValueOnce({ data: diffRun() })
    const w = await mountView({ run: 'run-1' })
    expect(w.text()).toContain('Comparing entries: 3 of 10')
    expect(w.text()).toContain('30%')
    vi.advanceTimersByTime(2000)
    await flushPromises()
    expect(api.getDirectoryDiff).toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="results"]').exists()).toBe(true)
    vi.advanceTimersByTime(10000)
    await flushPromises()
    expect(api.getDirectoryDiff).toHaveBeenCalledTimes(2)
  })

  it('keeps showing progress through a passing poll error', async () => {
    vi.useFakeTimers()
    api.getDirectoryDiff
      .mockResolvedValueOnce({ data: diffRun({ status: 'RUNNING', phase: 'READING_A', processed: 40, total: 0 }) })
      .mockRejectedValueOnce(new Error('Request failed'))
      .mockResolvedValueOnce({ data: diffRun({ status: 'RUNNING', phase: 'READING_B', processed: 5, total: 0 }) })
    const w = await mountView({ run: 'run-1' })
    expect(w.text()).toContain('Reading side A: 40 entries read')
    vi.advanceTimersByTime(2000)
    await flushPromises()
    expect(w.text()).toContain('Reading side A')
    vi.advanceTimersByTime(2000)
    await flushPromises()
    expect(w.text()).toContain('Reading side B: 5 entries read')
  })

  it('cancels a running comparison', async () => {
    api.getDirectoryDiff.mockResolvedValue({ data: diffRun({ status: 'RUNNING', phase: 'READING_A' }) })
    api.cancelDirectoryDiff.mockResolvedValue({ data: diffRun({ status: 'CANCELLED' }) })
    const w = await mountView({ run: 'run-1' })
    await w.findAll('button').find(b => b.text() === 'Cancel comparison')!.trigger('click')
    await flushPromises()
    expect(api.cancelDirectoryDiff).toHaveBeenCalledWith('run-1')
    expect(w.find('[data-testid="run-ended"]').text()).toContain('cancelled')
  })

  it('explains a failed run and offers to run it again with the same settings', async () => {
    const failed = diffRun({ status: 'FAILED', error: 'Side A has more than 500000 entries' })
    api.getDirectoryDiff.mockResolvedValue({ data: failed })
    const w = await mountView({ run: 'run-1' })
    expect(w.find('[data-testid="run-ended"]').text()).toContain('Side A has more than 500000 entries')
    await w.findAll('button').find(b => b.text() === 'Run again')!.trigger('click')
    expect(push).toHaveBeenCalledWith({ path: '/superadmin/directory-diff' })
    useRoute().query = {}
    await flushPromises()
    expect(w.findComponent({ name: 'DirectoryDiffSetup' }).props('prefill')).toMatchObject({ id: 'run-1' })
  })

  it('says when a run can’t be loaded', async () => {
    api.getDirectoryDiff.mockRejectedValue({ response: { data: { detail: 'Directory comparison not found: x' } } })
    const w = await mountView({ run: 'x' })
    expect(w.text()).toContain('Directory comparison not found: x')
  })
})
