// SPDX-License-Identifier: Apache-2.0
/**
 * BulkConfirmModal: a themed commit confirmation. The header band carries the
 * profile theme colour (via AppModal headerColor); reversible actions confirm
 * with one click, irreversible ones (require-typed) only arm once the operator
 * types the profile name. Teleports to <body>, so we query document.body.
 */
import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import BulkConfirmModal from './BulkConfirmModal.vue'

function mountModal(props = {}) {
  setActivePinia(createPinia())
  return mount(BulkConfirmModal, {
    props: {
      modelValue: true, profileName: 'PROD', themeColor: '#b91c1c',
      title: 'Confirm', summary: 'Delete 3 users in', targetDn: 'ou=People',
      confirmLabel: 'Delete', ...props,
    },
    attachTo: document.body,
  })
}
function confirmBtn() {
  return Array.from(document.body.querySelectorAll('button'))
    .find(b => /Delete|Import|Working/.test(b.textContent || ''))!
}

afterEach(() => { document.body.innerHTML = '' })

describe('BulkConfirmModal', () => {
  it('restates the target profile + DN and themes the header band', () => {
    const w = mountModal()
    expect(document.body.textContent).toContain('PROD')
    expect(document.body.textContent).toContain('ou=People')
    // Header band carries the theme colour.
    const header = document.body.querySelector('h2')?.parentElement as HTMLElement
    expect(header.getAttribute('style')).toContain('background-color')
    w.unmount()
  })

  it('confirms with one click when not require-typed (reversible import)', async () => {
    const w = mountModal({ requireTyped: false, confirmLabel: 'Import' })
    expect(confirmBtn().hasAttribute('disabled')).toBe(false)
    confirmBtn().click()
    expect(w.emitted('confirm')).toBeTruthy()
    w.unmount()
  })

  it('arms only after typing the exact profile name when require-typed', async () => {
    const w = mountModal({ requireTyped: true })
    expect(confirmBtn().hasAttribute('disabled')).toBe(true)

    const input = document.body.querySelector('input[aria-label="Type the profile name to confirm"]') as HTMLInputElement
    input.value = 'nope'; input.dispatchEvent(new Event('input'))
    await w.vm.$nextTick()
    expect(confirmBtn().hasAttribute('disabled')).toBe(true)

    input.value = 'prod'; input.dispatchEvent(new Event('input')) // case-insensitive
    await w.vm.$nextTick()
    expect(confirmBtn().hasAttribute('disabled')).toBe(false)
    w.unmount()
  })
})
