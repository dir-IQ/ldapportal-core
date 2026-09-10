// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useUnsavedChangesStore } from '@/stores/unsavedChanges'
import { useUnsavedChangesGuard } from './useUnsavedChangesGuard'

const Page = defineComponent({
  props: { dirty: { type: Boolean, default: false } },
  setup(props) {
    const open = ref(props.dirty)
    useUnsavedChangesGuard('Groups', () => open.value)
    return () => h('div')
  },
})

describe('useUnsavedChangesGuard', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('registers on setup and unregisters when the component unmounts', () => {
    const store = useUnsavedChangesStore()
    const w = mount(Page, { props: { dirty: true } })
    expect(store.dirtyPage()).toBe('Groups')
    w.unmount()
    expect(store.dirtyPage()).toBeNull()
    expect(store.guards).toHaveLength(0)
  })

  it('reflects the live predicate rather than a snapshot', () => {
    const store = useUnsavedChangesStore()
    mount(Page, { props: { dirty: false } })
    expect(store.dirtyPage()).toBeNull()
  })
})
