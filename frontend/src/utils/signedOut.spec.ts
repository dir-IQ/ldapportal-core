// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach } from 'vitest'
import { isSignedOut, markSignedOut, clearSignedOut } from './signedOut'

describe('signedOut', () => {
  beforeEach(() => clearSignedOut())

  it('remembers a logout until it is cleared', () => {
    expect(isSignedOut()).toBe(false)
    markSignedOut()
    expect(isSignedOut()).toBe(true)
    clearSignedOut()
    expect(isSignedOut()).toBe(false)
  })

  it('is a session cookie: no expiry, so it ends with the browser', () => {
    const writes: string[] = []
    // jsdom defines document.cookie on a prototype; find it to wrap its setter.
    let proto: object | null = document
    let desc: PropertyDescriptor | undefined
    while (proto && !(desc = Object.getOwnPropertyDescriptor(proto, 'cookie'))) proto = Object.getPrototypeOf(proto)
    if (!desc) throw new Error('no document.cookie descriptor')
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => desc!.get!.call(document),
      set: (v: string) => { writes.push(v); desc!.set!.call(document, v) },
    })
    try {
      markSignedOut()
    } finally {
      delete (document as unknown as Record<string, unknown>).cookie
    }
    expect(writes[0]).toMatch(/^ldapportal-signed-out=1; Path=\//)
    expect(writes[0]).not.toMatch(/Max-Age|Expires/i)
  })
})
