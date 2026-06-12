// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import {
  loginErrorMessage,
  isSystemError,
  INVALID_CREDENTIALS_MESSAGE,
  SERVICE_UNAVAILABLE_MESSAGE,
} from './loginError'

describe('loginErrorMessage', () => {
  it('maps auth rejections (401/403) to the generic credentials message', () => {
    expect(loginErrorMessage({ response: { status: 401 } })).toBe(INVALID_CREDENTIALS_MESSAGE)
    expect(loginErrorMessage({ response: { status: 403 } })).toBe(INVALID_CREDENTIALS_MESSAGE)
  })

  it('maps 5xx (LDAP/DB unreachable, server error) to the service-unavailable message', () => {
    for (const status of [500, 502, 503, 504]) {
      expect(loginErrorMessage({ response: { status } })).toBe(SERVICE_UNAVAILABLE_MESSAGE)
    }
  })

  it('maps no-response errors (network/timeout/backend down) to service-unavailable', () => {
    expect(loginErrorMessage(new Error('Network Error'))).toBe(SERVICE_UNAVAILABLE_MESSAGE)
    expect(loginErrorMessage({})).toBe(SERVICE_UNAVAILABLE_MESSAGE)
    expect(loginErrorMessage({ response: undefined })).toBe(SERVICE_UNAVAILABLE_MESSAGE)
  })

  it('never echoes the backend body, so internal detail cannot leak', () => {
    const err = { response: { status: 502, data: { detail: 'LDAP server unreachable: ldap1.internal:389' } } }
    expect(loginErrorMessage(err)).toBe(SERVICE_UNAVAILABLE_MESSAGE)
    expect(loginErrorMessage(err)).not.toContain('ldap1')
  })

  it('isSystemError distinguishes transport/server failures from auth rejections', () => {
    expect(isSystemError({ response: { status: 401 } })).toBe(false)
    expect(isSystemError({ response: { status: 500 } })).toBe(true)
    expect(isSystemError(new Error('boom'))).toBe(true)
  })
})
