// SPDX-License-Identifier: Apache-2.0
// Human-readable labels and the per-area view / manage structure for the
// system-scoped superadmin permission keys (dot-notation dbValues, mirroring
// the backend SuperadminPermission enum). The catalogue itself comes from the
// API (`SuperadminPermissionsDto.all`); this supplies display strings and the
// grouping the permission editor renders.

export const SUPERADMIN_OWNER_KEY = 'superadmin.manage_superadmins'

export const SUPERADMIN_PERMISSION_LABELS: Record<string, string> = {
  'superadmin.view_application_accounts': 'View application accounts',
  'superadmin.manage_application_accounts': 'Manage application accounts',
  'superadmin.manage_superadmins': 'Manage superadmins (owner)',
  'superadmin.view_directories': 'View directory connections',
  'superadmin.manage_directories': 'Manage directory connections',
  'superadmin.view_provisioning_profiles': 'View provisioning profiles',
  'superadmin.manage_provisioning_profiles': 'Manage provisioning profiles',
  'superadmin.view_integrations': 'View integrations',
  'superadmin.manage_integrations': 'Manage integrations',
  'superadmin.view_directory_sync': 'View directory sync',
  'superadmin.manage_directory_sync': 'Manage directory sync',
  'superadmin.view_api_tokens': 'View API tokens',
  'superadmin.manage_api_tokens': 'Manage API tokens',
  'superadmin.view_application_settings': 'View application settings',
  'superadmin.manage_application_settings': 'Manage application settings',
  'superadmin.view_event_backbone': 'View event backbone',
  'superadmin.manage_event_backbone': 'Manage event backbone',
  'superadmin.manage_schema': 'Manage directory schema',
  'superadmin.view_license': 'View license',
}

/** Display label for a permission key; falls back to the raw key. */
export function superadminPermissionLabel(key: string): string {
  return SUPERADMIN_PERMISSION_LABELS[key] ?? key
}

/**
 * One row of the permission editor: an area of the superadmin UI with an
 * optional read-only (`view`) key and an optional write (`manage`) key.
 * The backend treats a `manage` grant as implying `view`, so the editor
 * stores a single key per area.
 */
export interface SuperadminPermissionArea {
  id: string
  label: string
  description?: string
  view?: string
  manage?: string
}

export type SuperadminPermissionTier = 'none' | 'view' | 'manage'

export const SUPERADMIN_TIER_LABELS: Record<SuperadminPermissionTier, string> = {
  none: 'None',
  view: 'View',
  manage: 'Manage',
}

export const SUPERADMIN_PERMISSION_AREAS: readonly SuperadminPermissionArea[] = [
  { id: 'application_accounts', label: 'Application accounts',
    description: 'Admin accounts, their profile roles, and feature overrides',
    view: 'superadmin.view_application_accounts', manage: 'superadmin.manage_application_accounts' },
  { id: 'directories', label: 'Directory connections',
    description: 'Connections, discovery, and Entra',
    view: 'superadmin.view_directories', manage: 'superadmin.manage_directories' },
  { id: 'provisioning_profiles', label: 'Provisioning profiles',
    view: 'superadmin.view_provisioning_profiles', manage: 'superadmin.manage_provisioning_profiles' },
  { id: 'integrations', label: 'Integrations',
    description: 'Vendor integrations and audit data sources',
    view: 'superadmin.view_integrations', manage: 'superadmin.manage_integrations' },
  { id: 'directory_sync', label: 'Directory sync',
    view: 'superadmin.view_directory_sync', manage: 'superadmin.manage_directory_sync' },
  { id: 'api_tokens', label: 'API tokens',
    view: 'superadmin.view_api_tokens', manage: 'superadmin.manage_api_tokens' },
  { id: 'application_settings', label: 'Application settings',
    description: 'Branding, authentication, SMTP, SIEM, and config export',
    view: 'superadmin.view_application_settings', manage: 'superadmin.manage_application_settings' },
  { id: 'event_backbone', label: 'Event backbone',
    description: 'Event subscriptions and the outbox',
    view: 'superadmin.view_event_backbone', manage: 'superadmin.manage_event_backbone' },
  { id: 'schema', label: 'Directory schema',
    description: 'Apply schema changes via LDIF (reading schema needs no grant)',
    manage: 'superadmin.manage_schema' },
  { id: 'license', label: 'License',
    view: 'superadmin.view_license' },
]

/** The tiers an area actually offers, lowest first. */
export function areaTiers(area: SuperadminPermissionArea): SuperadminPermissionTier[] {
  const tiers: SuperadminPermissionTier[] = ['none']
  if (area.view) tiers.push('view')
  if (area.manage) tiers.push('manage')
  return tiers
}

/** The highest tier an area offers (what an owner effectively holds). */
export function areaTopTier(area: SuperadminPermissionArea): SuperadminPermissionTier {
  return area.manage ? 'manage' : area.view ? 'view' : 'none'
}

/** Resolve an area's tier from a set of granted keys (manage wins over view). */
export function areaTier(area: SuperadminPermissionArea, granted: ReadonlySet<string>): SuperadminPermissionTier {
  if (area.manage && granted.has(area.manage)) return 'manage'
  if (area.view && granted.has(area.view)) return 'view'
  return 'none'
}

/** The key(s) to store for an area at the given tier (empty for `none`). */
export function areaKeysForTier(area: SuperadminPermissionArea, tier: SuperadminPermissionTier): string[] {
  if (tier === 'manage' && area.manage) return [area.manage]
  if (tier === 'view' && area.view) return [area.view]
  return []
}
