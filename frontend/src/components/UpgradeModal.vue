<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <AppModal
    v-if="modal.visible"
    :model-value="modal.visible"
    @update:model-value="close"
    :title="title"
    size="sm"
  >
    <div class="space-y-4">
      <p class="text-gray-700">
        {{ description }}
      </p>

      <!-- Limit detail -->
      <div
        v-if="isLimit"
        class="rounded border border-amber-200 bg-amber-50 px-4 py-3"
      >
        <p class="text-sm font-medium text-amber-900">
          {{ formatLimitType(modal.params.limitType) }} limit reached
        </p>
        <p class="text-sm text-amber-800 mt-1">
          Currently at
          <span class="font-mono">{{ modal.params.currentCount }}</span>
          /
          <span class="font-mono">{{ modal.params.maximum }}</span>
          on the <span class="font-semibold">{{ modal.params.currentEdition }}</span> tier.
        </p>
      </div>

      <!-- Entitlement detail -->
      <div
        v-if="isEntitlement"
        class="rounded border border-blue-200 bg-blue-50 px-4 py-3"
      >
        <p class="text-sm font-medium text-blue-900">
          {{ formatEntitlement(modal.params.entitlement) }} not included
        </p>
        <p class="text-sm text-blue-800 mt-1">
          Your current edition
          (<span class="font-semibold">{{ modal.params.currentEdition }}</span>)
          doesn't include this feature. Upgrade to a tier that includes it.
        </p>
      </div>

      <p class="text-sm text-gray-500">
        Contact your Talligent representative or email
        <a
          :href="'mailto:sales@talligent.com?subject=' + encodeURIComponent(mailSubject)"
          class="text-blue-600 hover:underline"
        >
          sales@talligent.com
        </a>
        to upgrade.
      </p>
    </div>

    <template #footer>
      <button @click="close" class="btn-secondary">Close</button>
    </template>
  </AppModal>
</template>

<script setup>
import { computed } from 'vue'
import { useUpgradeModalStore } from '@/stores/upgradeModal'
import AppModal from '@/components/AppModal.vue'

const modal = useUpgradeModalStore()

const isLimit = computed(() => modal.params?.code === 'LIMIT_EXCEEDED')
const isEntitlement = computed(() => modal.params?.code === 'ENTITLEMENT_MISSING')

const title = computed(() => {
  if (isLimit.value) return 'License limit reached'
  if (isEntitlement.value) return 'Feature not included'
  return 'License restriction'
})

const description = computed(() => {
  if (isLimit.value) {
    return 'This operation would exceed a cap set by your current license.'
  }
  if (isEntitlement.value) {
    return 'This feature requires a higher edition than your current license grants.'
  }
  return 'Your current license doesn\'t permit this operation.'
})

const mailSubject = computed(() => {
  if (isLimit.value) {
    return `Upgrade request — ${modal.params.limitType} limit reached on ${modal.params.currentEdition}`
  }
  if (isEntitlement.value) {
    return `Upgrade request — ${modal.params.entitlement} on ${modal.params.currentEdition}`
  }
  return 'LDAP Portal upgrade request'
})

/** Turn DIRECTORIES → Directories, PROFILES_PER_DIRECTORY → Profiles per directory. */
function formatLimitType(s) {
  if (!s) return ''
  return s
    .toLowerCase()
    .split('_')
    .join(' ')
    .replace(/^./, c => c.toUpperCase())
}

function formatEntitlement(s) {
  if (!s) return ''
  return s
    .toLowerCase()
    .split('_')
    .join(' ')
    .replace(/^./, c => c.toUpperCase())
}

function close() {
  modal.hide()
}
</script>
