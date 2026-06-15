// SPDX-License-Identifier: Apache-2.0
/**
 * Shared formatting utilities for report table rendering.
 */

/**
 * Format an ISO timestamp as a friendly relative or absolute date.
 * - < 1 minute: "just now"
 * - < 1 hour: "X min ago"
 * - < 24 hours: "X hours ago"
 * - < 7 days: "X days ago"
 * - otherwise: "Mar 24, 2026"
 */
export function formatRelativeDate(iso) {
  if (!iso) return ''
  const date = new Date(iso)
  if (isNaN(date.getTime())) return iso
  const now = Date.now()
  const diffMs = now - date.getTime()

  if (diffMs < 0) {
    // Future date — show absolute
    return formatAbsoluteDate(date)
  }

  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return 'just now'
  if (diffMin < 60) return `${diffMin} min ago`

  const diffHours = Math.floor(diffMin / 60)
  if (diffHours < 24) return `${diffHours}h ago`

  const diffDays = Math.floor(diffHours / 24)
  if (diffDays < 7) return `${diffDays}d ago`

  return formatAbsoluteDate(date)
}

function formatAbsoluteDate(date) {
  const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
  return `${months[date.getMonth()]} ${date.getDate()}, ${date.getFullYear()}`
}

/**
 * Format an ISO timestamp as a full readable date for tooltips.
 */
export function formatFullDate(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleString()
}

/**
 * Extract the first RDN value from a DN for compact display.
 * e.g. "cn=John Smith,ou=People,dc=example,dc=com" → "John Smith"
 */
export function truncateDn(dn) {
  if (!dn || !dn.includes('=')) return dn || ''
  const firstEquals = dn.indexOf('=')
  const firstComma = dn.indexOf(',')
  if (firstComma === -1) return dn.substring(firstEquals + 1)
  return dn.substring(firstEquals + 1, firstComma)
}

/**
 * Map of LDAP attribute names → friendly display labels.
 */
const LDAP_COLUMN_MAP = {
  dn: 'DN',
  cn: 'Name',
  uid: 'User ID',
  mail: 'Email',
  sn: 'Last Name',
  givenName: 'First Name',
  displayName: 'Display Name',
  telephoneNumber: 'Phone',
  title: 'Title',
  description: 'Description',
  objectClass: 'Object Class',
  createTimestamp: 'Created',
  modifyTimestamp: 'Modified',
  employeeNumber: 'Employee #',
  employeeType: 'Employee Type',
  departmentNumber: 'Dept #',
  o: 'Organization',
  ou: 'Org Unit',
  l: 'Location',
  st: 'State',
  postalCode: 'Postal Code',
  street: 'Street',
  userPassword: 'Password',
  memberOf: 'Member Of',
  manager: 'Manager',
  loginShell: 'Login Shell',
  homeDirectory: 'Home Dir',
  uidNumber: 'UID #',
  gidNumber: 'GID #',
  userAccountControl: 'UAC',
  sAMAccountName: 'SAM Account',
  userPrincipalName: 'UPN',
  pwdAccountLockedTime: 'Locked Since',
  nsAccountLock: 'Account Lock',
}

/**
 * Humanize a column name: use LDAP mapping if available,
 * otherwise leave as-is (backend already uses friendly names for structured reports).
 */
export function friendlyColumnName(col) {
  return LDAP_COLUMN_MAP[col] || col
}

/**
 * Check if a value looks like an ISO timestamp.
 */
export function looksLikeTimestamp(val) {
  if (!val || typeof val !== 'string') return false
  // ISO 8601 patterns: 2026-03-24T14:32:01... or LDAP generalized time: 20260324...
  return /^\d{4}-\d{2}-\d{2}T/.test(val)
}

/**
 * Check if a value looks like a DN.
 */
export function looksLikeDn(val) {
  if (!val || typeof val !== 'string') return false
  return /^[a-zA-Z]+=.+,.+=/.test(val)
}

// ── Badge class helpers ──────────────────────────────────────────────────────

