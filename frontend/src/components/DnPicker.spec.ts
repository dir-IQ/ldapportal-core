// SPDX-License-Identifier: Apache-2.0
/**
 * DnPicker's subtree mode on a directory with several top-level roots: the
 * browse of the directory root lists the others (otherRoots), and the
 * picker shows one tree root per base DN, so a DN under any of them can be
 * picked. A root that can't be read is left out; caller-supplied
 * authorizedRoots still replace the directory's roots entirely.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import type { AxiosResponse } from 'axios'
import DnPicker from './DnPicker.vue'
import { browse } from '@/api/browse'

vi.mock('@/api/browse', () => ({ browse: vi.fn(), directoryBrowse: vi.fn() }))
vi.mock('@/api/groups', () => ({ searchGroups: vi.fn() }))
vi.mock('@/api/entra', () => ({ listEntraGroups: vi.fn() }))

const BASE = 'dc=example,dc=com'
const PARTNERS = 'o=partners'

function page(dn: string, extra: Record<string, unknown> = {}): AxiosResponse {
  return { data: { dn, attributes: {}, children: [], ...extra }, status: 200, statusText: 'OK',
    headers: {}, config: {} } as unknown as AxiosResponse
}

async function openPicker(props: Record<string, unknown> = {}) {
  const wrapper = mount(DnPicker, {
    props: { directoryId: 'dir-1', ...props },
    global: { stubs: { teleport: true }, directives: { 'dialog-a11y': {} } },
  })
  await wrapper.find('button[title="Browse directory tree"]').trigger('click')
  await flushPromises()
  return wrapper
}

describe('DnPicker subtree mode with several roots', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows a tree root for each base DN the directory root lists', async () => {
    vi.mocked(browse).mockImplementation(async (_dir: string, dn?: string) =>
      dn ? page(dn) : page(BASE, { otherRoots: [PARTNERS] }))

    const wrapper = await openPicker()

    expect(browse).toHaveBeenCalledWith('dir-1', PARTNERS)
    const text = wrapper.find('.dn-tree').text()
    expect(text).toContain(BASE)
    expect(text).toContain(PARTNERS)
  })

  it('leaves out a root that cannot be read', async () => {
    vi.mocked(browse).mockImplementation(async (_dir: string, dn?: string) => {
      if (dn === PARTNERS) throw new Error('no such object')
      return dn ? page(dn) : page(BASE, { otherRoots: [PARTNERS] })
    })
    vi.spyOn(console, 'warn').mockImplementation(() => {})

    const wrapper = await openPicker()

    const text = wrapper.find('.dn-tree').text()
    expect(text).toContain(BASE)
    expect(text).not.toContain(PARTNERS)
  })

  it('uses authorizedRoots instead of the directory roots when given', async () => {
    vi.mocked(browse).mockImplementation(async (_dir: string, dn?: string) => page(dn ?? BASE))

    const wrapper = await openPicker({ authorizedRoots: ['ou=people,' + BASE] })

    expect(browse).toHaveBeenCalledTimes(1)
    expect(browse).toHaveBeenCalledWith('dir-1', 'ou=people,' + BASE)
    expect(wrapper.find('.dn-tree').text()).toContain('ou=people,' + BASE)
  })
})
