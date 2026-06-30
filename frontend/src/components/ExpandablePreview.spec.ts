// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ExpandablePreview from './ExpandablePreview.vue'

function mountPreview() {
  return mount(ExpandablePreview, {
    props: { title: 'Import preview' },
    slots: { default: '<table><tbody><tr><td>row</td></tr></tbody></table>' },
    attachTo: document.body,
  })
}

describe('ExpandablePreview', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    document.body.style.overflow = ''
  })

  it('renders the slotted table and an Expand affordance', () => {
    const w = mountPreview()
    expect(w.find('table').exists()).toBe(true)
    const btn = document.querySelector('button[aria-label="Expand preview to full screen"]')
    expect(btn).not.toBeNull()
    w.unmount()
  })

  it('promotes to a full-screen overlay on Expand and locks body scroll', async () => {
    const w = mountPreview()
    await w.find('button[aria-label="Expand preview to full screen"]').trigger('click')

    // Teleported overlay backdrop is now in the body, and the title shows.
    expect(document.querySelector('.fixed.inset-0')).not.toBeNull()
    expect(document.body.textContent).toContain('Import preview')
    expect(document.body.style.overflow).toBe('hidden')

    // Collapse returns inline and releases the scroll lock.
    const collapse = document.querySelector('button[aria-label="Collapse preview"]') as HTMLButtonElement
    expect(collapse).not.toBeNull()
    collapse.click()
    await w.vm.$nextTick()
    expect(document.querySelector('.fixed.inset-0')).toBeNull()
    expect(document.body.style.overflow).toBe('')
    w.unmount()
  })

  it('releases the body scroll lock if unmounted while expanded', async () => {
    const w = mountPreview()
    await w.find('button[aria-label="Expand preview to full screen"]').trigger('click')
    expect(document.body.style.overflow).toBe('hidden')
    w.unmount()
    expect(document.body.style.overflow).toBe('')
  })
})
