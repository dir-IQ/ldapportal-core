<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { IVIA_NAME, IVIA_ABBR } from '@/constants/productNames'
import {
  getIsvaConfig,
  getIsvaProfileOverride,
  setIsvaProfileOverride,
} from '@/api/isvaConfig'

// Self-contained per-profile "exempt from ISVA" control. Renders
// nothing unless (a) the addon is present on this build and (b) the
// profile's directory has ISVA enabled — mirroring the directory-level
// gating so community / non-addon builds never see it. Owns its own
// load + save so the host editor doesn't need ISVA awareness.
const props = defineProps<{
  directoryId: string
  profileId: string
}>()

const auth = useAuthStore()
const notif = useNotificationStore()

const visible = ref<boolean>(false)
const loading = ref<boolean>(false)
const saving = ref<boolean>(false)
const forceOff = ref<boolean>(false)

async function load(): Promise<void> {
  visible.value = false
  if (!auth.isIsvaIntegrationEnabled || !props.directoryId || !props.profileId) {
    return
  }
  loading.value = true
  try {
    const cfg = await getIsvaConfig(props.directoryId)
    if (!cfg.data?.enabled) {
      return // directory has no active ISVA integration → hide
    }
    const ovr = await getIsvaProfileOverride(props.directoryId, props.profileId)
    forceOff.value = ovr.data.override === 'FORCE_OFF'
    visible.value = true
  } catch {
    // No config row (404) or any load failure → the control simply
    // doesn't apply here; stay hidden rather than surfacing an error.
    visible.value = false
  } finally {
    loading.value = false
  }
}

async function onToggle(next: boolean): Promise<void> {
  const previous = forceOff.value
  forceOff.value = next
  saving.value = true
  try {
    await setIsvaProfileOverride(
      props.directoryId,
      props.profileId,
      next ? 'FORCE_OFF' : 'INHERIT',
    )
    notif.success(
      next
        ? `Profile exempted from ${IVIA_ABBR} provisioning.`
        : `Profile now follows the directory ${IVIA_ABBR} setting.`,
    )
  } catch (e) {
    forceOff.value = previous // revert the optimistic toggle
    const err = e as { response?: { data?: { detail?: string } }; message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Failed to save override.')
  } finally {
    saving.value = false
  }
}

watch(
  () => [props.directoryId, props.profileId] as const,
  () => { void load() },
  { immediate: true },
)
</script>

<template>
  <div v-if="visible" class="border-t border-gray-100 pt-4">
    <label class="flex items-center gap-2 text-sm font-medium text-gray-900">
      <input
        type="checkbox"
        :checked="forceOff"
        :disabled="saving"
        class="rounded"
        @change="onToggle(($event.target as HTMLInputElement).checked)"
      />
      Exempt this profile from {{ IVIA_NAME }} provisioning
    </label>
    <p class="text-xs text-gray-500 pl-6 mt-1">
      When checked, create / delete / password / group operations for entries
      under this profile are plain LDAP — no {{ IVIA_ABBR }} secUser writes —
      even though {{ IVIA_ABBR }} is enabled on the directory. Leave unchecked
      to follow the directory setting. Don't exempt a profile that has already
      provisioned {{ IVIA_ABBR }} accounts without a reconcile.
    </p>
  </div>
</template>
