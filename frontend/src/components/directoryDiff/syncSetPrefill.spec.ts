// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { prefillFromSyncSet, unsupportedReason } from './syncSetPrefill'
import type { SyncLink, SyncSet } from '@/api/sync'

const LINK = { id: 'l', displayName: 'L', sourceDirId: 'src', targetDirId: 'dst' } as SyncLink

function set(overrides: Partial<SyncSet> = {}): SyncSet {
  return {
    id: 's', linkId: 'l', name: 'S', objectScopeBaseDn: 'ou=people,dc=a', objectScope: 'SUB',
    targetBaseDn: null, applicabilityFilter: null, sourceAnchorAttribute: null, transformRules: null,
    excludedAttributes: null, ...overrides,
  } as SyncSet
}

describe('prefillFromSyncSet', () => {
  it('compares the source scope with where sync places it', () => {
    const p = prefillFromSyncSet(set({ objectScope: 'ONE', targetBaseDn: 'ou=Users,dc=b',
      applicabilityFilter: '(employeeType=staff)' }), LINK, [])
    expect(p.a).toEqual({ directoryId: 'src', baseDn: 'ou=people,dc=a', scope: 'ONE', filter: '(employeeType=staff)' })
    expect(p.b).toEqual({ directoryId: 'dst', baseDn: 'ou=Users,dc=b', scope: 'ONE', filter: '' })
    expect(p.notes.join(' ')).toContain('source base swapped for the target base')
  })

  it('uses the source base on the target for identity placement', () => {
    const p = prefillFromSyncSet(set(), LINK, [])
    expect(p.b.baseDn).toBe('ou=people,dc=a')
    expect(p.notes.join(' ')).toContain('same DN')
    expect(p.notes.join(' ')).toContain('show under Only in B')
  })

  it('ignores the engine defaults unless the set has its own exclusions', () => {
    expect(prefillFromSyncSet(set(), LINK, ['userPassword']).ignore).toEqual(['userPassword'])
    expect(prefillFromSyncSet(set({ excludedAttributes: ['description'] }), LINK, ['userPassword']).ignore)
      .toEqual(['description'])
    expect(prefillFromSyncSet(set({ excludedAttributes: [] }), LINK, ['userPassword']).ignore).toEqual([])
  })

  it('ignores renamed and rewritten attributes and the source anchor, once each', () => {
    const p = prefillFromSyncSet(set({
      sourceAnchorAttribute: 'employeeNumber',
      excludedAttributes: ['mail'],
      transformRules: [
        { sourceAttr: 'mail', targetAttr: 'email', valueTemplate: null },
        { sourceAttr: 'cn', targetAttr: null, valueTemplate: '{cn} (synced)' },
      ],
    }), LINK, [])
    expect(p.ignore).toEqual(['mail', 'email', 'cn', 'employeeNumber'])
    expect(p.b.filter).toBe('(employeeNumber=*)')
    expect(p.notes.join(' ')).toContain('Ignoring mail, email, cn: the set renames or rewrites them')
  })
})

describe('unsupportedReason', () => {
  it('turns away sets the diff can’t express', () => {
    expect(unsupportedReason(set({ objectScope: 'BASE' }))).toContain('base scope')
    expect(unsupportedReason(set({ objectScopeBaseDn: null }))).toContain('no source base DN')
    expect(unsupportedReason(set())).toBeNull()
  })
})
