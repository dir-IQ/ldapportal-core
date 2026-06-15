// SPDX-License-Identifier: Apache-2.0
/**
 * ConfirmDialog must NOT close on backdrop/outside click — a confirmation
 * prompt has to be dismissed deliberately (Cancel or Escape), so a stray
 * click on the overlay can't silently abort the decision. Escape-to-close
 * lives in useDialogA11y and is exercised there; here we pin the backdrop
 * behaviour and the explicit Cancel path.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmDialog from './ConfirmDialog.vue'

function mountOpen() {
  return mount(ConfirmDialog, {
    props: { modelValue: true, title: 'Delete?', message: 'Are you sure?' },
    global: { stubs: { teleport: true } },
  })
}

describe('ConfirmDialog', () => {
  it('does NOT close when the backdrop is clicked', async () => {
    const wrapper = mountOpen()
    // Outermost full-screen overlay is the backdrop.
    await wrapper.find('div.fixed.inset-0').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('closes (emits false) only via the explicit Cancel button', async () => {
    const wrapper = mountOpen()
    const cancel = wrapper.findAll('button').find((b) => b.text() === 'Cancel')
    await cancel!.trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
  })
})
