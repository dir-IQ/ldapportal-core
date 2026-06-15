// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const testEntraConnection = (directoryId, data) =>
    client.post(`/superadmin/entra/${directoryId}/test-connection`, data)

export const getEntraSyncStatus = (directoryId) =>
    client.get(`/superadmin/entra/${directoryId}/sync-status`)

export const triggerEntraSync = (directoryId, full = false) =>
    client.post(`/superadmin/entra/${directoryId}/sync?full=${full}`)

export const listEntraUsers = (directoryId) =>
    client.get(`/superadmin/entra/${directoryId}/users`)

export const listEntraGroups = (directoryId) =>
    client.get(`/superadmin/entra/${directoryId}/groups`)
