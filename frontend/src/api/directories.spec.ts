// SPDX-License-Identifier: Apache-2.0
/**
 * listDirectories() is the single chokepoint every directory picker/panel
 * funnels through (directly or via useDirectoryPicker), so it sorts results
 * case-insensitively by display name — the whole app then shows directories
 * alphabetically without per-component sorting.
 */
import { describe, it, expect, vi } from 'vitest'

vi.mock('./apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

import { apiGet } from './apiClient'
import { listDirectories } from './directories'

describe('listDirectories', () => {
  it('returns directories sorted case-insensitively by display name', async () => {
    vi.mocked(apiGet).mockResolvedValue({
      data: [
        { id: '1', displayName: 'zebra' },
        { id: '2', displayName: 'Apple' },
        { id: '3', displayName: 'mango' },
      ],
    } as never)

    const res = await listDirectories()

    expect(res.data.map((d) => d.displayName)).toEqual(['Apple', 'mango', 'zebra'])
  })
})
