<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
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
defineProps({
  profiles: { type: Array, default: () => [] },
  rowClickable: { type: Boolean, default: false },
})
defineEmits(['rowClick'])

/** Render "ou=eng,dc=example,dc=com" as "ou=eng" — the meaningful bit. */
function shortenOu(dn) {
  if (!dn) return '—'
  const first = dn.split(',')[0]
  return first || dn
}
</script>

<template>
  <div class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
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
          </td>
          <td class="px-4 py-2.5 text-gray-600">
            <code class="text-[11px] bg-gray-50 border border-gray-100 rounded px-1 py-0.5"
                  :title="p.targetOuDn || ''">{{ shortenOu(p.targetOuDn) }}</code>
          </td>
          <!-- LDAP counts scoped to the profile's targetOuDn. -1 signals a
               directory error; render em-dash rather than a misleading zero. -->
          <td class="px-4 py-2.5 text-right text-gray-600">
            {{ p.userCount >= 0 ? p.userCount.toLocaleString() : '—' }}
          </td>
          <td class="px-4 py-2.5 text-right text-gray-600">
            {{ p.groupCount >= 0 ? p.groupCount.toLocaleString() : '—' }}
          </td>
          <td class="px-4 py-2.5 text-right">
            <span v-if="p.pendingApprovals > 0" class="text-amber-600 font-medium">{{ p.pendingApprovals }}</span>
            <span v-else class="text-gray-500">0</span>
          </td>
        </tr>
        <tr v-if="!profiles.length">
          <td colspan="5" class="px-5 py-8 text-center text-sm text-gray-500">
            No profile roles assigned.
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
