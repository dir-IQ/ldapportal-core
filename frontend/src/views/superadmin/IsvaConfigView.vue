<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FormField from '@/components/FormField.vue'
import {
  getIsvaConfig,
  upsertIsvaConfig,
  probeIsvaConfig,
  getIsvaUiOptions,
  type IsvaConfigDto,
  type UpsertIsvaConfigRequest,
  type ProbeResult,
  type IsvaTopologyMode,
  type IsvaDeletePolicy,
  type IsvaGroupMemberTarget,
  type IsvaDemographicDeleteMode,
} from '@/api/isvaConfig'
import { IVIA_NAME, IVIA_ABBR } from '@/constants/productNames'

const route = useRoute()
const router = useRouter()
const directoryId = computed(() => route.params.id as string)

const loading = ref(true)
const saving = ref(false)
const probing = ref(false)
const error = ref<string | null>(null)
const probeResult = ref<ProbeResult | null>(null)

// Form state. Defaults match the entity-level defaults so a fresh
// "no row exists" load lands here with sensible values.
interface Form {
  enabled: boolean
  topologyMode: IsvaTopologyMode
  secAuthority: string
  defaultValidUntilYears: number
  deletePolicy: IsvaDeletePolicy
  requireSecGroup: boolean
  managementDitBaseDn: string
  secuserRdnAttribute: 'secUUID' | 'secLogin'
  groupMemberTarget: IsvaGroupMemberTarget
  onDemographicDelete: IsvaDemographicDeleteMode
}

const form = ref<Form>({
  enabled: false,
  topologyMode: 'INLINE',
  secAuthority: 'Default',
  defaultValidUntilYears: 100,
  deletePolicy: 'DISABLE',
  requireSecGroup: true,
  managementDitBaseDn: '',
  secuserRdnAttribute: 'secUUID',
  groupMemberTarget: 'DEMOGRAPHIC_DN',
  onDemographicDelete: 'LEAVE',
})

// Which topology modes the deployment exposes for configuration
// (env-driven, UI-only — see EXPOSED_ISVA_TOPOLOGY_MODES). Defaults to
// linked; the selector is hidden when only one mode is exposed.
const exposedModes = ref<IsvaTopologyMode[]>(['LINKED'])
const showTopologySelector = computed(() => exposedModes.value.length > 1)

// A persisted config can hold a topology this deployment no longer offers
// (e.g. an existing INLINE config when only linked is exposed). The selector
// is then hidden, so surface a note instead of a silent, unchangeable form.
const modeNotExposed = computed(() => !exposedModes.value.includes(form.value.topologyMode))

// Linked-mode-only fields hide when topology is INLINE so the
// operator doesn't fill in values that'll be ignored.
const isLinkedMode = computed(() => form.value.topologyMode === 'LINKED')

// Save button enabled only when the form is at least minimally
// valid. The controller validates more strictly; this is just to
// catch the obvious "linked mode with empty management DIT" case
// before round-tripping the server.
const canSave = computed(() => {
  if (form.value.defaultValidUntilYears < 1) return false
  if (isLinkedMode.value && !form.value.managementDitBaseDn.trim()) return false
  return true
})

onMounted(async () => {
  // Fetch UI options (global, cached) and the per-directory config in
  // parallel. UI options determine the fresh-config default mode and
  // whether the selector shows; a failed options fetch keeps [LINKED].
  const [optsRes, cfgRes] = await Promise.allSettled([
    getIsvaUiOptions(),
    getIsvaConfig(directoryId.value),
  ])

  if (optsRes.status === 'fulfilled' && optsRes.value.data.exposedTopologyModes?.length) {
    exposedModes.value = optsRes.value.data.exposedTopologyModes
  }

  if (cfgRes.status === 'fulfilled') {
    populateFromDto(cfgRes.value.data)
  } else {
    const e = cfgRes.reason as { response?: { status?: number } }
    if (e?.response?.status === 404) {
      // New-install path: no config row. Default to the first exposed mode.
      form.value.topologyMode = exposedModes.value[0]
    } else {
      error.value = extractErrorMessage(cfgRes.reason, `Could not load ${IVIA_ABBR} configuration.`)
    }
  }
  loading.value = false
})

