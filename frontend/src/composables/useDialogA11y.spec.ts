// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, afterEach } from 'vitest'
import { defineComponent, h, nextTick, ref, type Ref } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { useDialogA11y } from './useDialogA11y'

interface Dialog {
  open: Ref<boolean>
  onClose: ReturnType<typeof vi.fn>
  wrapper: VueWrapper
}

/** A minimal dialog: two buttons in a panel, closed by flipping `open`. */
function dialog(label: string, startOpen = false): Dialog {
  const open = ref<boolean>(startOpen)
  const onClose = vi.fn(() => { open.value = false })
  const Comp = defineComponent({
    setup() {
      const panel = ref<HTMLElement | null>(null)
      useDialogA11y({ isOpen: () => open.value, containerRef: panel, onClose })
      return () => (open.value
        ? h('div', { ref: panel }, [h('button', `${label} first`), h('button', `${label} last`)])
        : null)
    },
  })
  return { open, onClose, wrapper: mount(Comp, { attachTo: document.body }) }
}

function press(key: string, shiftKey = false): void {
  document.dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }))
}

const mounted: VueWrapper[] = []
function track(d: Dialog): Dialog {
  mounted.push(d.wrapper)
  return d
}

afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
})

describe('useDialogA11y', () => {
  it('closes a lone dialog on Escape', async () => {
    const a = track(dialog('a', true))
    await nextTick()
    press('Escape')
    expect(a.onClose).toHaveBeenCalledTimes(1)
  })

  it('closes only the topmost of stacked dialogs on Escape', async () => {
    const outer = track(dialog('outer', true))
    await nextTick()
    const inner = track(dialog('inner'))
    inner.open.value = true
    await nextTick()

    press('Escape')
    await nextTick()
    expect(inner.onClose).toHaveBeenCalledTimes(1)
    expect(outer.onClose).not.toHaveBeenCalled()
    expect(outer.open.value).toBe(true)

    // With the inner one gone, Escape reaches the outer one again.
    press('Escape')
    expect(outer.onClose).toHaveBeenCalledTimes(1)
  })

  it('hands the keyboard back when the top dialog unmounts while open', async () => {
    const outer = track(dialog('outer', true))
    await nextTick()
    const inner = dialog('inner', true)
    await nextTick()
    inner.wrapper.unmount()

    press('Escape')
    expect(inner.onClose).not.toHaveBeenCalled()
    expect(outer.onClose).toHaveBeenCalledTimes(1)
  })

  it('leaves Tab to the topmost dialog\'s focus trap', async () => {
    track(dialog('outer', true))
    await nextTick()
    track(dialog('inner', true))
    await nextTick()
    await nextTick()

    const last = Array.from(document.querySelectorAll('button')).find((b) => b.textContent === 'inner last')!
    last.focus()
    press('Tab')
    expect(document.activeElement?.textContent).toBe('inner first')
  })
})
