// SPDX-License-Identifier: Apache-2.0
import type { SyncSetDefaults } from '@/api/sync'

/** Shown in a field that falls back to a default when left blank. */
export const DEFAULT_PLACEHOLDER = 'Leave blank for the default (see ⓘ)'

const DIRECTORY_TYPE_LABELS: Record<string, string> = {
  GENERIC: 'Generic LDAP',
  ACTIVE_DIRECTORY: 'Active Directory',
  OPENLDAP: 'OpenLDAP',
  IBM_DIRECTORY_SERVER: 'IBM Directory Server',
  ORACLE_UNIFIED_DIRECTORY: 'Oracle Unified Directory',
  ENTRA_ID: 'Microsoft Entra ID',
}

/** "entryUUID (OpenLDAP, Generic LDAP); objectGUID (Active Directory)": types grouped by attribute. */
function identityDefaults(byType: Record<string, string>): string {
  const byAttribute = new Map<string, string[]>()
  for (const [type, attribute] of Object.entries(byType)) {
    const types = byAttribute.get(attribute) ?? []
    types.push(DIRECTORY_TYPE_LABELS[type] ?? type)
    byAttribute.set(attribute, types)
  }
  return Array.from(byAttribute, ([attribute, types]) => `${attribute} (${types.join(', ')})`).join('; ')
}

function duration(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600} hour${seconds === 3600 ? '' : 's'}`
  if (seconds % 60 === 0) return `${seconds / 60} minute${seconds === 60 ? '' : 's'}`
  return `${seconds} seconds`
}

/**
 * The sync set editor's info tips. Defaults come from the backend, so a tip
 * can't drift from what the engine does; until they load, the tips say less.
 */
export function syncSetHelp(defaults: SyncSetDefaults | null) {
  const or = (text: (d: SyncSetDefaults) => string, fallback: string): string =>
    defaults ? text(defaults) : fallback

  return {
    name: 'A label for this sync set, shown in the inventory, run history and logs. It doesn\'t affect what\'s synced.',

    identityKey:
      'Source attribute whose value identifies each entry for as long as it\'s synced. The sync matches a source '
      + 'entry to its target entry by this value, so it follows the entry through renames and moves. Use an '
      + 'attribute the server assigns and never changes; avoid ones that can change, such as mail. Changing it '
      + 'later makes every entry look new.\n\n'
      + or((d) => `Default, by source directory type: ${identityDefaults(d.identityKeyByDirectoryType)}.`,
        'Default: the source directory type\'s own identity attribute.'),

    sourceBaseDn:
      'Base DN under the source directory to read entries from, together with the source scope. It must be under '
      + 'one of the source directory\'s base DNs.',

    targetBaseDn:
      'Base DN under the target directory where entries are placed. Each entry keeps the part of its DN below '
      + 'the source base DN. For example, with source base DN ou=people,o=src and target base DN '
      + 'ou=Users,o=dst, uid=jo,ou=people,o=src is placed at uid=jo,ou=Users,o=dst.\n\n'
      + 'Default: the same DNs as on the source. That needs the source base DN to be under one of the target '
      + 'directory\'s base DNs.',

    applicabilityFilter:
      'LDAP filter (RFC 4515) choosing which entries under the source base DN belong to this set. Only matching '
      + 'entries are synced. An entry that stops matching leaves the set, and the delete policy decides what '
      + 'happens to its target entry.\n\n'
      + 'A long filter can be laid out over several lines: line breaks, and the spaces around them, are removed '
      + 'when it\'s saved.\n\n'
      + 'Default: every entry under the source base DN.',

    referenceAttributes:
      'Attributes whose values are the DNs of other entries, such as a group\'s member or a user\'s manager. A '
      + 'DN that\'s right on the source can be wrong on the target, because the entry it points to may be placed '
      + 'at a different DN there. So for each of these attributes the sync:\n'
      + '• Translates each value to the DN the entry it points to was synced to. It looks this up in the sync\'s '
      + 'own records, across every sync link, not in the target directory.\n'
      + '• Leaves out a value whose entry isn\'t synced yet, unless the target already has it: then it\'s kept, '
      + 'so a member isn\'t removed just because its entry isn\'t synced (one whose entry the sync deleted is '
      + 'removed). If none of an attribute\'s values can be translated, the target\'s current value is kept. The '
      + 'preview says so either way.\n'
      + '• Re-syncs the entries that point to an entry when that entry is synced, moved or renamed, so their '
      + 'references stay current.\n'
      + 'Their values are compared as DNs, so a difference in case or spacing alone isn\'t a change.\n\n'
      + or((d) => `Default: ${d.referenceAttributes.join(', ')}.`, 'Default: the standard DN-valued attributes.')
      + ' A list you enter replaces the default list; it doesn\'t add to it.',

    sourceAnchorAttribute:
      'Target attribute where the sync writes each entry\'s source identity (its identity key value, such as its '
      + 'entryUUID). The first time an entry is synced, the sync searches the target for that value. A target '
      + 'entry that already carries it is adopted instead of a duplicate being added: it\'s updated to match the '
      + 'source, and moved to its planned DN if it\'s elsewhere. If more than one target entry carries the value, '
      + 'or an entry without it already sits at the planned DN, the entry is held for review.\n\n'
      + 'Default: none. Entries are matched by their planned DN only: an entry already at that DN is updated to '
      + 'match the source, and nothing extra is written to the target.\n\n'
      + 'Set it when target entries may already carry the source identity (written by an earlier sync or '
      + 'migration), or may sit at different DNs on the target, so matching by DN alone would duplicate them. '
      + 'Example: employeeNumber. The attribute must be allowed on the target entries\' object classes and not '
      + 'used for anything else, because the sync overwrites it.',

    reconcileInterval:
      'How often this set is fully reconciled: the whole source is read and compared with the target, and '
      + 'whatever has drifted is corrected. Between reconciles, changes are picked up as they happen (see the '
      + 'link\'s capture mode).\n\n'
      + or((d) => `Default: ${d.reconcileIntervalSeconds} seconds (${duration(d.reconcileIntervalSeconds)}), `
        + 'this server\'s setting.', 'Default: this server\'s setting.'),

    deletePolicy:
      'What happens to the target entry when an entry leaves the set (deleted on the source, moved out of '
      + 'scope, or no longer matching the filter). Delete removes it; Review holds it for an operator to '
      + 'approve or dismiss.\n\nDefault: Review.',

    sourceScope:
      'How far below the source base DN to read: Subtree (every entry below it), One level (its immediate '
      + 'children), or Base (the base entry only).\n\nDefault: Subtree.',

    loggingLevel:
      'How much of each run the run history keeps.\n'
      + '• Summary: counts and failure reasons only.\n'
      + '• Detailed: also every changed or flagged entry\'s DN, downloadable as CSV from the run\'s details. A '
      + 'large run stores one row per entry.\n'
      + 'Applies to runs that start after it\'s changed.\n\nDefault: Summary.',
  }
}
