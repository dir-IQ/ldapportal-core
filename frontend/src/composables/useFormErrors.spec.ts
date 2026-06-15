// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { useFormErrors } from './useFormErrors'

beforeEach(() => {
  document.body.innerHTML = ''
  // scrollIntoView isn't implemented in jsdom; stub it.
  Element.prototype.scrollIntoView = vi.fn()
})

function field(key: string): HTMLInputElement {
  const el = document.createElement('input')
  el.setAttribute('data-field', key)
  document.body.appendChild(el)
  return el
}

describe('useFormErrors', () => {
  it('builds a labelled summary from the error map', () => {
    const f = useFormErrors({ labelFor: k => k.toUpperCase() })
    f.setErrors({ mail: 'bad', sn: 'required' })
    expect(f.summary.value).toEqual([
      { key: 'mail', label: 'MAIL', message: 'bad' },
      { key: 'sn', label: 'SN', message: 'required' },
    ])
  })

  it('report() reveals the summary and reports whether the form has errors', () => {
    const f = useFormErrors()
    expect(f.report()).toBe(false)
    expect(f.showSummary.value).toBe(false)

    f.setErrors({ a: 'x' })
    expect(f.report()).toBe(true)
    expect(f.showSummary.value).toBe(true)
  })

  it('focuses and scrolls to the first failing field in document order', async () => {
    field('a')                 // valid
    const b = field('b')       // first error
    field('c')                 // also error, but later in the DOM
    const f = useFormErrors()
    f.setErrors({ b: 'bad', c: 'bad' })

    f.report(document.body)
    await nextTick()

    expect(document.activeElement).toBe(b)
    expect(b.scrollIntoView).toHaveBeenCalled()
  })

  it('clear() empties the errors and hides the summary', () => {
    const f = useFormErrors()
    f.setErrors({ a: 'x' })
    f.report()
    f.clear()
    expect(f.summary.value).toEqual([])
    expect(f.showSummary.value).toBe(false)
  })
})
