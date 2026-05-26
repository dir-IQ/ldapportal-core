// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const getAuditLog = (params) =>
  client.get('/audit', { params })

export const getEntryTimeline = (directoryId, targetDn, params = {}) =>
  client.get('/audit', { params: { directoryId, targetDn, ...params } })
