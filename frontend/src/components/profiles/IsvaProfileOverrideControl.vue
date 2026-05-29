<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { IVIA_ABBR } from '@/constants/productNames'
import {
  getIsvaConfig,
  getIsvaProfileOverride,
  setIsvaProfileOverride,
} from '@/api/isvaConfig'

/**
 * Self-contained per-profile "exempt from IVIA" control. Renders
 * nothing unless (a) the addon is present on this build and (b) the
 * profile's directory has IVIA enabled — mirroring the directory-
 * level gating so community / non-addon builds never see it.
 *
 * Two modes:
 *
 *   1. Edit mode (profileId provided) — owns its own load + save.
 *      Fetches the current override on mount, persists on toggle.
 *      Host editor doesn't need to wire anything beyond mounting.
 *
 *   2. Staging mode (profileId is null/empty) — used in create-
 *      profile flows. There's no row to PUT against until the
 *      profile is saved, so the control:
 *        - skips the load (no row exists yet)
 *        - holds the chosen value locally
 *        - emits 'staged-change' on toggle so the host can
 *          persist after profile create
 *        - starts at INHERIT (the documented default for
 *          unconfigured profiles)
 *
 * Both modes use the same directory-enabled probe to decide
 * visibility.
 */
const props = withDefaults(defineProps<{
  directoryId: string
  profileId?: string | null
}>(), { profileId: null })

const emit = defineEmits<{
  /**
   * Fired in staging mode (profileId is null/empty) when the
   * operator toggles the checkbox. Hosts use it to capture the
   * staged value and persist after the profile-create POST
   * succeeds.
   */
  (e: 'staged-change', override: 'INHERIT' | 'FORCE_OFF'): void
}>()

const auth = useAuthStore()
const notif = useNotificationStore()

const visible = ref<boolean>(false)
const loading = ref<boolean>(false)
const saving = ref<boolean>(false)
const forceOff = ref<boolean>(false)

const isStaging = (): boolean =>
  !props.profileId || props.profileId.length === 0

async function load(): Promise<void> {
  // TEMP DIAGNOSTIC — remove after verifying gating works in create
  // mode. Logs what the gates see at entry so we can tell which check
  // is bailing.
  // eslint-disable-next-line no-console
  console.log('[IsvaOverride] load()', {
    isIsvaIntegrationEnabled: auth.isIsvaIntegrationEnabled,
    directoryId: props.directoryId,
    profileId: props.profileId,
    isStaging: !props.profileId || props.profileId.length === 0,
  })
  visible.value = false
  forceOff.value = false
  if (!auth.isIsvaIntegrationEnabled || !props.directoryId) {
    // eslint-disable-next-line no-console
    console.log('[IsvaOverride] bailed at addon/directory gate')
    return
  }
  loading.value = true
  try {
    const cfg = await getIsvaConfig(props.directoryId)
    // eslint-disable-next-line no-console
    console.log('[IsvaOverride] config fetched', { enabled: cfg.data?.enabled })
    if (!cfg.data?.enabled) {
      return // directory has no active IVIA integration → hide
    }
    if (isStaging()) {
      // No profile yet → nothing to fetch. Default to INHERIT
      // (the documented behaviour for a profile with no override row).
      // eslint-disable-next-line no-console
      console.log('[IsvaOverride] staging mode → visible=true')
      visible.value = true
      return
    }
    const ovr = await getIsvaProfileOverride(props.directoryId, props.profileId!)
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

  // Staging mode: emit instead of persisting. Host saves after
  // profile create. No toast on toggle — too noisy when the
  // operator is still configuring; the eventual profile-create
  // success message covers the outcome.
  if (isStaging()) {
    emit('staged-change', next ? 'FORCE_OFF' : 'INHERIT')
    return
  }

  saving.value = true
  try {
    await setIsvaProfileOverride(
      props.directoryId,
      props.profileId!,
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
  <fieldset v-if="visible" class="border border-gray-300 rounded-lg p-3 space-y-3">
    <legend class="text-sm font-semibold text-gray-800 px-1">{{ IVIA_ABBR }} Integration</legend>
    <label class="flex items-center gap-2 text-sm font-medium text-gray-900">
      <input
        type="checkbox"
        :checked="forceOff"
        :disabled="saving"
        class="rounded"
        @change="onToggle(($event.target as HTMLInputElement).checked)"
      />
      Exempt this profile from {{ IVIA_ABBR }} provisioning
    </label>
    <p class="text-xs text-gray-500 pl-6">
      When checked, create / delete / password / group operations for entries
      under this profile are plain LDAP — no {{ IVIA_ABBR }} secUser writes —
      even though {{ IVIA_ABBR }} is enabled on the directory. Leave unchecked
      to follow the directory setting. Don't exempt a profile that has already
      provisioned {{ IVIA_ABBR }} accounts without a reconcile.
    </p>
  </fieldset>
</template>
