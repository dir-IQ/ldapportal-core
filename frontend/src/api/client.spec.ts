// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import client from './client'

/**
 * The 401 interceptor hard-navigates to /login so an expired session
 * anywhere in the app lands on the sign-in page. Session probes opt out via
 * `skipAuthRedirect`, because for them a 401 is a normal answer — and the
 * hard navigation would otherwise race (and beat) the router guard's own
 * redirect, which is the one that preserves the deep link.
 */
describe('api client 401 interceptor', () => {
  // axios keeps registered interceptors on `handlers`; the single response
  // interceptor is the one under test.
  const rejected = (client.interceptors.response as unknown as {
    handlers: Array<{ rejected: (err: unknown) => Promise<never> }>
  }).handlers[0].rejected

  const originalLocation = window.location
  let location: { pathname: string; href: string }

  beforeEach(() => {
    location = { pathname: '/dashboard', href: '/dashboard' }
    Object.defineProperty(window, 'location', { value: location, writable: true, configurable: true })
  })
  afterEach(() => {
    Object.defineProperty(window, 'location', { value: originalLocation, writable: true, configurable: true })
  })

  const unauthorized = (config: Record<string, unknown> = {}) =>
    Object.assign(new Error('401'), { response: { status: 401 }, config })

  it('hard-redirects to /login on a 401 from a protected page', async () => {
    await expect(rejected(unauthorized())).rejects.toThrow('401')
    expect(location.href).toBe('/login')
  })

  it('leaves navigation alone when the request opted out with skipAuthRedirect', async () => {
    await expect(rejected(unauthorized({ skipAuthRedirect: true }))).rejects.toThrow('401')
    expect(location.href).toBe('/dashboard')
  })

  it('never redirects away from the login page itself', async () => {
    location.pathname = '/login'
    location.href = '/login'
    await expect(rejected(unauthorized())).rejects.toThrow('401')
    expect(location.href).toBe('/login')
  })

  it('does nothing special for non-401 errors', async () => {
    const err = Object.assign(new Error('500'), { response: { status: 500 }, config: {} })
    await expect(rejected(err)).rejects.toThrow('500')
    expect(location.href).toBe('/dashboard')
  })
})
