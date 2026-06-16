// SPDX-License-Identifier: Apache-2.0
// Human-readable labels for the system-scoped superadmin permission keys
// (dot-notation dbValues, mirroring the backend SuperadminPermission enum).
// The catalogue itself comes from the API (`SuperadminPermissionsDto.all`);
// this only supplies display strings.

export const SUPERADMIN_OWNER_KEY = 'superadmin.manage_superadmins'

export const SUPERADMIN_PERMISSION_LABELS: Record<string, string> = {
  'superadmin.manage_application_accounts': 'Manage application accounts',
  'superadmin.manage_superadmins': 'Manage superadmins (owner)',
  'superadmin.manage_directories': 'Manage directory connections',
  'superadmin.manage_provisioning_profiles': 'Manage provisioning profiles',
  'superadmin.manage_integrations': 'Manage integrations',
  'superadmin.manage_directory_sync': 'Manage directory sync',
  'superadmin.manage_api_tokens': 'Manage API tokens',
  'superadmin.manage_application_settings': 'Manage application settings',
  'superadmin.manage_event_backbone': 'Manage event backbone',
  'superadmin.view_license': 'View license',
}

/** Display label for a permission key; falls back to the raw key. */
export function superadminPermissionLabel(key: string): string {
  return SUPERADMIN_PERMISSION_LABELS[key] ?? key
}
