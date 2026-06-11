// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useDragAutoScroll, type DragAutoScroll } from './useDragAutoScroll'

// rAF is stubbed so the loop can be driven one frame at a time (the real loop
// reschedules itself and would never settle synchronously).
let rafCb: FrameRequestCallback | null = null

function tick() {
  const cb = rafCb
  rafCb = null
  cb?.(0)
}

// Mount a throwaway component so the composable's onBeforeUnmount has a context.
function setup(opts?: Parameters<typeof useDragAutoScroll>[0]) {
  let api!: DragAutoScroll
  const wrapper = mount(defineComponent({
    setup() {
      api = useDragAutoScroll(opts)
      return () => h('div')
    },
  }))
  return { api, wrapper }
}

// A scroll container 300px tall (viewport rows 100–400) with 1000px of content.
function makeScroller(): { container: HTMLElement; child: HTMLElement } {
  const container = document.createElement('div')
  container.style.overflowY = 'auto'
  Object.defineProperty(container, 'scrollHeight', { value: 1000, configurable: true })
  Object.defineProperty(container, 'clientHeight', { value: 300, configurable: true })
  container.getBoundingClientRect = () =>
    ({ top: 100, bottom: 400, left: 0, right: 0, width: 0, height: 300, x: 0, y: 100, toJSON() {} })
  const child = document.createElement('div')
  container.appendChild(child)
  document.body.appendChild(container)
  return { container, child }
}

function dragOverAt(clientY: number) {
  document.dispatchEvent(new MouseEvent('dragover', { clientY } as MouseEventInit))
}

describe('useDragAutoScroll', () => {
  beforeEach(() => {
    rafCb = null
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { rafCb = cb; return 1 })
    vi.stubGlobal('cancelAnimationFrame', () => { rafCb = null })
  })
  afterEach(() => {
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('scrolls down when the pointer is in the bottom edge band', () => {
    const { container, child } = makeScroller()
    const { api } = setup({ edge: 50, maxSpeed: 10 })
    api.start(child)
    dragOverAt(395) // 5px from the bottom (400) → near full speed
    tick()
    expect(container.scrollTop).toBeGreaterThan(0)
  })

  it('scrolls up when the pointer is in the top edge band', () => {
    const { container, child } = makeScroller()
    container.scrollTop = 500
    const { api } = setup({ edge: 50, maxSpeed: 10 })
    api.start(child)
    dragOverAt(105) // 5px from the top (100)
    tick()
    expect(container.scrollTop).toBeLessThan(500)
  })

  it('does not scroll when the pointer is in the middle', () => {
    const { container, child } = makeScroller()
    const { api } = setup({ edge: 50, maxSpeed: 10 })
    api.start(child)
    dragOverAt(250) // dead centre
    tick()
    expect(container.scrollTop).toBe(0)
  })

  it('stop() halts the loop and detaches the listener', () => {
    const { container, child } = makeScroller()
    const { api } = setup({ edge: 50, maxSpeed: 10 })
    api.start(child)
    api.stop()
    dragOverAt(395)
    tick()
    expect(container.scrollTop).toBe(0)
  })

  it('is a no-op when there is no scrollable ancestor', () => {
    const loose = document.createElement('div')
    document.body.appendChild(loose)
    const { api } = setup()
    expect(() => { api.start(loose); tick() }).not.toThrow()
  })
})
