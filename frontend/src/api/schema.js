// SPDX-License-Identifier: Apache-2.0
import client from './client'

const base = (dirId) => `/directories/${dirId}/schema`

export const listObjectClasses  = (dirId)       => client.get(`${base(dirId)}/object-classes`)
export const getObjectClass     = (dirId, name) => client.get(`${base(dirId)}/object-classes/${encodeURIComponent(name)}`)
export const getObjectClassesBulk = (dirId, names) => client.get(`${base(dirId)}/object-classes/bulk`, { params: { names: names.join(',') } })
export const listAttributeTypes = (dirId)       => client.get(`${base(dirId)}/attribute-types`)
export const getAttributeType   = (dirId, name) => client.get(`${base(dirId)}/attribute-types/${encodeURIComponent(name)}`)
