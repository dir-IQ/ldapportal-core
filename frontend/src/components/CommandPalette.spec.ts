// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

const granted = vi.hoisted(() => ({ keys: new Set<string>() }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    isSuperadmin: true,
    hasFeature: () => true,
    hasSuperadminPermission: (key: string) => granted.keys.has(key),
  }),
}))

import CommandPalette from './CommandPalette.vue'

const Blank = { template: '<div />' }

function router() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{
      path: '/', component: Blank, children: [
        { path: 'superadmin/reports', component: Blank, meta: { requiresSuperadmin: true } },
        {
          path: 'superadmin/directory-diff', component: Blank,
          meta: { requiresSuperadmin: true, requiresSuperadminPermission: 'superadmin.manage_directory_diff' },
        },
      ],
    }],
  })
}

let wrapper: VueWrapper | null = null

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  granted.keys.clear()
})

async function openPalette() {
  const r = router()
  r.push('/')
  await r.isReady()
  wrapper = mount(CommandPalette, { global: { plugins: [r] }, attachTo: document.body })
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
  await flushPromises()
  return Array.from(document.body.querySelectorAll('[role="dialog"] button')).map(b => b.textContent ?? '')
}

describe('CommandPalette', () => {
  it('lists Directory Diff under Report for a superadmin holding its permission', async () => {
    granted.keys.add('superadmin.manage_directory_diff')
    const items = await openPalette()
    expect(items.some(t => t.includes('Directory Diff') && t.includes('Report'))).toBe(true)
  })

  it('hides a page whose superadmin permission is missing', async () => {
    const items = await openPalette()
    expect(items.some(t => t.includes('Directory Diff'))).toBe(false)
    expect(items.some(t => t.includes('Reports'))).toBe(true)
  })
})
