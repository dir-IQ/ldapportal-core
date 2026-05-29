// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for IsvaAccountPanel.
 *
 * Covers:
 *  - Gating: addon-off → hidden; directory IVIA-off → hidden; both on → visible.
 *  - State-derived action visibility (idempotency policy table):
 *      orphaned → only Grant; linked+active → Suspend; linked+suspended → Restore.
 *  - ProblemDetail `code` → inline help mapping for the four UI-facing codes.
 *
 * Stores + api are mocked at the module level — pure component tests.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import type { IsvaAccountStatus } from '@/api/isvaAccount'

const hoisted = vi.hoisted(() => ({
  authEnabled: { value: true },
  getIsvaConfig: vi.fn(),
  getStatus: vi.fn(),
  grant: vi.fn(),
  revoke: vi.fn(),
  suspend: vi.fn(),
  restore: vi.fn(),
  renew: vi.fn(),
  forceReset: vi.fn(),
  timeline: vi.fn(),
  notifSuccess: vi.fn(),
  notifError: vi.fn(),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    get isIsvaIntegrationEnabled() {
      return hoisted.authEnabled.value
    },
  }),
}))

vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({
    success: hoisted.notifSuccess,
    error: hoisted.notifError,
    info: vi.fn(),
    warning: vi.fn(),
  }),
}))

vi.mock('@/api/isvaConfig', () => ({
  getIsvaConfig: hoisted.getIsvaConfig,
}))

vi.mock('@/api/isvaAccount', () => ({
  getIsvaAccountStatus: hoisted.getStatus,
  grantIsvaAccount: hoisted.grant,
  revokeIsvaAccount: hoisted.revoke,
  suspendIsvaAccount: hoisted.suspend,
  restoreIsvaAccount: hoisted.restore,
  renewIsvaAccount: hoisted.renew,
  forceCredentialReset: hoisted.forceReset,
}))

vi.mock('@/api/audit', () => ({
  getEntryTimeline: hoisted.timeline,
}))

import IsvaAccountPanel from './IsvaAccountPanel.vue'

const DIR = 'dir-1'
const DN = 'uid=alice,ou=people,dc=example,dc=com'

function statusPresent(overrides: Partial<IsvaAccountStatus> = {}): IsvaAccountStatus {
  return {
    linked: true,
    orphaned: false,
    topology: 'INLINE',
    acctValid: true,
    validUntil: new Date(Date.now() + 365 * 24 * 3600 * 1000).toISOString(),
    pwdValid: true,
    pwdLastChanged: new Date(Date.now() - 12 * 24 * 3600 * 1000).toISOString(),
    authority: 'Default',
    secUserDn: null,
    ...overrides,
  }
}

function statusOrphan(): IsvaAccountStatus {
  return {
    linked: false,
    orphaned: true,
    topology: 'INLINE',
    acctValid: false,
    validUntil: null,
    pwdValid: false,
    pwdLastChanged: null,
    authority: null,
    secUserDn: null,
  }
}

function mountPanel() {
  return mount(IsvaAccountPanel, {
    props: { dirId: DIR, dn: DN },
    global: { stubs: { Teleport: true, AppModal: true, FormField: true } },
  })
}

beforeEach(() => {
  hoisted.authEnabled.value = true
  hoisted.getIsvaConfig.mockReset()
  hoisted.getStatus.mockReset()
  hoisted.grant.mockReset()
  hoisted.revoke.mockReset()
  hoisted.suspend.mockReset()
  hoisted.restore.mockReset()
  hoisted.renew.mockReset()
  hoisted.forceReset.mockReset()
  hoisted.timeline.mockReset()
  hoisted.timeline.mockResolvedValue({ data: { content: [] } })
  hoisted.notifSuccess.mockReset()
  hoisted.notifError.mockReset()
})

// ── gating ─────────────────────────────────────────────────────

describe('gating', () => {
  it('addon off → panel renders nothing', async () => {
    hoisted.authEnabled.value = false
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toBe('')
    expect(hoisted.getIsvaConfig).not.toHaveBeenCalled()
    expect(hoisted.getStatus).not.toHaveBeenCalled()
  })

  it('directory IVIA disabled → panel renders nothing', async () => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: false } })
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toBe('')
    expect(hoisted.getStatus).not.toHaveBeenCalled()
  })

  it('addon + directory both enabled → panel mounts and fetches status', async () => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: true } })
    hoisted.getStatus.mockResolvedValue({ data: statusPresent() })
    const w = mountPanel()
    await flushPromises()
    expect(hoisted.getStatus).toHaveBeenCalledWith(DIR, DN)
    expect(w.text()).toContain('Linked')
  })

  it('directory-disabled refusal from getIsvaConfig hides panel quietly', async () => {
    // 4xx with ivia_directory_disabled is treated identically to a 404
    // (no config row). Specifically not surfaced as an error toast.
    hoisted.getIsvaConfig.mockRejectedValue({
      response: { data: { code: 'ivia_directory_disabled', detail: 'off' } },
    })
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toBe('')
    expect(hoisted.notifError).not.toHaveBeenCalled()
  })
})

// ── state-derived button visibility ────────────────────────────

