// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { BASE_PATH, normalizeBasePath, withBase } from './basePath'

describe('basePath', () => {
  it('normalises every operator spelling to /segment/', () => {
    expect(normalizeBasePath(undefined)).toBe('/')
    expect(normalizeBasePath('')).toBe('/')
    expect(normalizeBasePath('/')).toBe('/')
    expect(normalizeBasePath('idm')).toBe('/idm/')
    expect(normalizeBasePath('/idm')).toBe('/idm/')
    expect(normalizeBasePath('/idm/')).toBe('/idm/')
    expect(normalizeBasePath(' //idm// ')).toBe('/idm/')
    expect(normalizeBasePath('/idm/portal')).toBe('/idm/portal/')
  })

  it('joins without doubling or dropping slashes', () => {
    expect(withBase('api/v1', '/')).toBe('/api/v1')
    expect(withBase('/api/v1', '/')).toBe('/api/v1')
    expect(withBase('api/v1', '/idm/')).toBe('/idm/api/v1')
    expect(withBase('/login', '/idm/')).toBe('/idm/login')
    expect(withBase('', '/idm/')).toBe('/idm/')
  })

  it('defaults to the root in the test build', () => {
    expect(BASE_PATH).toBe('/')
    expect(withBase('api/v1')).toBe('/api/v1')
  })
})
