// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for AllClearPanel.vue.
 *
 * The panel fills the dashboard's summary slot when the action/suggestion queue
 * is empty. It must reflect actual directory health: a green "All clear" only
 * when nothing is unreachable, and an amber caution (never a health claim) when
 * one or more in-scope directories are unavailable — otherwise it contradicts
 * the "Unavailable" badges the Profiles/Directories panel shows alongside it.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import AllClearPanel from './AllClearPanel.vue'

const mountWith = (props: Record<string, unknown> = {}) =>
  mount(AllClearPanel, { props })

describe('AllClearPanel', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows the green "All clear" health affirmation when nothing is unavailable', () => {
    const w = mountWith({ unavailableCount: 0 })
    const text = w.text()
    expect(text).toContain('All clear')
    // Substring common to both the compliance and community subtitle variants.
    expect(text).toContain('Your directories are healthy')
    expect(w.html()).toContain('bg-green-50')
  })

  it('defaults to the healthy state when no count is provided', () => {
    const w = mountWith()
    expect(w.text()).toContain('All clear')
  })

  it('flips to an amber caution and drops the health claim when a directory is unavailable', () => {
    const w = mountWith({ unavailableCount: 2 })
    const text = w.text()
    expect(text).toContain('Directories need attention')
    expect(text).toContain('2 directories are unavailable')
    // The contradiction this fix removes: never claim health while one is down.
    expect(text).not.toContain('Your directories are healthy')
    expect(text).not.toContain('All clear')
    expect(w.html()).toContain('bg-amber-50')
  })

  it('uses singular phrasing for a single unavailable directory', () => {
    const w = mountWith({ unavailableCount: 1 })
    expect(w.text()).toContain('1 directory is unavailable')
  })
})
