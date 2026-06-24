<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
/**
 * ProfilesPanel — admin dashboard panel that replaces DirectoriesPanel.
 *
 * Admins think in terms of profiles (their actual grants) rather than raw
 * directories, so this panel renders one row per AdminProfileRole the
 * caller holds: profile name, owning directory, base role, target OU,
 * and the pending approval count for that profile. Clicking a row deep-
 * links to the profile's directory Users page — the most common entry
 * point once an admin knows which scope they want to work in.
 *
 * Shape-compatible with DirectoriesPanel on the outer chrome (card +
 * table + empty-state row) so swapping between them at the dashboard
 * level doesn't produce visual jitter.
 */
import EmptyState from '@/components/EmptyState.vue'

/** One admin profile-role row, as merged by the dashboard service. */
interface ProfileRole {
  id: string
  name: string
  directoryName?: string | null
  targetUserDn?: string | null
  userCount: number
  groupCount: number
  pendingApprovals: number
}

withDefaults(defineProps<{
  profiles?: ProfileRole[]
  rowClickable?: boolean
}>(), {
  profiles: () => [],
  rowClickable: false,
})
defineEmits<{
  rowClick: [ProfileRole]
}>()

/** Render "ou=eng,dc=example,dc=com" as "ou=eng" — the meaningful bit. */
function shortenOu(dn: string | null | undefined): string {
  if (!dn) return '—'
  const first = dn.split(',')[0]
  return first || dn
}

/**
 * True when the profile's directory couldn't be reached: the dashboard service
 * returns a -1 user/group count on LDAP failure (rendered as an em-dash) — the
 * same signal the Directories panel surfaces as an "unreachable" dot. Used to
 * explain *why* the counts are dashes instead of showing a bare "—".
 */
function directoryUnavailable(p: ProfileRole): boolean {
  return p.userCount < 0 || p.groupCount < 0
}
</script>

<template>
  <div id="dashboard-scope-panel" class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
    <div class="px-5 py-3 border-b border-gray-100">
      <h2 class="text-sm font-semibold text-gray-700">Profiles</h2>
    </div>
    <table class="w-full text-sm">
      <thead class="bg-gray-50">
        <tr>
          <th class="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">Profile</th>
          <th class="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">Scope</th>
          <th class="px-4 py-2.5 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">Users</th>
          <th class="px-4 py-2.5 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">Groups</th>
          <th class="px-4 py-2.5 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">Pending</th>
        </tr>
      </thead>
      <tbody class="divide-y divide-gray-50">
        <tr v-for="p in profiles" :key="p.id"
            class="hover:bg-gray-50"
            :class="rowClickable ? 'cursor-pointer' : ''"
            @click="rowClickable && $emit('rowClick', p)">
          <td class="px-4 py-2.5">
            <div class="font-medium text-gray-900">{{ p.name }}</div>
            <!-- Directory name as a subtle second line — disambiguates profiles
                 that share a name across directories without taking its own
                 column. Omitted when there's only one directory in play
                 (the overhead beats the information there). -->
            <div v-if="p.directoryName" class="text-xs text-gray-500">{{ p.directoryName }}</div>
            <!-- When the directory is unreachable the user/group counts come back
                 as em-dashes; spell out why so it doesn't read as "no data". -->
            <div v-if="directoryUnavailable(p)"
                 class="mt-1 inline-flex items-center gap-1 text-xs text-red-600"
                 title="The directory is unavailable, so user and group counts can't be loaded.">
              <svg class="w-3.5 h-3.5 shrink-0" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                <path fill-rule="evenodd" d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.515 2.625H3.72c-1.345 0-2.188-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 8a1 1 0 100-2 1 1 0 000 2z" clip-rule="evenodd" />
              </svg>
              Directory unavailable
            </div>
          </td>
          <td class="px-4 py-2.5 text-gray-600">
            <code class="text-[11px] bg-gray-50 border border-gray-100 rounded px-1 py-0.5"
                  :title="p.targetUserDn || ''">{{ shortenOu(p.targetUserDn) }}</code>
          </td>
          <!-- LDAP counts scoped to the profile's targetUserDn. -1 signals a
               directory error; render an em-dash (flagged unavailable) rather
               than a misleading zero. -->
          <td class="px-4 py-2.5 text-right" :class="p.userCount < 0 ? 'text-red-400' : 'text-gray-600'">
            <span :title="p.userCount < 0 ? 'Directory unavailable' : ''">{{ p.userCount >= 0 ? p.userCount.toLocaleString() : '—' }}</span>
          </td>
          <td class="px-4 py-2.5 text-right" :class="p.groupCount < 0 ? 'text-red-400' : 'text-gray-600'">
            <span :title="p.groupCount < 0 ? 'Directory unavailable' : ''">{{ p.groupCount >= 0 ? p.groupCount.toLocaleString() : '—' }}</span>
          </td>
          <td class="px-4 py-2.5 text-right">
            <span v-if="p.pendingApprovals > 0" class="text-amber-600 font-medium">{{ p.pendingApprovals }}</span>
            <span v-else class="text-gray-500">0</span>
          </td>
        </tr>
        <tr v-if="!profiles.length">
          <td colspan="5">
            <EmptyState icon="folder" title="No profile roles assigned." />
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
