// SPDX-License-Identifier: Apache-2.0
import client from './client'

const base = (dirId) => `/superadmin/directories/${dirId}/browse`

export const browse = (dirId, dn) =>
  client.get(base(dirId), { params: { dn: dn || undefined } })

export const directoryBrowse = (dirId, dn) =>
  client.get(`/directories/${dirId}/browse`, { params: { dn: dn || undefined } })

export const createEntry = (dirId, data) =>
  client.post(base(dirId), data)

export const updateEntry = (dirId, dn, data) =>
  client.put(base(dirId), data, { params: { dn } })

export const deleteEntry = (dirId, dn, recursive = false) =>
  client.delete(base(dirId), { params: { dn, recursive } })

export const moveEntry = (dirId, dn, newParentDn) =>
  client.post(`${base(dirId)}/move`, { newParentDn }, { params: { dn } })

export const renameEntry = (dirId, dn, newRdn) =>
  client.post(`${base(dirId)}/rename`, { newRdn }, { params: { dn } })

export const searchEntries = (dirId, params) =>
  client.get(`${base(dirId)}/search`, { params })

export const exportLdif = (dirId, dn, scope = 'base') =>
  client.get(`${base(dirId)}/export/ldif`, {
    params: { dn, scope },
    responseType: 'blob',
  })

export const importLdif = (dirId, file, conflictHandling = 'SKIP', dryRun = false) => {
  const formData = new FormData()
  formData.append('file', file)
  return client.post(`${base(dirId)}/import/ldif`, formData, {
    params: { conflictHandling, dryRun },
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export const checkIntegrity = (dirId, baseDn, checks) =>
  client.post(`${base(dirId)}/integrity-check`, null, {
    params: { baseDn: baseDn || undefined, checks: checks.join(',') },
  })

export const browseObjectClasses = (dirId) =>
  client.get(`${base(dirId)}/schema/object-classes`)

export const browseObjectClassesBulk = (dirId, names) =>
  client.get(`${base(dirId)}/schema/object-classes/bulk`, {
    params: { names: names.join(',') },
  })
