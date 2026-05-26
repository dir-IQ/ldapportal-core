// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { formatRelativeTime, formatAbsoluteTime, useRelativeTime } from './useRelativeTime'

describe('formatRelativeTime', () => {
  beforeEach(() => {
    // Pin "now" to a deterministic instant for every test.
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-24T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('returns empty string for null / undefined / empty input', () => {
    expect(formatRelativeTime(null)).toBe('')
    expect(formatRelativeTime(undefined)).toBe('')
    expect(formatRelativeTime('')).toBe('')
  })

  it('returns empty string for invalid date input', () => {
    expect(formatRelativeTime('not-a-date')).toBe('')
    expect(formatRelativeTime(new Date('not-a-date'))).toBe('')
  })

  it('formats a recent past time (~5 minutes ago)', () => {
    const fiveMinAgo = new Date('2026-04-24T11:55:00Z')
    const result = formatRelativeTime(fiveMinAgo)
    // Intl.RelativeTimeFormat output is locale-dependent; assert the
    // semantic shape rather than the exact string.
    expect(result).toMatch(/5\s*min/i)
    expect(result.toLowerCase()).toContain('ago')
  })

  it('formats a future time', () => {
    const inOneHour = new Date('2026-04-24T13:00:00Z')
    const result = formatRelativeTime(inOneHour)
    expect(result.toLowerCase()).toContain('in')
    expect(result).toMatch(/1\s*hour/i)
  })

  it('accepts a Date object directly', () => {
    const date = new Date('2026-04-24T11:30:00Z')
    expect(formatRelativeTime(date)).toMatch(/30\s*min/i)
  })

  it('accepts an ISO string', () => {
    expect(formatRelativeTime('2026-04-24T11:30:00Z')).toMatch(/30\s*min/i)
  })
})

describe('formatAbsoluteTime', () => {
  it('returns empty string for falsy input', () => {
    expect(formatAbsoluteTime(null)).toBe('')
    expect(formatAbsoluteTime(undefined)).toBe('')
    expect(formatAbsoluteTime('')).toBe('')
  })

  it('returns empty string for invalid date', () => {
    expect(formatAbsoluteTime('garbage')).toBe('')
  })

  it('returns a non-empty locale string for a valid date', () => {
    const result = formatAbsoluteTime('2026-04-24T12:00:00Z')
    // Output is locale-dependent; assert non-empty string only.
    expect(result).toBeTruthy()
    expect(typeof result).toBe('string')
  })
})

describe('useRelativeTime composable', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-24T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  // The composable uses onUnmounted, which only fires inside a Vue component
  // lifecycle. Mount a tiny test component to host it.
  function makeHost(getValue, intervalMs) {
    return defineComponent({
      setup() {
        const text = useRelativeTime(getValue, intervalMs)
        return () => h('span', text.value)
      },
    })
  }

  it('returns a reactive ref with the initial formatted value', () => {
    const wrapper = mount(makeHost(() => new Date('2026-04-24T11:55:00Z'), 60000))
    expect(wrapper.text()).toMatch(/5\s*min/i)
    wrapper.unmount()
  })

  it('updates the text after the interval fires', async () => {
    const target = new Date('2026-04-24T11:59:30Z')  // 30s ago at start
    const wrapper = mount(makeHost(() => target, 1000))
    expect(wrapper.text()).toMatch(/30\s*sec/i)

    // Advance system time + interval timer together.
    vi.setSystemTime(new Date('2026-04-24T12:01:00Z'))  // target now 1m30s ago
    await vi.advanceTimersByTimeAsync(1000)
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toMatch(/(2\s*min|90\s*sec|minute)/i)
    wrapper.unmount()
  })

  it('cleans up the interval when the host component unmounts', () => {
    const wrapper = mount(makeHost(() => new Date(), 1000))
    const spy = vi.spyOn(global, 'clearInterval')
    wrapper.unmount()
    expect(spy).toHaveBeenCalled()
    spy.mockRestore()
  })

  it('accepts a static (non-function) value', () => {
    const wrapper = mount(makeHost(new Date('2026-04-24T11:30:00Z'), 60000))
    expect(wrapper.text()).toMatch(/30\s*min/i)
    wrapper.unmount()
  })
})
