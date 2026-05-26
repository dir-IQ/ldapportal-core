<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <nav v-if="crumbs.length > 1" class="flex items-center gap-1.5 text-xs text-gray-500 px-6 py-2 border-b border-gray-100 bg-gray-50/50">
    <template v-for="(crumb, i) in crumbs" :key="crumb.path">
      <svg v-if="i > 0" class="w-3 h-3 text-gray-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" />
      </svg>
      <RouterLink v-if="i < crumbs.length - 1" :to="crumb.path"
                  class="hover:text-gray-700 transition-colors truncate max-w-[200px]">
        {{ crumb.label }}
      </RouterLink>
      <span v-else class="text-gray-700 font-medium truncate max-w-[200px]">{{ crumb.label }}</span>
    </template>
  </nav>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

const route = useRoute()

const LABEL_MAP = {
  'home': 'Home',
  'superadmin': 'Admin',
  'dashboard': 'Dashboard',
  'admins': 'Application Accounts',
  'directories': 'Directory Connections',
  'directory-browser': 'Directory Browser',
  'directory-search': 'Directory Search',
  'directory-schema': 'Schema Browser',
  'integrity': 'Integrity Check',
  'audit-log': 'Audit Log',
  'audit-sources': 'Audit Sources',
  'audit-reports': 'Compliance Reports',
  'auditor-links': 'Auditor Links',
  'profiles': 'Provisioning Profiles',
  'playbooks': 'Lifecycle Playbooks',
  'reports': 'Reports',
  'settings': 'Settings',
  'sod-policies': 'SoD Policies',
  'sod-violations': 'SoD Violations',
  'access-drift': 'Access Drift',
  'access-reviews': 'Access Reviews',
  'campaign-templates': 'Campaign Templates',
  'hr': 'HR Integration',
  'employees': 'Employees',
  'users': 'Users',
  'groups': 'Groups',
  'audit': 'Audit Log',
  'bulk': 'Bulk Import/Export',
  'approvals': 'Approvals',
  'cross-campaign-report': 'Cross-Campaign Report',
  'notifications': 'Notifications',
  'discover': 'Discovery Wizard',
  'alerts': 'Alerts',
  'alert-rules': 'Alert Rules',
  'entra': 'Entra ID Browser',
}

const crumbs = computed(() => {
  const path = route.path
  const segments = path.split('/').filter(Boolean)
  const result = []

  let accumulated = ''
  for (let i = 0; i < segments.length; i++) {
    const seg = segments[i]
    accumulated += '/' + seg

    // Skip "directories" and the UUID segment — merge into one crumb
    if (seg === 'directories' && i + 1 < segments.length) {
      accumulated += '/' + segments[i + 1]
      i++ // skip UUID
      result.push({ path: accumulated + '/users', label: 'Directory' })
      continue
    }

    // Skip UUIDs (they're parameters, not navigable labels)
    if (seg.match(/^[0-9a-f]{8}-/)) continue

    const label = LABEL_MAP[seg] || seg.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
    result.push({ path: accumulated, label })
  }

  return result
})
</script>
