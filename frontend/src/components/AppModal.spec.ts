// SPDX-License-Identifier: Apache-2.0
/**
 * AppModal: renders header/body/footer when open, exposes the body as a
 * flexible scroll region, and turns its header into a drag handle only when
 * `movable` (and only on wide enough viewports). Teleports to <body>, so we
 * query document.body. Pointer-drag math depends on real layout (absent in
 * jsdom), so it's covered by manual/e2e testing rather than here.
 */
import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AppModal from './AppModal.vue'

function mountModal(props = {}, slots = {}) {
  return mount(AppModal, {
    props: { modelValue: true, title: 'Test', ...props },
    slots,
    attachTo: document.body,
  })
}

afterEach(() => { document.body.innerHTML = '' })

describe('AppModal', () => {
  it('renders the title, default slot and footer slot when open', () => {
    const w = mountModal({}, { default: '<p class="body-content">hello</p>', footer: '<button>OK</button>' })
    expect(document.body.querySelector('.body-content')).toBeTruthy()
    expect(document.body.textContent).toContain('Test')
    expect(document.body.textContent).toContain('OK')
    w.unmount()
  })

  it('renders nothing when closed', () => {
    const w = mountModal({ modelValue: false }, { default: '<p class="body-content">hello</p>' })
    expect(document.body.querySelector('.body-content')).toBeFalsy()
    w.unmount()
  })

  it('makes the body a flexible scroll region', () => {
    const w = mountModal({}, { default: '<p>hi</p>' })
    const body = document.body.querySelector('.overflow-y-auto')
    expect(body?.className).toContain('flex-1')
    expect(body?.className).toContain('min-h-0')
    w.unmount()
  })

  it('marks the header as a drag handle only when movable', () => {
    const a = mountModal({ movable: true })
    expect(document.body.querySelector('.cursor-move')).toBeTruthy()
    a.unmount()

    const b = mountModal({ movable: false })
    expect(document.body.querySelector('.cursor-move')).toBeFalsy()
    b.unmount()
  })

  it('renders a resize grip only when resizable', () => {
    const a = mountModal({ resizable: true })
    expect(document.body.querySelector('.cursor-nwse-resize')).toBeTruthy()
    a.unmount()

    const b = mountModal({ resizable: false })
    expect(document.body.querySelector('.cursor-nwse-resize')).toBeFalsy()
    b.unmount()
  })
})
