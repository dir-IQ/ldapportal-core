// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import FormLayoutDesigner from './FormLayoutDesigner.vue'

vi.mock('@/composables/useDragAutoScroll', () => ({
  useDragAutoScroll: () => ({ start: vi.fn(), stop: vi.fn() }),
}))

function cfg(name: string, extra: Record<string, unknown> = {}) {
  return { attributeName: name, inputType: 'TEXT', columnSpan: 6, hidden: false, sectionName: '', ...extra }
}

describe('FormLayoutDesigner — naming-badge reactivity', () => {
  // Regression: the designer cached rdn/naming flags in its internal sections and
  // skipped updates when only the naming source changed (same attribute set, same
  // RDN name) — so switching DN mode / editing the template left stale RDN badges.
  it('updates naming badges when the incoming naming flags change', async () => {
    // uid is the RDN; cn is an additional naming attribute (multi-valued RDN),
    // so cn renders as a regular card with a naming ("RDN") badge.
    const withCnNaming = [
      cfg('uid', { rdn: true, naming: true }),
      cfg('cn', { rdn: false, naming: true }),
    ]
    const withoutCnNaming = [
      cfg('uid', { rdn: true, naming: true }),
      cfg('cn', { rdn: false, naming: false }),
    ]

    const wrapper = mount(FormLayoutDesigner, {
      props: { attributeConfigs: withCnNaming, showDnField: false },
    })
    await wrapper.vm.$nextTick()

    const rdnBadges = () => wrapper.findAll('span').filter(s => s.text() === 'RDN').length
    // uid RDN card + cn naming badge = 2
    expect(rdnBadges()).toBe(2)

    // The naming source changes (e.g. template cleared) — cn is no longer naming,
    // but the attribute set and RDN name are unchanged.
    await wrapper.setProps({ attributeConfigs: withoutCnNaming })
    await wrapper.vm.$nextTick()

    // cn's stale badge must be gone: only uid's RDN card remains.
    expect(rdnBadges()).toBe(1)
  })
})

describe('FormLayoutDesigner — per-type width rule', () => {
  // The designer offers (and draws) only the widths the rendered form honors —
  // see utils/fieldWidth — so what the admin sees is what the operator gets.
  function mountWith(fields: ReturnType<typeof cfg>[]) {
    return mount(FormLayoutDesigner, {
      props: { attributeConfigs: [cfg('uid', { rdn: true }), ...fields], showDnField: false },
    })
  }

  // Width buttons on the card for `attr`, by label.
  function widthButtons(wrapper: ReturnType<typeof mountWith>, attr: string): string[] {
    const card = wrapper.findAll('[draggable="true"]').find(c => c.text().includes(attr))!
    return card.findAll('button[title]').map(b => b.text())
  }

  it('offers only 1/2 and Full for password and DN-lookup fields', () => {
    const wrapper = mountWith([
      cfg('userPassword', { inputType: 'PASSWORD' }),
      cfg('manager', { inputType: 'DN_LOOKUP' }),
    ])
    expect(widthButtons(wrapper, 'userPassword')).toEqual(['1/2', 'Full'])
    expect(widthButtons(wrapper, 'manager')).toEqual(['1/2', 'Full'])
  })

  it('offers every width for multi-line fields and draws them at the chosen width', () => {
    const wrapper = mountWith([cfg('description', { inputType: 'TEXTAREA', columnSpan: 2 })])
    expect(widthButtons(wrapper, 'description')).toEqual(['1/3', '1/2', '2/3', 'Full'])
    const card = wrapper.findAll('[draggable="true"]').find(c => c.text().includes('description'))!
    expect(card.attributes('style')).toContain('grid-column: span 2')
  })

  it('draws a narrow saved password at 1/2, matching the rendered form', () => {
    const wrapper = mountWith([cfg('userPassword', { inputType: 'PASSWORD', columnSpan: 2 })])
    const card = wrapper.findAll('[draggable="true"]').find(c => c.text().includes('userPassword'))!
    expect(card.attributes('style')).toContain('grid-column: span 3')
  })
})
