// SPDX-License-Identifier: Apache-2.0
export const ACTION_LABELS = {
  USER_CREATE: 'User created',
  USER_UPDATE: 'User updated',
  USER_DELETE: 'User deleted',
  USER_ENABLE: 'User enabled',
  USER_DISABLE: 'User disabled',
  USER_MOVE: 'User moved',
  PASSWORD_RESET: 'Password reset',
  GROUP_CREATE: 'Group created',
  GROUP_UPDATE: 'Group updated',
  GROUP_DELETE: 'Group deleted',
  GROUP_MEMBER_ADD: 'Member added',
  GROUP_MEMBER_REMOVE: 'Member removed',
  GROUP_BULK_IMPORT: 'Group bulk import',
  ENTRY_CREATE: 'Entry created',
  ENTRY_UPDATE: 'Entry updated',
  ENTRY_DELETE: 'Entry deleted',
  ENTRY_MOVE: 'Entry moved',
  ENTRY_RENAME: 'Entry renamed',
  LDIF_IMPORT: 'LDIF imported',
  INTEGRITY_CHECK: 'Integrity check',
  BULK_ATTRIBUTE_UPDATE: 'Bulk attribute update',
  APPROVAL_SUBMITTED: 'Approval submitted',
  APPROVAL_APPROVED: 'Request approved',
  APPROVAL_AUTO_APPROVED: 'Request auto-approved',
  APPROVAL_REJECTED: 'Request rejected',
  APPROVAL_REQUEST_EDITED: 'Request edited',
  CAMPAIGN_CREATED: 'Campaign created',
  CAMPAIGN_ACTIVATED: 'Campaign activated',
  CAMPAIGN_CLOSED: 'Campaign closed',
  CAMPAIGN_CANCELLED: 'Campaign cancelled',
  CAMPAIGN_EXPIRED: 'Campaign expired',
  REVIEW_CONFIRMED: 'Review confirmed',
  REVIEW_REVOKED: 'Review revoked',
  REVIEW_AUTO_REVOKED: 'Review auto-revoked',
  PLAYBOOK_EXECUTED: 'Playbook executed',
  PLAYBOOK_ROLLED_BACK: 'Playbook rolled back',
  LDAP_CHANGE: 'LDAP change',
  SOD_POLICY_CREATED: 'SoD policy created',
  SOD_POLICY_UPDATED: 'SoD policy updated',
  SOD_POLICY_DELETED: 'SoD policy deleted',
  SOD_SCAN_EXECUTED: 'SoD scan executed',
  SOD_VIOLATION_DETECTED: 'SoD violation detected',
  SOD_VIOLATION_EXEMPTED: 'SoD violation exempted',
  SOD_VIOLATION_BLOCKED: 'SoD violation blocked',
  SOD_VIOLATION_RESOLVED: 'SoD violation resolved',
  HR_SYNC_STARTED: 'HR sync started',
  HR_SYNC_COMPLETED: 'HR sync completed',
  HR_SYNC_FAILED: 'HR sync failed',
  HR_EMPLOYEE_MATCHED: 'HR employee matched',
  HR_ORPHAN_DETECTED: 'HR orphan detected',
  PROFILE_CREATE: 'Profile created',
  PROFILE_UPDATE: 'Profile updated',
  PROFILE_DELETE: 'Profile deleted',
  PROFILE_CLONE: 'Profile cloned',
  ACCOUNT_CREATE: 'Account created',
  ACCOUNT_UPDATE: 'Account updated',
  ACCOUNT_DELETE: 'Account deleted',
  ACCOUNT_PERMISSION_CHANGED: 'Account permissions changed',
  AUDITOR_LINK_CREATED: 'Auditor link created',
  AUDITOR_LINK_REVOKED: 'Auditor link revoked',
  AUDITOR_LINK_ACCESSED: 'Auditor link accessed',
  API_TOKEN_CREATED: 'API token created',
  API_TOKEN_REVOKED: 'API token revoked',
  API_TOKEN_ROTATED: 'API token rotated',
}

// Fallback humanizer for any audit action not in the map above — keeps the
// Recent Activity panel readable when a new AuditAction enum value is added
// on the backend before this map is updated. Turns FOO_BAR_BAZ into
// "Foo bar baz" rather than displaying the raw UPPER_SNAKE_CASE token.
function humanize(action) {
  if (!action || typeof action !== 'string') return action
  const lower = action.toLowerCase().replace(/_/g, ' ')
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}

export function actionLabel(action) {
  return ACTION_LABELS[action] || humanize(action)
}

export function actionColor(action) {
  if (action?.includes('DELETE') || action?.includes('REVOKED') || action?.includes('ROLLED_BACK') || action?.includes('BLOCKED'))
    return 'text-red-600 bg-red-50'
  if (action?.includes('CREATE') || action?.includes('APPROVED') || action?.includes('CONFIRMED'))
    return 'text-green-600 bg-green-50'
  if (action?.includes('DISABLE') || action?.includes('REJECTED') || action?.includes('CANCELLED') || action?.includes('EXPIRED') || action?.includes('DETECTED'))
    return 'text-amber-600 bg-amber-50'
  return 'text-blue-600 bg-blue-50'
}

export function shortDn(dn) {
  if (!dn) return '—'
  const first = dn.split(',')[0]
  return first || dn
}

export function cardBorder(severity) {
  switch (severity) {
    case 'green': return 'border-green-200 bg-green-50/30'
    case 'yellow': return 'border-amber-200 bg-amber-50/30'
    case 'red': return 'border-red-200 bg-red-50/30'
    case 'orange': return 'border-orange-200 bg-orange-50/30'
    default: return 'border-gray-200'
  }
}

export function cardValueColor(severity) {
  switch (severity) {
    case 'green': return 'text-green-700'
    case 'yellow': return 'text-amber-600'
    case 'red': return 'text-red-600'
    case 'orange': return 'text-orange-600'
    default: return 'text-gray-900'
  }
}
