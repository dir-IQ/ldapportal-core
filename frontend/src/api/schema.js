// SPDX-License-Identifier: Apache-2.0
import client from './client'

const base = (dirId) => `/directories/${dirId}/schema`

export const listObjectClasses  = (dirId)       => client.get(`${base(dirId)}/object-classes`)
export const getObjectClass     = (dirId, name) => client.get(`${base(dirId)}/object-classes/${encodeURIComponent(name)}`)
export const getObjectClassesBulk = (dirId, names) => client.get(`${base(dirId)}/object-classes/bulk`, { params: { names: names.join(',') } })
export const listAttributeTypes = (dirId)       => client.get(`${base(dirId)}/attribute-types`)
export const getAttributeType   = (dirId, name) => client.get(`${base(dirId)}/attribute-types/${encodeURIComponent(name)}`)

// ── Superadmin: schema updates via LDIF (preview → apply) + export ───────────
// These target the superadmin-scoped write surface, gated server-side by
// MANAGE_SCHEMA. v1 supports OpenLDAP (cn=config) and OpenDJ/OUD (cn=schema).
const superadminBase = (dirId) => `/superadmin/directories/${dirId}/schema`

export const previewSchemaLdif = (dirId, file) => {
  const formData = new FormData()
  formData.append('file', file)
  return client.post(`${superadminBase(dirId)}/import/preview`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// configBindDn/configPassword are OpenLDAP-only (cn=config admin bind); OpenDJ
// writes schema with the directory's normal bind, so both are omitted there.
export const applySchemaPreview = (dirId, previewId, configBindDn, configPassword) =>
  client.post(`${superadminBase(dirId)}/import/preview/${previewId}/apply`, {
    configBindDn: configBindDn || undefined,
    configPassword: configPassword || undefined,
  })

export const exportSchema = (dirId) =>
  client.get(`${superadminBase(dirId)}/export`, { responseType: 'blob' })
