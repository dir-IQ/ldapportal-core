// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for LdapFilterBuilder.
 *
 * The pure compile/escape helpers are tested in
 * `composables/useLdapFilter.spec.ts`. These tests verify the wiring:
 * disclosure toggle, rule add/remove, conjunction switch, value-input
 * hide for `present`, schema fetch fallback, v-model emissions.
 *
 * The schema autocomplete API is mocked via vi.mock since these are
 * pure component tests — we don't want to depend on the live backend.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'

vi.mock('@/api/schema', () => ({
  listAttributeTypes: vi.fn(),
}))
import { listAttributeTypes } from '@/api/schema'
const mockListAttributeTypes = vi.mocked(listAttributeTypes)

import LdapFilterBuilder from './LdapFilterBuilder.vue'

const mountBuilder = (props: Record<string, unknown> = {}) =>
  mount(LdapFilterBuilder, {
    props: {
      modelValue: '',
      directoryId: 'dir-123',
      ...props,
    },
  })

beforeEach(() => {
  mockListAttributeTypes.mockReset()
  // Default: schema returns a couple of attrs. The mock is loosely typed
  // (we cast to satisfy the AxiosResponse<any> return type) — these
  // tests don't care about the response envelope.
  mockListAttributeTypes.mockResolvedValue({
    data: [
      { name: 'cn' }, { name: 'mail' }, { name: 'uid' }, { name: 'objectClass' },
    ],
    status: 200,
    statusText: 'OK',
    headers: {},
    config: {},
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any)
  // Clear the module-scoped schemaCache so call-counter assertions
  // (`toHaveBeenCalledTimes(1)`, etc.) don't depend on test order. The
  // cache lives at module scope inside LdapFilterBuilder.vue; the only
  // way to reach it from outside is via a mounted instance's exposed
  // helper.
  const tmp = mountBuilder()
  ;(tmp.vm as unknown as { __resetSchemaCacheForTesting: () => void })
    .__resetSchemaCacheForTesting()
  tmp.unmount()
})

describe('LdapFilterBuilder.vue', () => {
  it('starts collapsed with the trigger visible', () => {
    const w = mountBuilder()
    expect(w.text()).toContain('Build filter visually')
    expect(w.find('button[aria-label="Add rule"]').exists()).toBe(false)
  })

  it('expands on trigger click', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    expect(w.find('button[aria-label="Add rule"]').exists()).toBe(true)
    expect(w.text()).toContain('Match')
    expect(w.text()).toContain('No rules yet')
  })

  it('add rule button appends a blank rule row', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click') // expand
    await w.find('button[aria-label="Add rule"]').trigger('click')
    const ruleInputs = w.findAll('input[data-rule-attr]')
    expect(ruleInputs).toHaveLength(1)
  })

  it('emits update:modelValue when a rule is filled', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')

    // Set attr on the new rule
    const attrInput = w.find('input[data-rule-attr]')
    await attrInput.setValue('cn')
    // Set value
    const valueInput = w.findAll('input[type="text"]').at(-1)!
    await valueInput.setValue('Alice')

    await flushPromises()
    const emits = w.emitted('update:modelValue')
    expect(emits).toBeTruthy()
    // Last emit reflects the latest state.
    const last = emits![emits!.length - 1][0] as string
    expect(last).toBe('(cn=Alice)')
  })

  it('two rules with AND emits combined filter', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')

    const attrInputs = w.findAll('input[data-rule-attr]')
    await attrInputs[0].setValue('cn')
    await attrInputs[1].setValue('mail')
    // Each rule has [attr, value] text inputs; collect them in order.
    const valueInputs = w.findAll('input[type="text"]:not([data-rule-attr])')
    await valueInputs[0].setValue('Alice')
    await valueInputs[1].setValue('alice@acme.com')

    await flushPromises()
    const emits = w.emitted('update:modelValue')
    const last = emits![emits!.length - 1][0] as string
    expect(last).toBe('(&(cn=Alice)(mail=alice@acme.com))')
  })

  it('switching to OR emits OR-grouped filter', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')

    const attrInputs = w.findAll('input[data-rule-attr]')
    await attrInputs[0].setValue('cn')
    await attrInputs[1].setValue('cn')
    const valueInputs = w.findAll('input[type="text"]:not([data-rule-attr])')
    await valueInputs[0].setValue('Alice')
    await valueInputs[1].setValue('Bob')

    // Click the OR radio
    const orRadio = w.find('input[type="radio"][value="OR"]')
    await orRadio.setValue(true)

    await flushPromises()
    const last = (w.emitted('update:modelValue')!.at(-1)![0]) as string
    expect(last).toBe('(|(cn=Alice)(cn=Bob))')
  })

  it('selecting "present" hides the value input', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')

    const attrInput = w.find('input[data-rule-attr]')
    await attrInput.setValue('mail')
    // Initially "equals" — there's a value input.
    expect(w.findAll('input[type="text"]').length).toBe(2)

    const opSelect = w.find('select')
    await opSelect.setValue('present')

    expect(w.findAll('input[type="text"]').length).toBe(1) // value input gone
    expect(w.text()).toContain('(no value needed)')
  })

  it('removes a rule when × is clicked', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')

    expect(w.findAll('input[data-rule-attr]')).toHaveLength(2)
    const removeBtns = w.findAll('button[aria-label^="Remove rule"]')
    await removeBtns[0].trigger('click')
    expect(w.findAll('input[data-rule-attr]')).toHaveLength(1)
  })

  it('Clear button removes all rules', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')

    expect(w.findAll('input[data-rule-attr]')).toHaveLength(2)
    const clearBtn = [...w.findAll('button')].find(b => b.text() === 'Clear')!
    await clearBtn.trigger('click')
    expect(w.findAll('input[data-rule-attr]')).toHaveLength(0)
  })

  it('fetches schema attributes when expanded and renders combobox suggestions on focus', async () => {
    const w = mountBuilder()
    expect(mockListAttributeTypes).not.toHaveBeenCalled()  // not yet
    await w.find('button').trigger('click')
    await flushPromises()
    expect(mockListAttributeTypes).toHaveBeenCalledWith('dir-123')

    // Combobox suggestions only render when an attr input is focused
    // (the input owns its open/closed state, mirroring focus).
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('input[data-rule-attr]').trigger('focus')
    await flushPromises()
    const options = w.findAll('[data-combo-option]')
    expect(options.length).toBe(4)
    expect(options[0].text()).toBe('cn')
  })

  it('falls back silently when schema fetch fails', async () => {
    mockListAttributeTypes.mockRejectedValueOnce(new Error('forbidden'))
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('Schema unavailable')

    // No combobox suggestions render — the listbox v-if guards on a
    // non-empty filtered list, and attributeNames is empty after a
    // failed fetch. Free-text input still works.
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('input[data-rule-attr]').trigger('focus')
    expect(w.findAll('[data-combo-option]').length).toBe(0)
    expect(w.find('[data-combo-listbox]').exists()).toBe(false)
  })

  it('typing in the attr input filters the suggestion list', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await flushPromises()
    await w.find('button[aria-label="Add rule"]').trigger('click')
    const attrInput = w.find('input[data-rule-attr]')
    await attrInput.trigger('focus')
    expect(w.findAll('[data-combo-option]').length).toBe(4)

    // Substring-match, case-insensitive: "ai" → only "mail".
    await attrInput.setValue('ai')
    await flushPromises()
    const options = w.findAll('[data-combo-option]')
    expect(options.length).toBe(1)
    expect(options[0].text()).toBe('mail')
  })

  it('clicking a suggestion sets the attr value and closes the listbox', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await flushPromises()
    await w.find('button[aria-label="Add rule"]').trigger('click')
    const attrInput = w.find('input[data-rule-attr]')
    await attrInput.trigger('focus')

    // Click the second option ("mail"). mousedown.prevent on the option
    // keeps the input focused; the handler swaps the value and closes
    // the listbox.
    const options = w.findAll('[data-combo-option]')
    await options[1].trigger('mousedown')
    await flushPromises()

    expect((attrInput.element as HTMLInputElement).value).toBe('mail')
    expect(w.find('[data-combo-listbox]').exists()).toBe(false)
  })

  it('keyboard nav: ArrowDown highlights, Enter selects', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await flushPromises()
    await w.find('button[aria-label="Add rule"]').trigger('click')
    const attrInput = w.find('input[data-rule-attr]')
    await attrInput.trigger('focus')

    // No highlight initially (-1 sentinel — Enter on -1 is a no-op
    // commit of the typed value rather than picking a suggestion).
    const vm = w.vm as unknown as { comboHighlightIdx: number }
    expect(vm.comboHighlightIdx).toBe(-1)

    await attrInput.trigger('keydown', { key: 'ArrowDown' })
    expect(vm.comboHighlightIdx).toBe(0)
    await attrInput.trigger('keydown', { key: 'ArrowDown' })
    expect(vm.comboHighlightIdx).toBe(1)
    await attrInput.trigger('keydown', { key: 'ArrowUp' })
    expect(vm.comboHighlightIdx).toBe(0)

    // Enter commits the highlighted suggestion.
    await attrInput.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect((attrInput.element as HTMLInputElement).value).toBe('cn')
    expect(w.find('[data-combo-listbox]').exists()).toBe(false)
  })

  it('Escape closes the listbox without changing the value', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await flushPromises()
    await w.find('button[aria-label="Add rule"]').trigger('click')
    const attrInput = w.find('input[data-rule-attr]')
    await attrInput.setValue('partial')
    await attrInput.trigger('focus')
    expect(w.find('[data-combo-listbox]').exists()).toBe(false)
      // ("partial" matches nothing in the mocked schema; listbox
      // hidden — escape is still a valid close-without-change shortcut
      // in case the user opens it via ArrowDown next.)

    await attrInput.trigger('focus')
    await attrInput.setValue('c')
    expect(w.find('[data-combo-listbox]').exists()).toBe(true)
    await attrInput.trigger('keydown', { key: 'Escape' })
    expect(w.find('[data-combo-listbox]').exists()).toBe(false)
    expect((attrInput.element as HTMLInputElement).value).toBe('c')
  })

  it('does not refetch schema on a second expansion', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await flushPromises()
    expect(mockListAttributeTypes).toHaveBeenCalledTimes(1)
    // collapse + re-expand
    await w.find('button').trigger('click')
    await w.find('button').trigger('click')
    await flushPromises()
    expect(mockListAttributeTypes).toHaveBeenCalledTimes(1)
  })

  it('refetches schema when directoryId changes', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click') // expand → first fetch
    await flushPromises()
    expect(mockListAttributeTypes).toHaveBeenCalledTimes(1)

    await w.setProps({ directoryId: 'dir-456' })
    await flushPromises()
    expect(mockListAttributeTypes).toHaveBeenCalledTimes(2)
    expect(mockListAttributeTypes).toHaveBeenLastCalledWith('dir-456')
  })

  it('collapsed builder does not fetch schema', async () => {
    mountBuilder()
    await flushPromises()
    expect(mockListAttributeTypes).not.toHaveBeenCalled()
  })

  it('shows the rule-count chip on the trigger', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await nextTick()
    expect(w.text()).toContain('(2 rules)')
  })

  it('compiled filter preview shows on screen', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('input[data-rule-attr]').setValue('cn')
    await w.findAll('input[type="text"]:not([data-rule-attr])')[0].setValue('Alice')
    await flushPromises()
    expect(w.text()).toContain('(cn=Alice)')
  })

  it('is-one-of value input prompts comma-separated', async () => {
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    const opSelect = w.find('select')
    await opSelect.setValue('is-one-of')
    const valueInput = w.findAll('input[type="text"]:not([data-rule-attr])')[0]
    expect(valueInput.attributes('placeholder')).toContain('val1, val2')
  })

  it('switching to "present" clears the previous value (round-trip)', async () => {
    // Regression guard for the setOp() side-effect: without it, switching
    // present → equals would resurface a stale "Alice" the user thought
    // they'd discarded.
    const w = mountBuilder()
    await w.find('button').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('input[data-rule-attr]').setValue('cn')
    await w.findAll('input[type="text"]:not([data-rule-attr])')[0].setValue('Alice')

    const opSelect = w.find('select')
    await opSelect.setValue('present')
    await opSelect.setValue('equals')

    const valueInput = w.findAll('input[type="text"]:not([data-rule-attr])')[0]
    expect((valueInput.element as HTMLInputElement).value).toBe('')
  })

  it('Clear button resets conjunction back to AND', async () => {
    // Regression guard for the clearAll() reset semantics. Without this,
    // a user who built an OR query, hit Clear, then built a fresh query
    // would silently inherit the prior OR conjunction.
    const w = mountBuilder()
    await w.find('button').trigger('click') // expand
    await w.find('input[type="radio"][value="OR"]').setValue(true)
    await w.find('button[aria-label="Add rule"]').trigger('click')

    const clearBtn = [...w.findAll('button')].find(b => b.text() === 'Clear')!
    await clearBtn.trigger('click')

    // After clear, conjunction is AND again.
    const andRadio = w.find('input[type="radio"][value="AND"]')
    expect((andRadio.element as HTMLInputElement).checked).toBe(true)
  })

  it('suppresses the final update:modelValue when the compiled string already matches the prop', async () => {
    // The watcher guards against feedback loops with two-way binding.
    // Intermediate emits during rule construction are expected (the
    // rule starts incomplete and compiles to "" or "(cn=)" before the
    // user finishes typing). The guard's job is to ensure the FINAL
    // emit — when the user has finished and the compiled string would
    // equal what the parent already holds — is suppressed.
    const w = mountBuilder({ modelValue: '(cn=Alice)' })
    await w.find('button').trigger('click') // expand
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('input[data-rule-attr]').setValue('cn')
    await w.findAll('input[type="text"]:not([data-rule-attr])')[0].setValue('Alice')
    await flushPromises()
    // Specifically check the LAST emit. The guard's contract is "do
    // not emit when compiled === modelValue", so the last emit must
    // not equal the modelValue string. Asserting against the last
    // emit is more faithful to the guard than checking every
    // historical emit (a future preprocessor that flattens
    // intermediates to a final value would still satisfy the guard
    // even if the final equality check happens earlier).
    const emits = w.emitted('update:modelValue') ?? []
    const lastEmit = emits.at(-1)?.[0]
    expect(lastEmit).not.toBe('(cn=Alice)')
  })

  it('after removing a rule, the ref Map stays in sync with rendered rows', async () => {
    // Regression guard for the index-vs-stable-id fix. Before the fix,
    // removing rule[0] while keeping rule[1] left a stale entry in the
    // template-ref array indexed by 0; the next addRule() would compute
    // `length - 1 = 1`, look up the slot at index 1 (which was the
    // previous rule[1]'s element, still mounted but at index 0), and
    // focus the wrong input. With stable rule ids and a Map keyed by id,
    // the Map's domain matches exactly the set of currently-rendered
    // rule rows.
    //
    // happy-dom's focus() doesn't reliably set document.activeElement,
    // so we assert the Map's invariant directly rather than the DOM
    // focus state.
    const w = mountBuilder()
    await w.find('button').trigger('click') // expand
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await w.find('button[aria-label="Add rule"]').trigger('click')
    expect(w.findAll('input[data-rule-attr]')).toHaveLength(2)

    // Reach into the exposed internals. defineExpose auto-unwraps refs,
    // so `vm.rules` is the array directly (not a Ref wrapper). The Map
    // is exposed as-is.
    const vm = w.vm as unknown as {
      rules: Array<{ id: number, attr: string }>
      ruleAttrRefs: Map<number, HTMLInputElement>
    }
    const idsBeforeRemove = vm.rules.map(r => r.id)
    expect(vm.ruleAttrRefs.size).toBe(2)
    expect([...vm.ruleAttrRefs.keys()].sort()).toEqual([...idsBeforeRemove].sort())

    // Remove the first rule.
    await w.findAll('button[aria-label^="Remove rule"]')[0].trigger('click')
    await flushPromises()
    expect(w.findAll('input[data-rule-attr]')).toHaveLength(1)
    const idsAfterRemove = vm.rules.map(r => r.id)
    expect(idsAfterRemove).toEqual([idsBeforeRemove[1]])
    // The Map shrunk along with the row count — no stale entry left.
    expect(vm.ruleAttrRefs.size).toBe(1)
    expect([...vm.ruleAttrRefs.keys()]).toEqual(idsAfterRemove)

    // Add a new rule.
    await w.find('button[aria-label="Add rule"]').trigger('click')
    await flushPromises()
    const idsAfterAdd = vm.rules.map(r => r.id)
    expect(idsAfterAdd).toHaveLength(2)
    expect(vm.ruleAttrRefs.size).toBe(2)
    expect([...vm.ruleAttrRefs.keys()].sort((a, b) => a - b))
      .toEqual([...idsAfterAdd].sort((a, b) => a - b))
    // The newly-added id is fresh (greater than any prior id) and the
    // Map has a corresponding entry. (We don't assert el.isConnected
    // because happy-dom doesn't always wire that flag through Vue's
    // patch cycle; the size + keys assertions above already prove the
    // Map is in sync with the rendered rows.)
    const newId = idsAfterAdd[1]
    expect(newId).toBeGreaterThan(idsBeforeRemove[1])
    expect(vm.ruleAttrRefs.get(newId)).toBeDefined()
  })

  it('schema error hint appears on failed expansion and persists across collapse/re-expand', async () => {
    // Once a fetch fails, the user should keep seeing the fallback hint
    // until they pick a different directory or trigger a refetch.
    mockListAttributeTypes.mockRejectedValueOnce(new Error('forbidden'))
    const w = mountBuilder()
    await w.find('button').trigger('click') // expand → fail
    await flushPromises()
    expect(w.text()).toContain('Schema unavailable')
    // collapse, re-expand — hint stays (no new fetch is attempted thanks
    // to the schemaState !== 'idle' guard)
    await w.find('button').trigger('click') // collapse
    await w.find('button').trigger('click') // re-expand
    await flushPromises()
    expect(w.text()).toContain('Schema unavailable')
  })
})