function populateFromDto(dto: IsvaConfigDto) {
  form.value.enabled = dto.enabled
  form.value.topologyMode = dto.topologyMode
  form.value.secAuthority = dto.secAuthority ?? 'Default'
  form.value.defaultValidUntilYears = dto.defaultValidUntilYears
  form.value.deletePolicy = dto.deletePolicy
  form.value.requireSecGroup = dto.requireSecGroup
  form.value.managementDitBaseDn = dto.managementDitBaseDn ?? ''
  form.value.secuserRdnAttribute = (dto.secuserRdnAttribute as 'secUUID' | 'secLogin') ?? 'secUUID'
  form.value.groupMemberTarget = dto.groupMemberTarget ?? 'DEMOGRAPHIC_DN'
  form.value.onDemographicDelete = dto.onDemographicDelete ?? 'LEAVE'
}

async function save() {
  saving.value = true
  error.value = null
  try {
    const payload: UpsertIsvaConfigRequest = {
      enabled: form.value.enabled,
      topologyMode: form.value.topologyMode,
      secAuthority: form.value.secAuthority,
      defaultValidUntilYears: form.value.defaultValidUntilYears,
      deletePolicy: form.value.deletePolicy,
      requireSecGroup: form.value.requireSecGroup,
      // Linked-only — server clears these when topologyMode = INLINE,
      // so it's safe to send the form values regardless.
      managementDitBaseDn: form.value.managementDitBaseDn.trim() || null,
      secuserRdnAttribute: form.value.secuserRdnAttribute,
      groupMemberTarget: form.value.groupMemberTarget,
      onDemographicDelete: form.value.onDemographicDelete,
    }
    const { data } = await upsertIsvaConfig(directoryId.value, payload)
    populateFromDto(data)
  } catch (e: unknown) {
    error.value = extractErrorMessage(e, 'Save failed.')
  } finally {
    saving.value = false
  }
}

async function probe() {
  probing.value = true
  probeResult.value = null
  try {
    // Force a save first so the probe runs against the latest
    // config values rather than whatever was last persisted.
    // Skip if form is unchanged from server state — but for v1,
    // always-save is simpler and the probe-button-implies-save
    // semantic is consistent with the rest of the panel.
    await save()
    const { data } = await probeIsvaConfig(directoryId.value)
    probeResult.value = data
  } catch (e: unknown) {
    error.value = extractErrorMessage(e, 'Probe failed.')
  } finally {
    probing.value = false
  }
}

function extractErrorMessage(e: unknown, fallback: string): string {
  const err = e as { response?: { data?: { detail?: string; message?: string }; status?: number }; message?: string }
  return (
    err?.response?.data?.detail
    || err?.response?.data?.message
    || (err?.response?.status ? `HTTP ${err.response.status}` : null)
    || err?.message
    || fallback
  )
}
</script>

