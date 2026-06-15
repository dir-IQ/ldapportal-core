<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div v-if="loading" class="text-xs text-gray-500 py-2">Checking password policy...</div>
  <div v-else-if="status && status.supported" class="rounded-lg border text-sm p-3 space-y-2" :class="borderClass">
    <div class="flex items-center gap-2 text-xs font-medium" :class="headingClass">
      <svg class="w-4 h-4 shrink-0" viewBox="0 0 20 20" fill="currentColor">
        <path v-if="isOk" fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/>
        <path v-else-if="isWarning" fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd"/>
        <path v-else fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"/>
      </svg>
      Password Policy Status
    </div>
    <div class="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
      <template v-if="status.isLocked">
        <span class="text-gray-500">Account</span>
        <span class="text-red-600 font-medium">Locked</span>
      </template>
      <template v-if="status.isExpired">
        <span class="text-gray-500">Password</span>
        <span class="text-red-600 font-medium">Expired</span>
      </template>
      <template v-else-if="status.daysUntilExpiry != null">
        <span class="text-gray-500">Expires in</span>
        <span :class="status.daysUntilExpiry <= 7 ? 'text-amber-600 font-medium' : 'text-gray-700'">
          {{ status.daysUntilExpiry }} days
        </span>
      </template>
      <template v-if="status.lastChanged">
        <span class="text-gray-500">Last changed</span>
        <span class="text-gray-700">{{ formatDate(status.lastChanged) }}</span>
      </template>
      <template v-if="status.mustChange">
        <span class="text-gray-500">Must change</span>
        <span class="text-amber-600 font-medium">Yes (admin reset)</span>
      </template>
      <template v-if="status.failedAttempts > 0">
        <span class="text-gray-500">Failed attempts</span>
        <span class="text-gray-700">{{ status.failedAttempts }}{{ status.maxFailures ? ` / ${status.maxFailures}` : '' }}</span>
      </template>
      <template v-if="status.graceLoginsRemaining != null">
        <span class="text-gray-500">Grace logins</span>
        <span class="text-gray-700">{{ status.graceLoginsRemaining }} remaining</span>
      </template>
    </div>
  </div>
  <!-- No output if not supported — graceful degradation -->
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { getPasswordStatus } from '@/api/users'

const props = defineProps({
  directoryId: { type: String, required: true },
  userDn:      { type: String, required: true },
})

const loading = ref(false)
const status = ref(null)

const isOk = computed(() => status.value && !status.value.isLocked && !status.value.isExpired && !status.value.mustChange)
const isWarning = computed(() => status.value && !status.value.isLocked && !status.value.isExpired &&
    (status.value.mustChange || (status.value.daysUntilExpiry != null && status.value.daysUntilExpiry <= 14)))
const isCritical = computed(() => status.value && (status.value.isLocked || status.value.isExpired))

const borderClass = computed(() => {
  if (isCritical.value) return 'border-red-200 bg-red-50'
  if (isWarning.value) return 'border-amber-200 bg-amber-50'
  return 'border-green-200 bg-green-50'
})

const headingClass = computed(() => {
  if (isCritical.value) return 'text-red-700'
  if (isWarning.value) return 'text-amber-700'
  return 'text-green-700'
})

function formatDate(isoStr) {
  if (!isoStr) return '—'
  return new Date(isoStr).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

async function load() {
  if (!props.directoryId || !props.userDn) return
  loading.value = true
  try {
    const { data } = await getPasswordStatus(props.directoryId, props.userDn)
    status.value = data
  } catch (e) {
    // Not all directories support ppolicy — fail silently
    status.value = null
  } finally {
    loading.value = false
  }
}

watch(() => [props.directoryId, props.userDn], load, { immediate: true })
</script>
