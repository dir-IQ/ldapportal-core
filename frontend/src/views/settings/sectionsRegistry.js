// SPDX-License-Identifier: Apache-2.0
// Single source of truth for the Settings sidebar + content pane.
// Adding a new section is a single entry here; the sidebar iterates over this
// list and the content pane uses the `id` from the route param to look up the
// component to render.

import BrandingSection         from './sections/BrandingSection.vue'
import AuthenticationSection   from './sections/AuthenticationSection.vue'
import UserGroupEditsSection  from './sections/UserGroupEditsSection.vue'
import SessionSection          from './sections/SessionSection.vue'
import SmtpSection             from './sections/SmtpSection.vue'
import S3Section               from './sections/S3Section.vue'
import SiemSection             from './sections/SiemSection.vue'
import LicenseSection          from './sections/LicenseSection.vue'

// Inline SVG path data for each section's sidebar icon. Rendered at w-4 h-4
// stroked with currentColor so they pick up the active/hover text colour.
const ICONS = {
  palette: 'M12 2a10 10 0 1 0 0 20 2 2 0 0 0 2-2v-1a2 2 0 0 1 2-2h2a4 4 0 0 0 4-4 10 10 0 0 0-10-11zM8 14a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM6 10a1 1 0 1 0 0-2 1 1 0 0 0 0 2zm6-4a1 1 0 1 0 0-2 1 1 0 0 0 0 2zm6 4a1 1 0 1 0 0-2 1 1 0 0 0 0 2z',
  key:     'M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3m-3.5 3.5L19 4',
  check:   'M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z',
  clock:   'M12 8v4l2.5 2.5M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z',
  mail:    'M3 8l9 6 9-6M3 7h18a0 0 0 0 1 0 0v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7z',
  cloud:   'M7 18a5 5 0 1 1 .96-9.91 6 6 0 1 1 11.42 2.4A4 4 0 0 1 18 18H7z',
  signal:  'M5 12a7 7 0 0 1 14 0M8 12a4 4 0 0 1 8 0M12 20v-8m0 0a1 1 0 1 0 0-2 1 1 0 0 0 0 2z',
  // Licence: a stylised certificate card with a seal.
  badge:   'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM7 9h6M7 12h4M16 14a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5zM16 14v3l-1-1-1 1v-3',
}

function iconPath(key) { return ICONS[key] }

export const SECTIONS = [
  {
    id: 'branding',
    label: 'Branding',
    icon: iconPath('palette'),
    fields: ['appName', 'logoUrl', 'primaryColour', 'secondaryColour'],
    component: BrandingSection,
  },
  {
    id: 'authentication',
    label: 'Authentication',
    icon: iconPath('key'),
    fields: [
      'enabledAuthTypes',
      'ldapAuthHost', 'ldapAuthPort', 'ldapAuthSslMode', 'ldapAuthTrustAllCerts',
      'ldapAuthTrustedCertPem', 'ldapAuthBindDn', 'ldapAuthBindPassword',
      'ldapAuthUserSearchBase', 'ldapAuthBindDnPattern',
      'oidcIssuerUrl', 'oidcClientId', 'oidcClientSecret', 'oidcScopes', 'oidcUsernameClaim',
      'oidcRedirectUri',
      'websealTrustedProxies', 'websealUserHeader', 'websealGroupsHeader', 'websealLogoutUrl',
    ],
    component: AuthenticationSection,
  },
  {
    // ID stays 'approval' so existing /settings/approval bookmarks
    // and superadmin nav links don't break — only the visible label
    // and the underlying component changed.
    id: 'approval',
    label: 'User/Group Edits',
    icon: iconPath('check'),
    fields: ['directorySearchInlineEditEnabled'],
    component: UserGroupEditsSection,
  },
  {
    id: 'session',
    label: 'Session',
    icon: iconPath('clock'),
    fields: ['sessionTimeoutMinutes'],
    component: SessionSection,
  },
  {
    id: 'smtp',
    label: 'SMTP',
    icon: iconPath('mail'),
    fields: ['smtpHost', 'smtpPort', 'smtpSenderAddress', 'smtpUsername', 'smtpPassword', 'smtpUseTls'],
    component: SmtpSection,
  },
  {
    id: 's3',
    label: 'S3',
    icon: iconPath('cloud'),
    fields: ['s3EndpointUrl', 's3BucketName', 's3AccessKey', 's3SecretKey', 's3Region', 's3PresignedUrlTtlHours'],
    component: S3Section,
  },
  {
    id: 'siem',
    label: 'SIEM / Syslog',
    icon: iconPath('signal'),
    fields: [
      'siemEnabled', 'siemProtocol', 'siemHost', 'siemPort', 'siemFormat',
      'siemAuthToken', 'webhookUrl', 'webhookAuthHeader',
    ],
    component: SiemSection,
  },
  {
    // Read-only. No fields — dirty tracking sees nothing, so the Save
    // button stays disabled on this tab.
    id: 'license',
    label: 'License',
    icon: iconPath('badge'),
    fields: [],
    component: LicenseSection,
    // Hidden on community / community-plus-isva builds: there's no
    // signed license there (community baseline), so the panel has
    // nothing meaningful to show. The sidebar filters this out when
    // auth.isCommunityDistribution is true (ee classes absent).
    hideOnCommunity: true,
  },
]

export const DEFAULT_SECTION_ID = 'branding'

export function findSection(id) {
  return SECTIONS.find(s => s.id === id) || SECTIONS.find(s => s.id === DEFAULT_SECTION_ID)
}

/**
 * True when any field belonging to the given section differs between `form`
 * and `savedForm` (deep compare via JSON serialisation). Used by the sidebar
 * to show a dirty dot next to edited categories.
 */
export function sectionIsDirty(section, form, savedForm) {
  if (!section || !form || !savedForm) return false
  for (const key of section.fields) {
    if (JSON.stringify(form[key]) !== JSON.stringify(savedForm[key])) return true
  }
  return false
}
