// SPDX-License-Identifier: Apache-2.0
import client from './client'

const base = (dirId) => `/directories/${dirId}/csv-templates`

export const listCsvTemplates = (dirId) =>
  client.get(base(dirId))

export const getCsvTemplate = (dirId, templateId) =>
  client.get(`${base(dirId)}/${templateId}`)

export const createCsvTemplate = (dirId, data) =>
  client.post(base(dirId), data)

export const updateCsvTemplate = (dirId, templateId, data) =>
  client.put(`${base(dirId)}/${templateId}`, data)

export const deleteCsvTemplate = (dirId, templateId) =>
  client.delete(`${base(dirId)}/${templateId}`)

export const previewCsv = (dirId, file, request) => {
  const form = new FormData()
  form.append('file', file)
  form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }))
  return client.post(`/directories/${dirId}/users/import/preview`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export const importCsv = (dirId, file, request) => {
  const form = new FormData()
  form.append('file', file)
  form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }))
  return client.post(`/directories/${dirId}/users/import`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export const exportCsv = (dirId, params) =>
  client.get(`/directories/${dirId}/users/export`, { params, responseType: 'blob' })

// ── Group bulk import/export ─────────────────────────────────────────────────

export const previewGroupCsv = (dirId, file, request, memberAttribute = 'member', objectClass = 'groupOfNames') => {
  const form = new FormData()
  form.append('file', file)
  form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }))
  return client.post(`/directories/${dirId}/groups/import/preview`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    // Mirrors the import endpoint so the preview's schema-driven
    // required-attribute check is run against the same object class.
    params: { memberAttribute, objectClass }
  })
}

export const importGroupCsv = (dirId, file, request, memberAttribute = 'member', objectClass = 'groupOfNames') => {
  const form = new FormData()
  form.append('file', file)
  form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }))
  return client.post(`/directories/${dirId}/groups/import`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    params: { memberAttribute, objectClass }
  })
}

export const exportGroupCsv = (dirId, params) =>
  client.get(`/directories/${dirId}/groups/export`, { params, responseType: 'blob' })

// ── Container helpers (used by bulk-import "create missing parent DN") ──────

export const checkContainerExists = (dirId, dn) =>
  client.get(`/directories/${dirId}/container/exists`, { params: { dn } })

export const createContainer = (dirId, dn) =>
  client.post(`/directories/${dirId}/container`, { dn })
