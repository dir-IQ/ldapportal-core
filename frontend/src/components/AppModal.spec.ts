// SPDX-License-Identifier: Apache-2.0
/**
 * AppModal: renders header/body/footer when open, exposes the body as a
 * flexible scroll region, and turns its header into a drag handle only when
 * `movable` (and only on wide enough viewports). Teleports to <body>, so we
 * query document.body. Pointer-drag math depends on real layout (absent in
 * jsdom), so it's covered by manual/e2e testing rather than here.
 */
import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppModal from './AppModal.vue'
import { usePreferencesStore } from '@/stores/preferences'

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

  const panelEl = () => document.body.querySelector('.rounded-xl') as HTMLElement

  it('fills the content area with an explicit size when `fill` is set', async () => {
    setActivePinia(createPinia())
    // Open false→true so the layout watcher fires (no main-content in jsdom →
    // falls back to the viewport, default 1024×768, minus margins).
    const w = mount(AppModal, { props: { modelValue: false, title: 'T', fill: true }, attachTo: document.body })
    await w.setProps({ modelValue: true })
    await flushPromises()
    const px = parseInt(panelEl().style.width)
    expect(px).toBeGreaterThan(400) // an explicit fill width, not the size-class cap
    w.unmount()
  })

  it('restores a persisted size from preferences (storageKey)', async () => {
    setActivePinia(createPinia())
    const store = usePreferencesStore()
    store.doc = { modals: { 'msize': { w: 600, h: 400 } } }
    const w = mount(AppModal, {
      props: { modelValue: false, title: 'T', storageKey: 'msize' },
      attachTo: document.body,
    })
    await w.setProps({ modelValue: true })
    await flushPromises()
    expect(panelEl().style.width).toBe('600px')
    expect(panelEl().style.height).toBe('400px')
    w.unmount()
  })

  it('restores by a title-derived key when no storageKey is given', async () => {
    setActivePinia(createPinia())
    const store = usePreferencesStore()
    store.doc = { modals: { 'edit-sync-set': { w: 700, h: 500 } } }
    const w = mount(AppModal, {
      props: { modelValue: false, title: 'Edit sync set' }, // no storageKey → keyed by slug(title)
      attachTo: document.body,
    })
    await w.setProps({ modelValue: true })
    await flushPromises()
    expect(panelEl().style.width).toBe('700px')
    expect(panelEl().style.height).toBe('500px')
    w.unmount()
  })
})
