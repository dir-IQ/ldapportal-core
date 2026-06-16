// SPDX-License-Identifier: Apache-2.0
// Human-readable labels for the admin feature-permission keys, keyed by the
// dot-notation dbValue (mirroring the backend FeatureKey enum). The catalogue
// itself is backend-driven (the feature-catalog endpoint / effective-permissions
// view); this only supplies friendly display strings.

export const FEATURE_PERMISSION_LABELS: Record<string, string> = {
  'user.create': 'Create users',
  'user.edit': 'Edit users',
  'user.delete': 'Delete users',
  'user.enable_disable': 'Enable / disable users',
  'user.move': 'Move users',
  'user.reset_password': 'Reset user passwords',
  'user.read': 'View users',
  'group.create_delete': 'Create / delete groups',
  'group.edit': 'Edit groups',
  'group.manage_members': 'Manage group members',
  'group.read': 'View groups',
  'bulk.import': 'Bulk import',
  'bulk.export': 'Bulk export',
  'bulk.attribute_update': 'Bulk attribute update',
  'bulk.delete': 'Bulk delete',
  'reports.run': 'Run reports',
  'reports.schedule': 'Schedule reports',
  'access_review.manage': 'Manage access reviews',
  'access_review.review': 'Perform access reviews',
  'approval.manage': 'Manage approvals',
  'playbook.manage': 'Manage playbooks',
  'playbook.execute': 'Run playbooks',
  'csv_template.manage': 'Manage CSV templates',
  'directory.browse': 'Browse the directory',
  'schema.read': 'View directory schema',
  'sod.manage': 'Manage separation-of-duties rules',
  'sod.view': 'View separation-of-duties rules',
  'hr.manage': 'Manage HR data',
  'hr.view': 'View HR data',
  'auditor.manage': 'Manage auditing',
}

/** Friendly label for a feature key (dot-notation dbValue); falls back to the raw key. */
export function featurePermissionLabel(dbValue: string): string {
  return FEATURE_PERMISSION_LABELS[dbValue] ?? dbValue
}
