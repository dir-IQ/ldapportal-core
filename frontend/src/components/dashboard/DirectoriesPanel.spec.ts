// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for DirectoriesPanel.vue.
 *
 * Focus: the status dot must reflect actual LDAP reachability, not just the
 * `enabled` config flag. An enabled directory whose host is unreachable
 * (backend `reachable: false`) previously read green — these tests pin the
 * regression: green only when enabled AND reachable, red when enabled but
 * unreachable, grey outline when disabled.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DirectoriesPanel from './DirectoriesPanel.vue'

type Dir = {
  id: string
  name: string
  enabled: boolean
  reachable?: boolean | null
}

function makeWrapper(directories: Dir[]) {
  return mount(DirectoriesPanel, { props: { directories } })
}

/** The status dot is the first <span> inside each card header. */
function dotClasses(wrapper: ReturnType<typeof makeWrapper>, index = 0): string {
  return wrapper.findAll('.rounded-full')[index].attributes('class') ?? ''
}

describe('DirectoriesPanel status dot', () => {
  it('is green when enabled and reachable', () => {
    const wrapper = makeWrapper([{ id: '1', name: 'Acmecorp', enabled: true, reachable: true }])
    expect(dotClasses(wrapper)).toContain('bg-green-500')
    expect(dotClasses(wrapper)).not.toContain('bg-red-500')
  })

  it('is red when enabled but unreachable', () => {
    const wrapper = makeWrapper([{ id: '1', name: 'Acmecorp', enabled: true, reachable: false }])
    expect(dotClasses(wrapper)).toContain('bg-red-500')
    expect(dotClasses(wrapper)).not.toContain('bg-green-500')
    // Screen readers should hear the unreachable state.
    expect(wrapper.find('.sr-only').text()).toContain('unreachable')
  })

  it('is a grey outline when disabled (not probed)', () => {
    const wrapper = makeWrapper([{ id: '1', name: 'Acmecorp', enabled: false, reachable: null }])
    expect(dotClasses(wrapper)).toContain('border-gray-400')
    expect(dotClasses(wrapper)).not.toContain('bg-green-500')
    expect(dotClasses(wrapper)).not.toContain('bg-red-500')
  })

  it('falls back to green when reachable is absent (older payloads)', () => {
    const wrapper = makeWrapper([{ id: '1', name: 'Acmecorp', enabled: true }])
    expect(dotClasses(wrapper)).toContain('bg-green-500')
  })
})
