<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <!-- Degraded: at least one in-scope directory is unreachable. The empty
       action/suggestion queue still warrants this summary slot, but it must
       NOT claim health — otherwise it contradicts the "Unavailable" badges
       the Profiles/Directories panel shows for the same directories. -->
  <div v-if="degraded" class="bg-amber-50 border border-amber-200 rounded-xl p-8 text-center">
    <svg class="w-12 h-12 text-amber-500 mx-auto mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
      <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m0 3.75h.008v.008H12V16.5zm-.75-12.06a1.5 1.5 0 012.498 0l7.5 11.25A1.5 1.5 0 0120.25 21H3.75a1.5 1.5 0 01-1.248-2.31l7.5-11.25z" />
    </svg>
    <p class="text-lg font-semibold text-amber-800">Directories need attention</p>
    <p class="text-sm text-amber-700 mt-1">{{ subtitle }}</p>
  </div>
  <div v-else class="bg-green-50 border border-green-200 rounded-xl p-8 text-center">
    <svg class="w-12 h-12 text-green-400 mx-auto mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
      <path stroke-linecap="round" stroke-linejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
    </svg>
    <p class="text-lg font-semibold text-green-800">All clear</p>
    <p class="text-sm text-green-600 mt-1">{{ subtitle }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

const props = withDefaults(defineProps<{
  /**
   * Number of in-scope directories currently unreachable. >0 flips the panel
   * from the green "all healthy" affirmation to an amber caution, so it never
   * claims directory health while one is down — the contradiction this panel
   * otherwise showed beside the Profiles/Directories panel's "Unavailable" cue.
   */
  unavailableCount?: number
}>(), {
  unavailableCount: 0,
})

const auth = useAuthStore()
const degraded = computed(() => props.unavailableCount > 0)

// Reviews are an ee/governance feature — on a community deployment
// `isComplianceEnabled` is false and there are no review campaigns
// to be on track. Mentioning them then is misleading; strip the
// half-sentence rather than ship copy that implies a feature the
// operator can't reach.
const subtitle = computed(() => {
  if (degraded.value) {
    const n = props.unavailableCount
    return `${n} director${n === 1 ? 'y is' : 'ies are'} unavailable. No other pending actions.`
  }
  return auth.isComplianceEnabled
    ? 'No pending actions. Your directories are healthy and reviews are on track.'
    : 'No pending actions. Your directories are healthy.'
})
</script>
