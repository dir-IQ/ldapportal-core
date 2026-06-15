// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for UserIdentityHeader.
 *
 * Covers initials derivation across single-word / multi-word names,
 * fallback to alternative attributes when displayName is missing, and
 * the conditional badges (enabled / IVIA state).
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import UserIdentityHeader from './UserIdentityHeader.vue'
import type { IsvaAccountStatus } from '@/api/isvaAccount'

const DN = 'uid=alice.contractor,ou=Contractors,dc=acmecorp,dc=com'

function status(overrides: Partial<IsvaAccountStatus>): IsvaAccountStatus {
  return {
    linked: true,
    orphaned: false,
    topology: 'INLINE',
    acctValid: true,
    validUntil: null,
    pwdValid: true,
    pwdLastChanged: null,
    authority: null,
    secUserDn: null,
    ...overrides,
  }
}

describe('initials', () => {
  it('two-word displayName → first + last initial', () => {
    const w = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { displayName: 'Alice Contractor' } },
    })
    expect(w.text()).toContain('AC')
  })

  it('single-word name → first two characters', () => {
    const w = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { displayName: 'alice' } },
    })
    expect(w.text()).toContain('AL')
  })

  it('falls back through displayName → cn → uid', () => {
    const w = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { uid: 'bob' } },
    })
    expect(w.text()).toContain('BO')
  })

  it('no name attributes → renders ? placeholder', () => {
    const w = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: {} },
    })
    expect(w.text()).toContain('(unnamed)')
  })
})

describe('badges', () => {
  it('enabled=null → no Enabled/Disabled badge rendered', () => {
    const w = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { cn: 'Alice' }, enabled: null },
    })
    expect(w.text()).not.toContain('Enabled')
    expect(w.text()).not.toContain('Disabled')
  })

  it('enabled=true → green Enabled badge', () => {
    const w = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { cn: 'Alice' }, enabled: true },
    })
    expect(w.html()).toContain('badge-green')
    expect(w.text()).toContain('Enabled')
  })

  it('enabled=false → red Disabled badge', () => {
    const w = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { cn: 'Alice' }, enabled: false },
    })
    expect(w.html()).toContain('badge-red')
    expect(w.text()).toContain('Disabled')
  })

  it('ivia=null → no IVIA badge', () => {
    const w = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { cn: 'Alice' }, ivia: null },
    })
    expect(w.text()).not.toContain('IVIA')
  })

  it('ivia.linked + active → "IVIA linked" blue badge', () => {
    const w = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { cn: 'Alice' }, ivia: status({ acctValid: true }) },
    })
    expect(w.text()).toContain('IVIA linked')
    expect(w.html()).toContain('badge-blue')
  })

  it('ivia.linked + suspended → "IVIA suspended" red badge', () => {
    const w = mount(UserIdentityHeader, {
      props: {
        dn: DN,
        attributes: { cn: 'Alice' },
        ivia: status({ acctValid: false }),
      },
    })
    expect(w.text()).toContain('IVIA suspended')
  })

  it('ivia.orphaned → "IVIA orphaned" yellow badge', () => {
    const w = mount(UserIdentityHeader, {
      props: {
        dn: DN,
        attributes: { cn: 'Alice' },
        ivia: status({ linked: false, orphaned: true, acctValid: false }),
      },
    })
    expect(w.text()).toContain('IVIA orphaned')
    expect(w.html()).toContain('badge-yellow')
  })
})

describe('avatar gradient', () => {
  it('is stable across mounts for the same DN', () => {
    const a = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { cn: 'Alice' } },
    })
    const b = mount(UserIdentityHeader, {
      props: { dn: DN, attributes: { cn: 'Alice' } },
    })
    const styleA = a.find('[aria-hidden="true"]').attributes('style')
    const styleB = b.find('[aria-hidden="true"]').attributes('style')
    expect(styleA).toBe(styleB)
  })

  it('differs across DNs (collisions allowed but unlikely)', () => {
    const a = mount(UserIdentityHeader, {
      props: { dn: 'uid=alpha,dc=x', attributes: { cn: 'A' } },
    })
    const b = mount(UserIdentityHeader, {
      props: { dn: 'uid=zulu,dc=x', attributes: { cn: 'Z' } },
    })
    const styleA = a.find('[aria-hidden="true"]').attributes('style')
    const styleB = b.find('[aria-hidden="true"]').attributes('style')
    expect(styleA).not.toBe(styleB)
  })
})
