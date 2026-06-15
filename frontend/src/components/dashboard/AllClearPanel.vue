<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="bg-green-50 border border-green-200 rounded-xl p-8 text-center">
    <svg class="w-12 h-12 text-green-400 mx-auto mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
      <path stroke-linecap="round" stroke-linejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
    </svg>
    <p class="text-lg font-semibold text-green-800">All clear</p>
    <p class="text-sm text-green-600 mt-1">{{ subtitle }}</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

// Reviews are an ee/governance feature — on a community deployment
// `isComplianceEnabled` is false and there are no review campaigns
// to be on track. Mentioning them then is misleading; strip the
// half-sentence rather than ship copy that implies a feature the
// operator can't reach.
const auth = useAuthStore()
const subtitle = computed(() =>
  auth.isComplianceEnabled
    ? 'No pending actions. Your directories are healthy and reviews are on track.'
    : 'No pending actions. Your directories are healthy.',
)
</script>