<template>
  <div class="max-w-3xl mx-auto p-6 space-y-6">
    <!-- Header + back button -->
    <header class="flex items-center justify-between">
      <div>
        <button
          @click="router.push('/superadmin/directories')"
          class="text-sm text-blue-600 hover:text-blue-700 mb-1"
        >&larr; Back to directories</button>
        <h1 class="text-xl font-semibold text-gray-900">{{ IVIA_NAME }} integration</h1>
        <p class="text-sm text-gray-500">
          Configure the {{ IVIA_ABBR }} full-mode write-path overlay for this directory.
        </p>
      </div>
    </header>

    <div v-if="loading" class="text-sm text-gray-500 py-12 text-center">
      Loading configuration…
    </div>

    <form v-else @submit.prevent="save" class="space-y-6">
      <!-- Core toggle + topology -->
      <section class="bg-white border border-gray-200 rounded-xl p-6 space-y-4">
        <div class="flex items-center gap-2">
          <input id="enabled" type="checkbox" v-model="form.enabled" class="rounded" />
          <label for="enabled" class="text-sm font-medium text-gray-900">
            Enable {{ IVIA_NAME }} full-mode integration
          </label>
        </div>
        <p class="text-xs text-gray-500 pl-6">
          When enabled, user create / delete / password / group operations on
          this directory produce {{ IVIA_ABBR }}-compatible writes per the
          configured topology. Leave off until you've tested in a staging
          environment.
        </p>

        <!-- Persisted topology this deployment no longer offers for editing.
             The selector is hidden, so explain rather than dead-end. -->
        <p v-if="!showTopologySelector && modeNotExposed"
           data-testid="topology-mismatch"
           class="border-t border-gray-100 pt-4 text-xs text-amber-700">
          This directory is configured for
          <code>{{ form.topologyMode === 'LINKED' ? 'linked' : 'inline' }}</code>
          topology, which this deployment doesn't offer for editing. The setting
          is preserved; an administrator can change the exposed modes
          (<code>APP_ISVA_EXPOSED_TOPOLOGY_MODES</code>) to edit it here.
        </p>

        <fieldset v-if="showTopologySelector" class="border-t border-gray-100 pt-4">
          <legend class="text-sm font-medium text-gray-900">Topology mode</legend>
          <div class="mt-2 space-y-2">
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="topology" value="INLINE"
                     v-model="form.topologyMode" class="mt-1" />
              <span>
                <span class="font-medium">Inline</span> — single LDAP entry per user
                carries both demographic + <code>sec*</code> attributes.
              </span>
            </label>
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="topology" value="LINKED"
                     v-model="form.topologyMode" class="mt-1" />
              <span>
                <span class="font-medium">Linked</span> — demographic entry
                under the provisioning OU, plus a paired
                <code>secUser</code> entry under a separate management DIT.
              </span>
            </label>
          </div>
        </fieldset>
      </section>

      <!-- Common ISVA settings -->
      <section class="bg-white border border-gray-200 rounded-xl p-6 space-y-4">
        <h2 class="text-base font-semibold text-gray-900">Common settings</h2>

        <FormField label="secAuthority" v-model="form.secAuthority"
                   placeholder="Default" />
        <p class="text-xs text-gray-500 -mt-3">
          Authority name written to every user's <code>secAuthority</code>
          attribute. Default is <code>Default</code>; only override for
          multi-authority deployments.
        </p>

        <!-- Plain input rather than FormField — FormField's modelValue
             defaults to '' which Vue's type-checker pins as string, and
             .number-modifier binding then fails the type-check. The
             tiny duplication of label/input markup is preferable to a
             cast or to monkey-patching FormField's type. -->
        <div>
          <label class="label" for="defaultValidUntilYears">
            secValidUntil default (years)
          </label>
          <input id="defaultValidUntilYears" type="number" min="1"
                 v-model.number="form.defaultValidUntilYears"
                 class="input w-full" placeholder="100" />
        </div>
        <p class="text-xs text-gray-500 -mt-3">
          New users are created with <code>secValidUntil</code> set to
          <em>now + N years</em>. Admins can shorten per-user later.
        </p>

        <fieldset>
          <legend class="text-sm font-medium text-gray-900 mb-2">Delete behaviour</legend>
          <div class="space-y-2">
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="delete" value="DISABLE"
                     v-model="form.deletePolicy" class="mt-1" />
              <span>
                <span class="font-medium">Disable</span> (recommended) — flips
                <code>secAcctValid=FALSE</code>; preserves audit + policy.
              </span>
            </label>
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="delete" value="HARD_DELETE"
                     v-model="form.deletePolicy" class="mt-1" />
              <span>
                <span class="font-medium">Hard delete</span> — actually
                <code>DEL</code>s the entry. Destroys {{ IVIA_ABBR }} policy
                associations.
              </span>
            </label>
          </div>
        </fieldset>

        <div class="flex items-start gap-2">
          <input id="requireSecGroup" type="checkbox"
                 v-model="form.requireSecGroup" class="rounded mt-1" />
          <div>
            <label for="requireSecGroup" class="text-sm font-medium text-gray-900">
              Require <code>secGroup</code> overlay on groups
            </label>
            <p class="text-xs text-gray-500">
              When on, group-membership writes refuse to add a user to a
              group that lacks <code>objectClass: secGroup</code> — {{ IVIA_ABBR }}
              would silently ignore the membership otherwise.
            </p>
          </div>
        </div>
      </section>

      <!-- Linked-mode-only block -->
      <section v-if="isLinkedMode"
               class="bg-white border border-gray-200 rounded-xl p-6 space-y-4">
        <h2 class="text-base font-semibold text-gray-900">Paired secUser settings</h2>

        <FormField label="Management DIT base DN (required)"
                   v-model="form.managementDitBaseDn"
                   placeholder="secAuthority=Default,o=ibm,c=us" />
        <p class="text-xs text-gray-500 -mt-3">
          Parent DN under which paired <code>secUser</code> entries live.
        </p>

        <fieldset>
          <legend class="text-sm font-medium text-gray-900 mb-2">secUser RDN attribute</legend>
          <div class="space-y-2">
            <label class="flex items-center gap-2 text-sm">
              <input type="radio" name="rdn" value="secUUID"
                     v-model="form.secuserRdnAttribute" />
              <code>secUUID</code> (default — generated UUID per user)
            </label>
            <label class="flex items-center gap-2 text-sm">
              <input type="radio" name="rdn" value="secLogin"
                     v-model="form.secuserRdnAttribute" />
              <code>secLogin</code> (uses the user's <code>uid</code>)
            </label>
          </div>
        </fieldset>

        <fieldset>
          <legend class="text-sm font-medium text-gray-900 mb-2">Group membership target</legend>
          <div class="space-y-2">
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="grouptarget" value="DEMOGRAPHIC_DN"
                     v-model="form.groupMemberTarget" class="mt-1" />
              <span>
                <span class="font-medium">Demographic DN</span> — group
                <code>member</code> values point at
                <code>uid=…,ou=People,…</code>.
              </span>
            </label>
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="grouptarget" value="SECUSER_DN"
                     v-model="form.groupMemberTarget" class="mt-1" />
              <span>
                <span class="font-medium">secUser DN</span> — group
                <code>member</code> values point at
                <code>secUUID=…,secAuthority=Default,…</code>.
              </span>
            </label>
          </div>
          <p class="text-xs text-gray-500 mt-2">
            Pick the convention your existing {{ IVIA_ABBR }} groups already use.
            Wrong inference here corrupts ACLs; if in doubt, check an
            existing group in the directory browser first.
          </p>
        </fieldset>

        <fieldset>
          <legend class="text-sm font-medium text-gray-900 mb-2">
            On demographic-entry delete
          </legend>
          <div class="space-y-2">
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="ondemodelete" value="LEAVE"
                     v-model="form.onDemographicDelete" class="mt-1" />
              <span>
                <span class="font-medium">Leave</span> (default) — touch only
                the secUser entry on soft-delete; demographic stays as-is.
              </span>
            </label>
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="ondemodelete" value="DISABLE_AND_MARK"
                     v-model="form.onDemographicDelete" class="mt-1" />
              <span>
                <span class="font-medium">Disable and mark</span> — also
                annotate the demographic entry (v1 logs a TODO; the
                actual marker write lands in v1.1 once we know your
                site convention).
              </span>
            </label>
          </div>
        </fieldset>
      </section>

      <!-- Probe result -->
      <section v-if="probeResult"
               class="bg-white border border-gray-200 rounded-xl p-4 space-y-2 text-sm">
        <h2 class="text-base font-semibold text-gray-900">Probe result</h2>
        <p>
          Management DIT reachable:
          <span :class="probeResult.reachable ? 'text-green-600 font-medium' : 'text-red-600 font-medium'">
            {{ probeResult.reachable ? 'yes' : 'no' }}
          </span>
        </p>
        <p>
          Sample <code>secUser</code> entry found:
          <span :class="probeResult.sampleSecUserFound ? 'text-green-600 font-medium' : 'text-amber-600 font-medium'">
            {{ probeResult.sampleSecUserFound ? 'yes' : 'no' }}
          </span>
        </p>
        <ul v-if="probeResult.warnings.length" class="list-disc list-inside text-gray-600">
          <li v-for="w in probeResult.warnings" :key="w">{{ w }}</li>
        </ul>
      </section>

      <!-- Errors -->
      <div v-if="error"
           class="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">
        {{ error }}
      </div>

      <!-- Actions -->
      <div class="flex justify-end gap-3">
        <button
          type="button"
          class="btn-secondary"
          :disabled="probing || saving || !canSave"
          @click="probe">
          {{ probing ? 'Probing…' : 'Save & probe' }}
        </button>
        <button
          type="submit"
          class="btn-primary"
          :disabled="saving || !canSave">
          {{ saving ? 'Saving…' : 'Save' }}
        </button>
      </div>
    </form>
  </div>
</template>
