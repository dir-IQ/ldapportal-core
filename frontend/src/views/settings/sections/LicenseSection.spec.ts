// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for LicenseSection.
 *
 * Covers the License summary panel's edition-only collapse: when the
 * instance runs on the community baseline (no installed license key,
 * `signed === false`) only the Edition field is shown; every other
 * summary field (Customer ID, Source, Issued, Expires, Signed) renders
 * only when an actual signed license key is installed (`signed === true`).
 *
 * The license store is mocked at module level — these are pure component
 * tests, not integration tests.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

interface LicenseStatus {
  edition: string
  customerId: string | null
  source: string
  signed: boolean
  issuedAt: string | null
  expiresAt: string | null
  daysRemaining: number | null
  grantedEntitlements: string[]
  addOns: string[]
  limits: Record<string, number>
}

// Mocked store handle the spec mutates before each mount.
const storeStub: {
  status: LicenseStatus | null
  loading: boolean
  error: string | null
  bannerLevel: string | null
  bannerMessage: string
  ensureLoaded: ReturnType<typeof vi.fn>
  refresh: ReturnType<typeof vi.fn>
} = {
  status: null,
  loading: false,
  error: null,
  bannerLevel: null,
  bannerMessage: '',
  ensureLoaded: vi.fn(),
  refresh: vi.fn(),
}

vi.mock('@/stores/license', () => ({
  useLicenseStore: () => storeStub,
}))

import LicenseSection from './LicenseSection.vue'

function communityStatus(): LicenseStatus {
  return {
    edition: 'COMMUNITY',
    customerId: null,
    source: 'community baseline (no license file configured)',
    signed: false,
    issuedAt: null,
    expiresAt: null,
    daysRemaining: null,
    grantedEntitlements: [],
    addOns: [],
    limits: {},
  }
}

function signedStatus(): LicenseStatus {
  return {
    edition: 'BUSINESS',
    customerId: '7f2e8a12-0000-0000-0000-000000000000',
    source: '/etc/ldapportal/license.jwt',
    signed: true,
    issuedAt: '2026-01-01T00:00:00Z',
    expiresAt: '2027-04-22T23:59:59Z',
    daysRemaining: 365,
    grantedEntitlements: [],
    addOns: [],
    limits: {},
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  storeStub.loading = false
  storeStub.error = null
  storeStub.bannerLevel = null
})

describe('LicenseSection license summary', () => {
  it('shows only the edition for the community baseline', () => {
    storeStub.status = communityStatus()
    const wrapper = mount(LicenseSection)

    const text = wrapper.text()
    expect(text).toContain('Edition')
    expect(text).toContain('COMMUNITY')
    // No license-key fields when unsigned.
    expect(text).not.toContain('Customer ID')
    expect(text).not.toContain('Source')
    expect(text).not.toContain('Signed')
  })

  it('shows all summary fields when a signed license key is installed', () => {
    storeStub.status = signedStatus()
    const wrapper = mount(LicenseSection)

    const text = wrapper.text()
    expect(text).toContain('Edition')
    expect(text).toContain('BUSINESS')
    expect(text).toContain('Customer ID')
    expect(text).toContain('7f2e8a12-0000-0000-0000-000000000000')
    expect(text).toContain('Source')
    expect(text).toContain('/etc/ldapportal/license.jwt')
    expect(text).toContain('Signed')
    expect(text).toContain('Yes')
  })
})
