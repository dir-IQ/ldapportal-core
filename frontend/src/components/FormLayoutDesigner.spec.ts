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
