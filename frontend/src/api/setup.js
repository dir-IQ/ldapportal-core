// SPDX-License-Identifier: Apache-2.0
import client from './client'

/** Public — no auth required */
export const getSetupStatus = () =>
  client.get('/auth/setup-status')
