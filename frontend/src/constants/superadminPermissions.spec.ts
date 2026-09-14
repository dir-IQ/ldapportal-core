// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import {
  SUPERADMIN_PERMISSION_AREAS, SUPERADMIN_PERMISSION_LABELS, SUPERADMIN_OWNER_KEY,
  areaTier, areaTiers, areaTopTier, areaKeysForTier, areaTierLabel,
} from './superadminPermissions'

describe('superadmin permission areas', () => {
  it('labels every key an area references', () => {
    for (const area of SUPERADMIN_PERMISSION_AREAS) {
      for (const key of [area.view, area.manage]) {
        if (key) expect(SUPERADMIN_PERMISSION_LABELS[key], key).toBeTruthy()
      }
    }
  })

  it('does not list the owner key as an area (it is the Owner toggle)', () => {
    expect(SUPERADMIN_PERMISSION_AREAS.some(a => a.view === SUPERADMIN_OWNER_KEY || a.manage === SUPERADMIN_OWNER_KEY)).toBe(false)
  })

  it('offers only the tiers an area has keys for', () => {
    const both = SUPERADMIN_PERMISSION_AREAS.find(a => a.id === 'directories')!
    const manageOnly = SUPERADMIN_PERMISSION_AREAS.find(a => a.id === 'schema')!
    const viewOnly = SUPERADMIN_PERMISSION_AREAS.find(a => a.id === 'license')!
    expect(areaTiers(both)).toEqual(['none', 'view', 'manage'])
    expect(areaTiers(manageOnly)).toEqual(['none', 'manage'])
    expect(areaTiers(viewOnly)).toEqual(['none', 'view'])
    expect(areaTopTier(both)).toBe('manage')
    expect(areaTopTier(viewOnly)).toBe('view')
  })

  it('resolves the tier from granted keys, manage winning over view', () => {
    const dirs = SUPERADMIN_PERMISSION_AREAS.find(a => a.id === 'directories')!
    expect(areaTier(dirs, new Set())).toBe('none')
    expect(areaTier(dirs, new Set(['superadmin.view_directories']))).toBe('view')
    expect(areaTier(dirs, new Set(['superadmin.manage_directories']))).toBe('manage')
    expect(areaTier(dirs, new Set(['superadmin.view_directories', 'superadmin.manage_directories']))).toBe('manage')
  })

  it('labels the lowest tier per area: None by default, Read-only for directory entries', () => {
    const dirs = SUPERADMIN_PERMISSION_AREAS.find(a => a.id === 'directories')!
    const data = SUPERADMIN_PERMISSION_AREAS.find(a => a.id === 'directory_data')!
    expect(areaTierLabel(dirs, 'none')).toBe('None')
    expect(areaTierLabel(data, 'none')).toBe('Read-only')
    expect(areaTierLabel(data, 'manage')).toBe('Manage')
    expect(areaTiers(data)).toEqual(['none', 'manage'])
  })

  it('stores exactly one key per area tier', () => {
    const dirs = SUPERADMIN_PERMISSION_AREAS.find(a => a.id === 'directories')!
    expect(areaKeysForTier(dirs, 'none')).toEqual([])
    expect(areaKeysForTier(dirs, 'view')).toEqual(['superadmin.view_directories'])
    expect(areaKeysForTier(dirs, 'manage')).toEqual(['superadmin.manage_directories'])
    const license = SUPERADMIN_PERMISSION_AREAS.find(a => a.id === 'license')!
    expect(areaKeysForTier(license, 'manage')).toEqual([])
  })
})
