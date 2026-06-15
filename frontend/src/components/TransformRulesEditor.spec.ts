// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import TransformRulesEditor from './TransformRulesEditor.vue'
import type { SyncTransformRule } from '@/api/sync'

// Harness exercises the real v-model:rules contract.
function harness(initial: SyncTransformRule[] = []) {
  const Host = defineComponent({
    components: { TransformRulesEditor },
    setup() {
      const rules = ref<SyncTransformRule[]>(initial.map((r) => ({ ...r })))
      return { rules }
    },
    template: `<TransformRulesEditor v-model:rules="rules" />`,
  })
  return mount(Host)
}

describe('TransformRulesEditor', () => {
  it('shows the empty hint when there are no rules', () => {
    const wrapper = harness()
    expect(wrapper.text()).toContain('every source attribute is synced')
    expect(wrapper.find('#tr-src-0').exists()).toBe(false)
  })

  it('adds an empty mapping row', async () => {
    const wrapper = harness()
    await wrapper.find('button.btn-secondary').trigger('click')
    expect((wrapper.vm as unknown as { rules: SyncTransformRule[] }).rules).toEqual([
      { sourceAttr: '', targetAttr: '', valueTemplate: '' },
    ])
    expect(wrapper.find('#tr-src-0').exists()).toBe(true)
  })

  it('edits a row through v-model', async () => {
    const wrapper = harness([{ sourceAttr: 'uid', targetAttr: null, valueTemplate: null }])
    await wrapper.find('#tr-tgt-0').setValue('sAMAccountName')
    await wrapper.find('#tr-tpl-0').setValue('Mr ${value}')
    const rules = (wrapper.vm as unknown as { rules: SyncTransformRule[] }).rules
    expect(rules[0]).toEqual({ sourceAttr: 'uid', targetAttr: 'sAMAccountName', valueTemplate: 'Mr ${value}' })
  })

  it('removes a row', async () => {
    const wrapper = harness([
      { sourceAttr: 'uid', targetAttr: null, valueTemplate: null },
      { sourceAttr: 'cn', targetAttr: 'displayName', valueTemplate: null },
    ])
    await wrapper.find('[aria-label="Remove mapping 1"]').trigger('click')
    const rules = (wrapper.vm as unknown as { rules: SyncTransformRule[] }).rules
    expect(rules).toHaveLength(1)
    expect(rules[0].sourceAttr).toBe('cn')
  })
})
