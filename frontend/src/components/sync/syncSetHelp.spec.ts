// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { syncSetHelp } from './syncSetHelp'

const defaults = {
  identityKeyByDirectoryType: {
    OPENLDAP: 'entryUUID', ORACLE_UNIFIED_DIRECTORY: 'entryUUID', ACTIVE_DIRECTORY: 'objectGUID', ENTRA_ID: 'id',
  },
  referenceAttributes: ['member', 'uniqueMember', 'manager', 'owner', 'secDN'],
  reconcileIntervalSeconds: 3600,
  excludedAttributes: ['userPassword'],
}

describe('syncSetHelp', () => {
  it('lists the backend defaults in the tips', () => {
    const help = syncSetHelp(defaults)
    expect(help.identityKey).toContain(
      'Default, by source directory type: entryUUID (OpenLDAP, Oracle Unified Directory); '
      + 'objectGUID (Active Directory); id (Microsoft Entra ID).')
    expect(help.referenceAttributes).toContain('Default: member, uniqueMember, manager, owner, secDN.')
    expect(help.reconcileInterval).toContain('Default: 3600 seconds (1 hour)')
    expect(help.sourceAnchorAttribute).toContain('Default: none.')
    expect(help.sourceAnchorAttribute).toContain('Example: employeeNumber.')
  })

  it('still reads sensibly before the defaults load', () => {
    const help = syncSetHelp(null)
    expect(help.referenceAttributes).toContain('Default: the standard DN-valued attributes.')
    expect(help.reconcileInterval).toContain('Default: this server\'s setting.')
  })
})
