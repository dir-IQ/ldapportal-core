// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const discoverDirectory = (directoryId, options = {}) =>
    client.post(`/superadmin/directories/${directoryId}/discover`, options)

export const commitDiscovery = (directoryId, data) =>
    client.post(`/superadmin/directories/${directoryId}/discover/commit`, data)