export function statusBadgeClass(status) {
  switch (status) {
    case 'OPEN':         return 'bg-red-100 text-red-700'
    case 'RESOLVED':     return 'bg-green-100 text-green-700'
    case 'EXEMPTED':     return 'bg-amber-100 text-amber-700'
    case 'ACKNOWLEDGED': return 'bg-blue-100 text-blue-700'
    case 'ACTIVE':       return 'bg-blue-100 text-blue-700'
    case 'CLOSED':       return 'bg-green-100 text-green-700'
    case 'UPCOMING':     return 'bg-gray-100 text-gray-600'
    case 'EXPIRED':      return 'bg-red-100 text-red-700'
    case 'CANCELLED':    return 'bg-amber-100 text-amber-700'
    default:             return 'bg-gray-100 text-gray-700'
  }
}

export function severityBadgeClass(severity) {
  switch (severity) {
    case 'CRITICAL': return 'bg-red-100 text-red-700'
    case 'HIGH':     return 'bg-orange-100 text-orange-700'
    case 'MEDIUM':   return 'bg-yellow-100 text-yellow-700'
    case 'LOW':      return 'bg-blue-100 text-blue-700'
    default:         return 'bg-gray-100 text-gray-700'
  }
}

export function decisionBadgeClass(decision) {
  switch (decision) {
    case 'CONFIRM':  return 'bg-green-100 text-green-700'
    case 'REVOKE':   return 'bg-red-100 text-red-700'
    case 'PENDING':  return 'bg-amber-100 text-amber-700'
    default:         return 'bg-gray-100 text-gray-700'
  }
}

export function actionBadgeClass(action) {
  if (!action) return 'bg-gray-100 text-gray-600'
  const a = action.toUpperCase()
  if (a.startsWith('USER_'))     return 'bg-blue-100 text-blue-700'
  if (a.startsWith('GROUP_'))    return 'bg-purple-100 text-purple-700'
  if (a.startsWith('ENTRY_'))    return 'bg-sky-100 text-sky-700'
  if (a.startsWith('APPROVAL_')) return 'bg-amber-100 text-amber-700'
  if (a.startsWith('CAMPAIGN_') || a.startsWith('REVIEW_')) return 'bg-green-100 text-green-700'
  if (a.startsWith('SOD_'))      return 'bg-red-100 text-red-700'
  if (a.startsWith('PLAYBOOK_')) return 'bg-indigo-100 text-indigo-700'
  if (a.startsWith('HR_'))       return 'bg-teal-100 text-teal-700'
  if (a === 'LDAP_CHANGE' || a === 'LDIF_IMPORT') return 'bg-violet-100 text-violet-700'
  if (a === 'PASSWORD_RESET')    return 'bg-blue-100 text-blue-700'
  if (a === 'INTEGRITY_CHECK')   return 'bg-sky-100 text-sky-700'
  return 'bg-gray-100 text-gray-600'
}

export function integrityTypeBadgeClass(type) {
  switch (type) {
    case 'BROKEN_MEMBER':  return 'bg-red-100 text-red-700'
    case 'ORPHANED_ENTRY': return 'bg-amber-100 text-amber-700'
    case 'EMPTY_GROUP':    return 'bg-blue-100 text-blue-700'
    default:               return 'bg-gray-100 text-gray-600'
  }
}

export function sourceBadgeClass(source) {
  switch (source) {
    case 'Internal':  return 'bg-blue-100 text-blue-700'
    case 'Changelog': return 'bg-purple-100 text-purple-700'
    default:          return 'bg-gray-100 text-gray-600'
  }
}

/**
 * Humanize an enum-style value: USER_DELETE → "User Delete"
 */
export function humanizeEnum(val) {
  if (!val) return ''
  return val.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase()).replace(/\b(\w)(\w*)/g, (m, first, rest) => first + rest.toLowerCase())
}

/**
 * Friendly label for integrity issue types.
 */
export function friendlyIntegrityType(type) {
  switch (type) {
    case 'BROKEN_MEMBER':  return 'Broken Member'
    case 'ORPHANED_ENTRY': return 'Orphaned Entry'
    case 'EMPTY_GROUP':    return 'Empty Group'
    default:               return type
  }
}

/**
 * Set of column names that should be rendered as badges.
 */
export const BADGE_COLUMNS = new Set(['Status', 'Severity', 'Decision', 'Action', 'Source', 'type'])

/**
 * Set of column names that contain date values (from structured reports).
 */
export const DATE_COLUMNS = new Set([
  'Detected', 'Decided At', 'Deleted At', 'Time', 'Created', 'Completed',
  'Starts', 'Deadline', 'Exempted At',
])

/**
 * Columns that are hidden from display (used internally).
 */
export const HIDDEN_COLUMNS = new Set(['id'])
