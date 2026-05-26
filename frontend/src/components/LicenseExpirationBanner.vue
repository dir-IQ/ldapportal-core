<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div
    v-if="shouldShow"
    :role="banner.level === 'error' ? 'alert' : 'status'"
    :class="[
      'px-4 py-2 text-sm border-b flex items-center justify-between',
      banner.level === 'error'
        ? 'bg-red-50 border-red-200 text-red-800'
        : banner.level === 'warning'
        ? 'bg-amber-50 border-amber-200 text-amber-800'
        : 'bg-blue-50 border-blue-200 text-blue-800',
    ]"
  >
    <span>
      <span class="font-medium">{{ levelLabel }}:</span>
      {{ banner.message }}
    </span>
    <RouterLink
      to="/settings/license"
      class="ml-4 underline hover:no-underline whitespace-nowrap"
    >
      View license
    </RouterLink>
  </div>
</template>

<script setup>
import { computed, onMounted, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useLicenseStore } from '@/stores/license'
import { useAuthStore } from '@/stores/auth'

const license = useLicenseStore()
const auth = useAuthStore()

// Only load (and therefore show) the banner for superadmins — the
// /api/v1/license/status endpoint is gated behind ROLE_SUPERADMIN and
// regular admins would just see 403s in the network tab.
const canViewLicense = computed(() => auth.isSuperadmin)

const shouldShow = computed(
  () => canViewLicense.value && license.bannerLevel !== null
)

const banner = computed(() => ({
  level: license.bannerLevel,
  message: license.bannerMessage,
}))

const levelLabel = computed(() => {
  switch (license.bannerLevel) {
    case 'error':   return 'License past grace period'
    case 'warning': return 'License expired'
    case 'info':    return 'License approaching expiry'
    default:        return ''
  }
})

onMounted(() => {
  if (canViewLicense.value) license.ensureLoaded()
})

// Re-trigger the fetch if a login/logout flips the role (e.g. the user
// logs out and another user logs in within the same tab).
watch(canViewLicense, (can) => {
  if (can) license.ensureLoaded()
})
</script>