describe('action visibility', () => {
  beforeEach(() => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: true } })
  })

  it('orphaned: Grant CTA visible, lifecycle verbs absent', async () => {
    hoisted.getStatus.mockResolvedValue({ data: statusOrphan() })
    const w = mountPanel()
    await flushPromises()
    const buttons = w.findAll('button').map((b) => b.text().trim())
    expect(buttons.some((t) => t.includes('Grant'))).toBe(true)
    expect(buttons.some((t) => t === 'Restore')).toBe(false)
    expect(buttons.some((t) => t === 'Suspend')).toBe(false)
  })

  it('linked + active: Suspend visible, Restore hidden', async () => {
    hoisted.getStatus.mockResolvedValue({ data: statusPresent({ acctValid: true }) })
    const w = mountPanel()
    await flushPromises()
    const buttons = w.findAll('button').map((b) => b.text().trim())
    expect(buttons.some((t) => t === 'Suspend')).toBe(true)
    expect(buttons.some((t) => t === 'Restore')).toBe(false)
  })

  it('linked + suspended: Restore visible, Suspend hidden', async () => {
    hoisted.getStatus.mockResolvedValue({
      data: statusPresent({ acctValid: false }),
    })
    const w = mountPanel()
    await flushPromises()
    const buttons = w.findAll('button').map((b) => b.text().trim())
    expect(buttons.some((t) => t === 'Restore')).toBe(true)
    expect(buttons.some((t) => t === 'Suspend')).toBe(false)
  })

  it('force-reset button disabled when pwdValid is already false (no-op state)', async () => {
    hoisted.getStatus.mockResolvedValue({
      data: statusPresent({ pwdValid: false }),
    })
    const w = mountPanel()
    await flushPromises()
    const btn = w.findAll('button').find((b) => b.text().includes('Force credential reset'))
    expect(btn).toBeDefined()
    expect(btn!.attributes('disabled')).toBeDefined()
  })
})

// ── verb invocation ────────────────────────────────────────────

describe('verb invocation', () => {
  beforeEach(() => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: true } })
  })

  it('clicking Suspend calls the API and refreshes the snapshot', async () => {
    hoisted.getStatus.mockResolvedValue({ data: statusPresent({ acctValid: true }) })
    hoisted.suspend.mockResolvedValue({ data: statusPresent({ acctValid: false }) })
    const w = mountPanel()
    await flushPromises()
    await w.findAll('button').find((b) => b.text() === 'Suspend')!.trigger('click')
    await flushPromises()
    expect(hoisted.suspend).toHaveBeenCalledWith(DIR, DN)
    expect(hoisted.notifSuccess).toHaveBeenCalled()
  })

  it('clicking Grant on an orphan calls grant and emits status-changed', async () => {
    hoisted.getStatus.mockResolvedValue({ data: statusOrphan() })
    hoisted.grant.mockResolvedValue({ data: statusPresent() })
    const w = mountPanel()
    await flushPromises()
    await w.findAll('button').find((b) => b.text().includes('Grant'))!.trigger('click')
    await flushPromises()
    expect(hoisted.grant).toHaveBeenCalledWith(DIR, DN)
    expect(w.emitted('status-changed')).toBeTruthy()
  })
})

// ── refusal code → inline help mapping ─────────────────────────

describe('refusal code → inline help', () => {
  beforeEach(() => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: true } })
    hoisted.getStatus.mockResolvedValue({ data: statusOrphan() })
  })

  function refusal(code: string, detail = 'refused') {
    return { response: { data: { code, detail } } }
  }

  async function clickGrantAndExpectHelp(code: string, expectedText: string) {
    hoisted.grant.mockRejectedValue(refusal(code))
    const w = mountPanel()
    await flushPromises()
    await w.findAll('button').find((b) => b.text().includes('Grant'))!.trigger('click')
    await flushPromises()
    expect(w.text()).toContain(expectedText)
    return w
  }

  it('ivia_force_off → "Flip the profile override"', async () => {
    await clickGrantAndExpectHelp('ivia_force_off', 'Flip the override')
  })

  it('ivia_directory_disabled → "Enable IVIA on this directory"', async () => {
    await clickGrantAndExpectHelp('ivia_directory_disabled', 'Enable it under Directory')
  })

  it('ivia_state_changed → blue alert with Refresh button', async () => {
    const w = await clickGrantAndExpectHelp('ivia_state_changed', 'changed under you')
    const refresh = w.findAll('button').find((b) => b.text() === 'Refresh')
    expect(refresh).toBeDefined()
  })

  it('ivia_already_linked → toast (no inline help)', async () => {
    hoisted.grant.mockRejectedValue(refusal('ivia_already_linked', 'already linked'))
    const w = mountPanel()
    await flushPromises()
    await w.findAll('button').find((b) => b.text().includes('Grant'))!.trigger('click')
    await flushPromises()
    expect(hoisted.notifError).toHaveBeenCalledWith('already linked')
    expect(w.text()).not.toContain('Flip the override')
  })

  it('unknown error code → toast', async () => {
    hoisted.grant.mockRejectedValue({ response: { data: { detail: 'boom' } } })
    const w = mountPanel()
    await flushPromises()
    await w.findAll('button').find((b) => b.text().includes('Grant'))!.trigger('click')
    await flushPromises()
    expect(hoisted.notifError).toHaveBeenCalledWith('boom')
  })
})
