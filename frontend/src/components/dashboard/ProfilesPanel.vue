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
  targetGroupDn?: string | null
  userCount: number
  groupCount: number
  pendingApprovals: number
}

withDefaults(defineProps<{
  profiles?: ProfileRole[]
  rowClickable?: boolean
  /**
   * Whether the LDAP user/group counts have loaded. The dashboard paints first
   * without counts (they're the slow part) and fills them in on a follow-up
   * request; while false, the Users/Groups cells render a skeleton instead of a
   * placeholder zero. Defaults to true so other callers are unaffected.
   */
  countsLoaded?: boolean
}>(), {
  profiles: () => [],
  rowClickable: false,
  countsLoaded: true,
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
 * Whether the profile's groups live in a distinct subtree from its users.
 * targetGroupDn defaults to targetUserDn, so only surface a separate group
 * scope row when it actually differs.
 */
function groupScopeDiffers(p: ProfileRole): boolean {
  const g = p.targetGroupDn
  return !!g && g.toLowerCase() !== (p.targetUserDn || '').toLowerCase()
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
            <!-- Second line: directory name plus, to its right, a compact
                 "Unavailable" cue when the directory is unreachable (its counts
                 come back as em-dashes). Kept on one line so each row stays two
                 lines of text. -->
            <div v-if="p.directoryName || directoryUnavailable(p)" class="flex items-center gap-1.5 text-xs">
              <span v-if="p.directoryName" class="text-gray-500">{{ p.directoryName }}</span>
              <span v-if="directoryUnavailable(p)"
                    class="inline-flex items-center gap-1 text-red-600"
                    title="The directory is unavailable, so user and group counts can't be loaded.">
                <svg class="w-3.5 h-3.5 shrink-0" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path fill-rule="evenodd" d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.515 2.625H3.72c-1.345 0-2.188-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 8a1 1 0 100-2 1 1 0 000 2z" clip-rule="evenodd" />
                </svg>
                Unavailable
              </span>
            </div>
          </td>
          <!-- Users live under targetUserDn; groups under targetGroupDn. Show the
               group OU on a second line only when it's a distinct subtree (it
               defaults to the user OU), so the Groups count is interpretable. -->
          <td class="px-4 py-2.5 text-gray-600">
            <div class="flex flex-col items-start gap-0.5">
              <code class="text-[11px] bg-gray-50 border border-gray-100 rounded px-1 py-0.5"
                    :title="`Users: ${p.targetUserDn || '—'}`">{{ shortenOu(p.targetUserDn) }}</code>
              <code v-if="groupScopeDiffers(p)"
                    class="text-[11px] bg-gray-50 border border-gray-100 rounded px-1 py-0.5"
                    :title="`Groups: ${p.targetGroupDn || '—'}`">{{ shortenOu(p.targetGroupDn) }}</code>
            </div>
          </td>
          <!-- LDAP counts scoped to the profile's targetUserDn. -1 signals a
               directory error; render an em-dash (flagged unavailable) rather
               than a misleading zero. -->
          <td class="px-4 py-2.5 text-right" :class="countsLoaded && p.userCount < 0 ? 'text-red-400' : 'text-gray-600'">
            <span v-if="countsLoaded" :title="p.userCount < 0 ? 'Directory unavailable' : ''">{{ p.userCount >= 0 ? p.userCount.toLocaleString() : '—' }}</span>
            <span v-else class="inline-block h-3 w-10 bg-gray-200 rounded animate-pulse align-middle">
              <span class="sr-only">Loading</span>
            </span>
          </td>
          <td class="px-4 py-2.5 text-right" :class="countsLoaded && p.groupCount < 0 ? 'text-red-400' : 'text-gray-600'">
            <span v-if="countsLoaded" :title="p.groupCount < 0 ? 'Directory unavailable' : ''">{{ p.groupCount >= 0 ? p.groupCount.toLocaleString() : '—' }}</span>
            <span v-else class="inline-block h-3 w-10 bg-gray-200 rounded animate-pulse align-middle">
              <span class="sr-only">Loading</span>
            </span>
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
