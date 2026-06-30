// SPDX-License-Identifier: Apache-2.0
/**
 * AppModal: renders header/body/footer when open, exposes the body as a
 * flexible scroll region, and turns its header into a drag handle only when
 * `movable` (and only on wide enough viewports). Teleports to <body>, so we
 * query document.body. Pointer-drag math depends on real layout (absent in
 * jsdom), so it's covered by manual/e2e testing rather than here.
 */
import { describe, it, expect, afterEach, vi } from 'vitest'
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

  it('keeps the default white header (gray title) when no headerColor is set', () => {
    const w = mountModal({}, { default: '<p>hi</p>' })
    const header = document.body.querySelector('h2')?.parentElement as HTMLElement
    expect(header.getAttribute('style') || '').not.toContain('background')
    expect(document.body.querySelector('h2')?.className).toContain('text-gray-900')
    w.unmount()
  })

  it('renders a colour band with contrasting title when headerColor is set', () => {
    const w = mountModal({ headerColor: '#2563eb' }, { default: '<p>hi</p>' })
    const header = document.body.querySelector('h2')?.parentElement as HTMLElement
    // Dark brand colour → white title for legibility on the band.
    expect(header.getAttribute('style')).toContain('background-color')
    expect(document.body.querySelector('h2')?.className).toContain('text-white')
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

  it('sizes dynamically by default: no fixed height, capped at the padded viewport', () => {
    setActivePinia(createPinia())
    // The panel must take its content's natural height (so short content
    // shows no inner scrollbar) and cap at 100% of the p-4 wrapper — the
    // window minus a 1rem margin all round — where the body starts
    // scrolling instead.
    const w = mountModal({}, { default: '<p>hi</p>' })
    expect(panelEl().style.height).toBe('')
    expect(panelEl().style.maxHeight).toBe('100%')
    w.unmount()
  })

  // Teleported content isn't reachable through wrapper.find — query the
  // document and click directly, then read emitted events off the wrapper.
  const bodyButton = (match: string) =>
    [...document.body.querySelectorAll('button')]
      .find(b => (b.getAttribute('aria-label') || b.textContent || '').includes(match)) as HTMLElement

  it('closes immediately from the x button when not dirty', async () => {
    setActivePinia(createPinia())
    const w = mountModal({}, { default: '<p>hi</p>' })
    bodyButton('Close').click()
    await w.vm.$nextTick()
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([false])
    w.unmount()
  })

  it('asks before discarding unsaved values, on every dismissal path', async () => {
    setActivePinia(createPinia())
    const w = mountModal({ dirty: true }, {
      default: '<p>hi</p>',
      // String slots receive scoped-slot props as `params` — wire Cancel the
      // way consumers do, through the footer slot's `close` guard.
      footer: '<button class="guarded-cancel" @click="params.close()">Cancel</button>',
    })

    // x button: confirmation appears, nothing emitted yet.
    bodyButton('Close').click()
    await w.vm.$nextTick()
    expect(document.body.textContent).toContain('Discard unsaved changes?')
    expect(w.emitted('update:modelValue')).toBeUndefined()

    // Keep editing: confirmation goes away, still open.
    bodyButton('Keep editing').click()
    await w.vm.$nextTick()
    expect(document.body.textContent).not.toContain('Discard unsaved changes?')
    expect(w.emitted('update:modelValue')).toBeUndefined()

    // Footer Cancel through the slot's close prop: guarded the same way.
    ;(document.body.querySelector('.guarded-cancel') as HTMLElement).click()
    await w.vm.$nextTick()
    expect(document.body.textContent).toContain('Discard unsaved changes?')

    // Discard: now (and only now) the close is emitted.
    bodyButton('Discard').click()
    await w.vm.$nextTick()
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([false])
    w.unmount()
  })

  it('grows with content but never shrinks while open (stable across tab switches)', async () => {
    setActivePinia(createPinia())
    // jsdom has no ResizeObserver/layout; stub the observer and drive its
    // callback with mocked panel heights. The contract: the largest height
    // the content has needed becomes a min-height, so switching to a
    // shorter tab doesn't bounce the modal.
    let trigger: (() => void) | null = null
    vi.stubGlobal('ResizeObserver', class {
      constructor(cb: () => void) { trigger = cb }
      observe() {}
      disconnect() {}
    })
    try {
      const w = mountModal({}, { default: '<p>hi</p>' })
      await flushPromises()
      const panel = panelEl()
      Object.defineProperty(panel, 'offsetHeight', { configurable: true, value: 500 })
      trigger!()
      await w.vm.$nextTick()
      expect(panel.style.minHeight).toBe('500px')

      // Content gets shorter (e.g. a shorter tab) → height holds.
      Object.defineProperty(panel, 'offsetHeight', { configurable: true, value: 300 })
      trigger!()
      await w.vm.$nextTick()
      expect(panel.style.minHeight).toBe('500px')

      // Content gets taller → grows (still viewport-capped via maxHeight).
      Object.defineProperty(panel, 'offsetHeight', { configurable: true, value: 600 })
      trigger!()
      await w.vm.$nextTick()
      expect(panel.style.minHeight).toBe('600px')
      w.unmount()
    } finally {
      vi.unstubAllGlobals()
    }
  })

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
