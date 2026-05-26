// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the underlying axios client BEFORE importing apiClient.
// apiClient delegates every call to client.get/post/put/delete,
// and we want to inspect those calls in tests.
vi.mock('./client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

// eslint-disable-next-line import/first
import client from './client'
// eslint-disable-next-line import/first
import { apiGet, apiPost, apiPut, apiDelete } from './apiClient'

describe('apiClient', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('apiGet', () => {
    it('strips /api/v1 prefix before calling client.get', async () => {
      ;(client.get as ReturnType<typeof vi.fn>).mockResolvedValue({ data: [], status: 200 })
      await apiGet('/api/v1/superadmin/directories' as any)
      expect(client.get).toHaveBeenCalledWith('/superadmin/directories', undefined)
    })

    it('passes config through unchanged', async () => {
      ;(client.get as ReturnType<typeof vi.fn>).mockResolvedValue({ data: null, status: 200 })
      const config = { headers: { 'X-Custom': 'value' } }
      await apiGet('/api/v1/superadmin/directories' as any, config)
      expect(client.get).toHaveBeenCalledWith('/superadmin/directories', config)
    })

    it('returns the AxiosResponse from client.get unchanged', async () => {
      const response = { data: [{ id: '1' }], status: 200, statusText: 'OK', headers: {}, config: {} as any }
      ;(client.get as ReturnType<typeof vi.fn>).mockResolvedValue(response)
      const result = await apiGet('/api/v1/superadmin/directories' as any)
      expect(result).toBe(response)
    })

    it('does not mangle paths that lack /api/v1 prefix', async () => {
      ;(client.get as ReturnType<typeof vi.fn>).mockResolvedValue({ data: null, status: 200 })
      await apiGet('/something-else' as any)
      expect(client.get).toHaveBeenCalledWith('/something-else', undefined)
    })
  })

  describe('apiPost', () => {
    it('strips prefix and passes data + config through', async () => {
      ;(client.post as ReturnType<typeof vi.fn>).mockResolvedValue({ data: null, status: 201 })
      const body = { name: 'test' }
      await apiPost('/api/v1/superadmin/directories' as any, body as any)
      expect(client.post).toHaveBeenCalledWith('/superadmin/directories', body, undefined)
    })

    it('passes undefined data when no body provided', async () => {
      ;(client.post as ReturnType<typeof vi.fn>).mockResolvedValue({ data: null, status: 200 })
      await apiPost('/api/v1/superadmin/directories/test-noop' as any)
      expect(client.post).toHaveBeenCalledWith('/superadmin/directories/test-noop', undefined, undefined)
    })
  })

  describe('apiPut', () => {
    it('strips prefix and passes data through', async () => {
      ;(client.put as ReturnType<typeof vi.fn>).mockResolvedValue({ data: null, status: 200 })
      const body = { name: 'updated' }
      await apiPut('/api/v1/superadmin/directories/{id}' as any, body as any)
      expect(client.put).toHaveBeenCalledWith('/superadmin/directories/{id}', body, undefined)
    })
  })

  describe('apiDelete', () => {
    it('strips prefix and passes config through', async () => {
      ;(client.delete as ReturnType<typeof vi.fn>).mockResolvedValue({ data: null, status: 204 })
      await apiDelete('/api/v1/superadmin/directories/{id}' as any)
      expect(client.delete).toHaveBeenCalledWith('/superadmin/directories/{id}', undefined)
    })
  })

  describe('error propagation', () => {
    it('rejects when client.get rejects', async () => {
      const err = new Error('network')
      ;(client.get as ReturnType<typeof vi.fn>).mockRejectedValue(err)
      await expect(apiGet('/api/v1/superadmin/directories' as any)).rejects.toThrow('network')
    })
  })
})
