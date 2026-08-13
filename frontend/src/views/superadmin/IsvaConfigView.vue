<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  getIsvaConfig,
  upsertIsvaConfig,
  probeIsvaConfig,
  getIsvaUiOptions,
  type IsvaConfigDto,
  type UpsertIsvaConfigRequest,
  type ProbeResult,
  type IsvaTopologyMode,
  type IsvaGroupMemberTarget,
  type IsvaRdnValueSource,
  type SecUserAttribute,
  type SecUserAttributeValueKind,
} from '@/api/isvaConfig'
import { getDirectory } from '@/api/directories'
import { IVIA_NAME, IVIA_ABBR } from '@/constants/productNames'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirm } from '@/composables/useConfirm'

// The unified secUser attribute model, in the backend's canonical order.
// Mirrors IsvaSecUserPlans.CANONICAL_ORDER / REQUIRED_ATTRS. secLoginType and
// secAuthority are MUST on IBM's stock secUser, so they're always enabled and
// can't be unticked (their values stay editable).
const REQUIRED_ATTRS = ['secLoginType', 'secAuthority'] as const

function isRequiredAttr(name: string): boolean {
  return REQUIRED_ATTRS.some((r) => r.toLowerCase() === name.toLowerCase())
}

// One-line help per attribute, shown under its row.
const ATTR_HINTS: Record<string, string> = {
  secLogin: "The login the account authenticates as — typically the user's uid.",
  secLoginType: 'MUST on IBM\'s stock secUser. Usually "Default".',
  secAuthority: 'MUST on IBM\'s stock secUser. The authority/domain — usually "Default".',
  secAcctValid: 'Whether the account is active. TRUE on create; flipped to FALSE on disable.',
  secPwdValid: 'Whether the password is valid. TRUE on create.',
  secValidUntil: 'Account expiry. nowPlusYears(n) sets it n years out.',
  secPwdLastChanged: 'Password-age stamp for IVIA rotation. now() on create.',
  secUUID: 'Opaque per-user id native IVIA accounts carry. Off unless your schema permits it.',
  principalName: 'The principal/login (the uid). Off unless your schema permits it.',
  secDomainId: 'Concatenated <authority>%<principal>. Off unless your schema permits it.',
}

// The default model for a fresh / unset config — matches the backend's
// legacy-derived defaults: the two MUST attrs plus the historical default
// overlay set enabled, the three IVIA identity attributes off.
function defaultAttributeModel(): SecUserAttribute[] {
  const c = (name: string, enabled: boolean,
             valueKind: SecUserAttributeValueKind, value: string): SecUserAttribute =>
    ({ name, enabled, valueKind, value })
  return [
    c('secLogin', true, 'COMPUTED', '${user.uid}'),
    c('secLoginType', true, 'LITERAL', 'Default'),
    c('secAuthority', true, 'LITERAL', 'Default'),
    c('secAcctValid', true, 'LITERAL', 'TRUE'),
    c('secPwdValid', true, 'LITERAL', 'TRUE'),
    c('secValidUntil', true, 'COMPUTED', 'nowPlusYears(100)'),
    c('secPwdLastChanged', true, 'COMPUTED', 'now()'),
    c('secUUID', false, 'COMPUTED', 'uuid()'),
    c('principalName', false, 'COMPUTED', '${user.uid}'),
    c('secDomainId', false, 'COMPUTED', '${sec.secAuthority}%${user.uid}'),
  ]
}

const route = useRoute()
const router = useRouter()
const directoryId = computed(() => route.params.id as string)
// Display name of the directory this page configures, shown in the
// heading. Empty until the fetch resolves (or if it fails).
const directoryName = ref('')
const notif = useNotificationStore()
const confirm = useConfirm()

const loading = ref(true)
const saving = ref(false)
const probing = ref(false)
const probeResult = ref<ProbeResult | null>(null)

// Form state. Defaults match the entity-level defaults so a fresh
// "no row exists" load lands here with sensible values.
interface Form {
  enabled: boolean
  topologyMode: IsvaTopologyMode
  requireSecGroup: boolean
  secuserObjectClasses: string[]
  // The unified per-attribute model — the standalone secAuthority /
  // secLoginType / valid-until inputs and the overlay checkboxes are now rows
  // in this table.
  secuserAttributes: SecUserAttribute[]
  managementDitBaseDn: string
  secuserRdnAttribute: string
  secuserRdnValueSource: IsvaRdnValueSource
  groupMemberTarget: IsvaGroupMemberTarget
}

function emptyForm(): Form {
  return {
    enabled: false,
    topologyMode: 'INLINE',
    // Opt-in since enforcement shipped (V504) — defaulting the gate on
    // would refuse memberships in plain (non-secGroup) groups.
    requireSecGroup: false,
    secuserObjectClasses: ['secUser'],
    secuserAttributes: defaultAttributeModel(),
    managementDitBaseDn: '',
    secuserRdnAttribute: 'secUUID',
    secuserRdnValueSource: 'GENERATED_UUID',
    groupMemberTarget: 'DEMOGRAPHIC_DN',
  }
}

// Deep snapshot for the pristine baseline — the form now nests an array of
// row objects, so a shallow {...form} would share those rows and defeat the
// isDirty comparison when a row is edited in place.
function snapshot(f: Form): Form {
  return JSON.parse(JSON.stringify(f)) as Form
}

const form = ref<Form>(emptyForm())

// Snapshot of the form as last loaded from / persisted to the server.
// Updated on initial load and after every successful save. Drives the
// isDirty computed and the Discard revert.
const pristine = ref<Form>(emptyForm())

// JSON.stringify is good enough — the form is flat primitives only.
// Avoids pulling in a deep-equal dependency for one call site.
const isDirty = computed(() =>
  JSON.stringify(form.value) !== JSON.stringify(pristine.value))

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

// Save enabled only when the form is at least minimally valid. The
// controller validates more strictly; this catches the obvious
// "linked mode with empty management DIT" before the round-trip.
const canSave = computed(() => {
  // Every written attribute needs a value (a literal or an expression).
  if (form.value.secuserAttributes.some((a) => a.enabled && !a.value.trim())) return false
  if (isLinkedMode.value && !form.value.managementDitBaseDn.trim()) return false
  return true
})

// Probe runs against the persisted config — disabled while the form
// is dirty so the operator doesn't see results for a config that
// doesn't match what they're looking at. Tooltip explains why.
const probeDisabledReason = computed<string | null>(() => {
  if (probing.value) return 'Probe in flight…'
  if (isDirty.value) return 'Save your changes first — Probe runs against the persisted config.'
  if (saving.value) return 'Save in flight…'
  return null
})

// Stale-probe-result guard: any form edit invalidates the displayed
// probe result. Otherwise an operator could tweak the management DIT,
// not save, and see a green "reachable" result from the old value.
watch(isDirty, (dirty) => {
  if (dirty) probeResult.value = null
})

onMounted(async () => {
  // Fetch UI options (global, cached) and the per-directory config in
  // parallel. UI options determine the fresh-config default mode and
  // whether the selector shows; a failed options fetch keeps [LINKED].
  const [optsRes, cfgRes, dirRes] = await Promise.allSettled([
    getIsvaUiOptions(),
    getIsvaConfig(directoryId.value),
    getDirectory(directoryId.value),
  ])

  if (optsRes.status === 'fulfilled' && optsRes.value.data.exposedTopologyModes?.length) {
    exposedModes.value = optsRes.value.data.exposedTopologyModes
  }

  if (dirRes.status === 'fulfilled') {
    directoryName.value = dirRes.value.data.displayName ?? ''
  }

  if (cfgRes.status === 'fulfilled') {
    populateFromDto(cfgRes.value.data)
  } else {
    const e = cfgRes.reason as { response?: { status?: number } }
    if (e?.response?.status === 404) {
      // New-install path: no config row. Default to the first exposed mode.
      form.value.topologyMode = exposedModes.value[0]
    } else {
      notif.error(extractErrorMessage(cfgRes.reason, `Could not load ${IVIA_ABBR} configuration.`))
    }
  }
  // Whatever we landed on (server DTO, defaults, or defaults with the
  // first exposed mode), capture as the pristine snapshot so the
  // initial state isn't reported as dirty.
  pristine.value = snapshot(form.value)
  loading.value = false
})

function populateFromDto(dto: IsvaConfigDto) {
  form.value.enabled = dto.enabled
  form.value.topologyMode = dto.topologyMode
  form.value.requireSecGroup = dto.requireSecGroup
  form.value.secuserObjectClasses = dto.secuserObjectClasses?.length
    ? [...dto.secuserObjectClasses]
    : ['secUser']
  // The DTO always carries a full model (server derives one when none is
  // stored); fall back to defaults only if it's somehow absent.
  form.value.secuserAttributes = dto.secuserAttributes?.length
    ? dto.secuserAttributes.map((a) => ({ ...a }))
    : defaultAttributeModel()
  form.value.managementDitBaseDn = dto.managementDitBaseDn ?? ''
  form.value.secuserRdnAttribute = dto.secuserRdnAttribute ?? 'secUUID'
  form.value.secuserRdnValueSource = dto.secuserRdnValueSource ?? 'GENERATED_UUID'
  form.value.groupMemberTarget = dto.groupMemberTarget ?? 'DEMOGRAPHIC_DN'
}

// secUser object-class chip editor. secUser is load-bearing (the
// server normalizes it back in if dropped) so it's rendered locked.
const newObjectClass = ref('')

function addObjectClass() {
  // Accept a single name or a comma/space-delimited list (paste-friendly):
  // split on either separator, drop blanks, and add each class not already
  // present — deduping case-insensitively against the existing chips and
  // within the pasted list itself.
  const names = newObjectClass.value
    .split(/[\s,]+/)
    .map((n) => n.trim())
    .filter(Boolean)
  newObjectClass.value = ''
  if (names.length === 0) return
  // Assign a new array rather than push-mutating: pristine holds a
  // shallow snapshot that shares this array's reference, so an in-place
  // mutation would change both and defeat the isDirty comparison.
  const seen = new Set(form.value.secuserObjectClasses.map((oc) => oc.toLowerCase()))
  const additions: string[] = []
  for (const name of names) {
    const key = name.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    additions.push(name)
  }
  if (additions.length > 0) {
    form.value.secuserObjectClasses = [...form.value.secuserObjectClasses, ...additions]
  }
}

function removeObjectClass(name: string) {
  if (name.toLowerCase() === 'secuser') return // can't remove the required class
  form.value.secuserObjectClasses = form.value.secuserObjectClasses
    .filter((oc) => oc !== name)
}

// Attribute-row edits (On toggle, kind, value) are bound with v-model and
// mutate the row objects in place; pristine is a deep snapshot (see snapshot())
// so the isDirty comparison still fires.

// The legacy value fields the request still carries are derived from the
// corresponding rows, so any consumer that still reads them (e.g. the
// account-status snapshot) stays consistent with the model.
function literalValueOf(name: string): string {
  const a = form.value.secuserAttributes.find(
    (x) => x.name.toLowerCase() === name.toLowerCase())
  return a && a.valueKind === 'LITERAL' ? a.value : ''
}

function yearsFromValidUntil(): number {
  const a = form.value.secuserAttributes.find((x) => x.name === 'secValidUntil')
  const m = a?.value.match(/nowPlusYears\(\s*(\d+)\s*\)/)
  return m ? Number(m[1]) : 100
}

function enabledOptionalNames(): string[] {
  return form.value.secuserAttributes
    .filter((a) => a.enabled && !isRequiredAttr(a.name))
    .map((a) => a.name)
}

async function save() {
  if (!canSave.value || saving.value) return
  saving.value = true
  try {
    const payload: UpsertIsvaConfigRequest = {
      enabled: form.value.enabled,
      topologyMode: form.value.topologyMode,
      // Legacy value fields, derived from the model rows so back-end readers
      // that still consult them stay consistent. The model below is
      // authoritative for what a grant actually writes.
      secAuthority: literalValueOf('secAuthority') || 'Default',
      secLoginType: literalValueOf('secLoginType') || 'Default',
      defaultValidUntilYears: yearsFromValidUntil(),
      requireSecGroup: form.value.requireSecGroup,
      // Applies to both modes; server normalizes (ensures secUser present).
      secuserObjectClasses: form.value.secuserObjectClasses,
      // Legacy: enabled optional names. Superseded by secuserAttributes.
      secuserOverlayAttributes: enabledOptionalNames(),
      // The authoritative per-attribute model.
      secuserAttributes: form.value.secuserAttributes,
      // Linked-only — server clears these when topologyMode = INLINE,
      // so it's safe to send the form values regardless.
      managementDitBaseDn: form.value.managementDitBaseDn.trim() || null,
      secuserRdnAttribute: form.value.secuserRdnAttribute.trim() || null,
      secuserRdnValueSource: form.value.secuserRdnValueSource,
      groupMemberTarget: form.value.groupMemberTarget,
    }
    const { data } = await upsertIsvaConfig(directoryId.value, payload)
    populateFromDto(data)
    // Reset pristine to the server-confirmed shape, which clears
    // isDirty and hides the action bar.
    pristine.value = snapshot(form.value)
    notif.success(`${IVIA_ABBR} integration saved.`)
  } catch (e: unknown) {
    notif.error(extractErrorMessage(e, 'Save failed.'))
  } finally {
    saving.value = false
  }
}

function discard() {
  // Restore the form to the last-loaded/saved snapshot. Action bar
  // disappears on its own because isDirty flips false.
  form.value = snapshot(pristine.value)
  // Clear any stale probe result that might have survived the edits
  // we're now throwing away.
  probeResult.value = null
}

async function probe() {
  if (probeDisabledReason.value) return
  probing.value = true
  probeResult.value = null
  try {
    const { data } = await probeIsvaConfig(directoryId.value)
    probeResult.value = data
  } catch (e: unknown) {
    notif.error(extractErrorMessage(e, 'Probe failed.'))
  } finally {
    probing.value = false
  }
}

// ── Navigation guards ──────────────────────────────────────────
//
// Two distinct guard surfaces — vue-router for in-app navigation,
// window.beforeunload for browser-level close / refresh / address-bar.
// The browser one can only show the native dialog (security model);
// the in-app one uses the project's custom ConfirmDialog for visual
// consistency with the rest of the app.

onBeforeRouteLeave(async () => {
  if (!isDirty.value) return true
  return await confirm({
    title: 'Discard unsaved changes?',
    message: `You have unsaved changes to the ${IVIA_ABBR} integration. `
      + 'Leaving the page will discard them.',
    confirmLabel: 'Discard and leave',
    danger: true,
  })
})

function beforeUnloadHandler(e: BeforeUnloadEvent) {
  if (!isDirty.value) return
  // Setting returnValue triggers the native "Leave site? Changes
  // you made may not be saved" prompt. Modern browsers ignore the
  // string content for security reasons — only the call itself
  // matters.
  e.preventDefault()
  e.returnValue = ''
}
onMounted(() => window.addEventListener('beforeunload', beforeUnloadHandler))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnloadHandler))

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
  <!-- Bottom padding leaves room for the sticky action bar so the
       last form fields aren't covered when the bar is up. -->
  <div class="max-w-3xl p-6 pb-28 space-y-6">
    <!-- Header + back button. Back is plain navigation; the route
         guard intercepts if the form is dirty. -->
    <header>
      <button
        @click="router.push('/superadmin/directories')"
        class="text-sm text-blue-600 hover:text-blue-700 mb-1"
      >&larr; Back to directories</button>
      <h1 class="text-xl font-semibold text-gray-900">
        {{ IVIA_NAME }} integration{{ directoryName ? ` - ${directoryName}` : '' }}
      </h1>
      <p class="text-sm text-gray-500">
        Configure the {{ IVIA_ABBR }} full-mode write-path overlay for this directory.
      </p>
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

        <div>
          <label class="label" for="newObjectClass">secUser object classes</label>
          <!-- Write-target phrasing tracks the selected topology so only the
               relevant mode's behaviour is described (inline overlays the
               demographic entry; linked stamps the paired secUser entry). -->
          <p class="text-xs text-gray-500 mb-2" data-testid="secuser-oc-help">
            Object classes written to the <code>secUser</code> identity,
            <template v-if="isLinkedMode">stamped on the paired <code>secUser</code> entry</template>
            <template v-else>overlaid onto the user's demographic entry</template>.
            <code>secUser</code> is required; add others your schema needs (e.g.
            <code>eUser</code>, which defines <code>principalName</code>).
          </p>
          <div class="flex flex-wrap gap-2 mb-2">
            <span
              v-for="oc in form.secuserObjectClasses"
              :key="oc"
              class="inline-flex items-center gap-1 rounded-full bg-gray-100 dark:bg-gray-700
                     px-2.5 py-1 text-xs font-medium text-gray-800 dark:text-gray-100"
            >
              <code>{{ oc }}</code>
              <button
                v-if="oc.toLowerCase() !== 'secuser'"
                type="button"
                class="text-gray-500 hover:text-red-600"
                :aria-label="`Remove ${oc}`"
                @click="removeObjectClass(oc)"
              >&times;</button>
              <span v-else class="text-gray-400" title="Required">&#128274;</span>
            </span>
          </div>
          <div class="flex gap-2">
            <input
              id="newObjectClass"
              v-model="newObjectClass"
              type="text"
              class="input flex-1"
              placeholder="Add one or more objectClasses — comma or space separated (e.g. eUser, inetOrgPerson)"
              @keydown.enter.prevent="addObjectClass"
            />
            <button type="button" class="btn-secondary" @click="addObjectClass">Add</button>
          </div>
        </div>

        <!-- Unified secUser attribute table: each attribute's On toggle, value
             kind, and literal/expression value in one place. secLoginType /
             secAuthority are required (MUST on stock secUser) and locked on. -->
        <div>
          <label class="label">secUser attributes</label>
          <p class="text-xs text-gray-500 mb-2" data-testid="secuser-attributes-help">
            Every <code>sec*</code> attribute a grant writes, and how each value
            is produced — a <strong>literal</strong>, or a <strong>computed</strong>
            expression evaluated per user.
            <code>secLoginType</code> and <code>secAuthority</code> are required
            by IBM's stock <code>secUser</code> and can't be unticked (their
            values stay editable). Untick any your directory's <code>secUser</code>
            schema doesn't permit — leaving one ticked that the schema forbids
            makes provisioning fail with <em>"attribute not allowed by
            objectClass secUser"</em>. Use <strong>Probe</strong> below to detect
            mismatches.
          </p>
          <div class="overflow-x-auto" data-testid="secuser-attributes-table">
            <div class="min-w-[34rem]">
              <div class="grid grid-cols-[1.5fr_auto_7rem_2fr] gap-x-3 items-center px-1 pb-1
                          text-xs font-medium text-gray-500 dark:text-gray-400
                          border-b border-gray-200 dark:border-gray-700">
                <span>Attribute</span>
                <span class="text-center">On</span>
                <span>Value kind</span>
                <span>Literal / expression</span>
              </div>
              <div
                v-for="attr in form.secuserAttributes"
                :key="attr.name"
                class="grid grid-cols-[1.5fr_auto_7rem_2fr] gap-x-3 items-start py-2
                       border-b border-gray-100 dark:border-gray-800"
                :data-testid="`attr-row-${attr.name}`"
              >
                <div class="self-center">
                  <code
                    class="text-sm text-gray-800 dark:text-gray-100 cursor-help
                           underline decoration-dotted decoration-gray-400 underline-offset-2"
                    :title="ATTR_HINTS[attr.name]"
                  >{{ attr.name }}</code>
                  <span v-if="isRequiredAttr(attr.name)" class="ml-1 text-gray-400"
                        title="Required — can't be unticked">&#128274;</span>
                </div>
                <div class="flex justify-center self-center">
                  <input
                    type="checkbox"
                    class="rounded"
                    v-model="attr.enabled"
                    :disabled="isRequiredAttr(attr.name)"
                    :aria-label="`Write ${attr.name}`"
                  />
                </div>
                <select
                  class="input"
                  v-model="attr.valueKind"
                  :disabled="!attr.enabled"
                  :aria-label="`${attr.name} value kind`"
                >
                  <option value="LITERAL">Literal</option>
                  <option value="COMPUTED">Computed</option>
                </select>
                <input
                  type="text"
                  class="input w-full font-mono text-xs"
                  v-model="attr.value"
                  :disabled="!attr.enabled"
                  :aria-label="`${attr.name} value`"
                  :placeholder="attr.valueKind === 'COMPUTED'
                    ? 'e.g. ${user.uid}' : 'literal value'"
                />
              </div>
            </div>
          </div>
          <details class="mt-2 text-xs text-gray-600 dark:text-gray-300"
                   data-testid="expression-help">
            <summary class="cursor-pointer text-gray-700 dark:text-gray-200">
              Expression reference
            </summary>
            <div class="mt-1 space-y-1 pl-1">
              <p>
                <strong>References</strong> —
                <code>&dollar;{user.&lt;attr&gt;}</code> a demographic attribute
                (e.g. <code>&dollar;{user.uid}</code>);
                <code>&dollar;{sec.&lt;attr&gt;}</code> another
                <code>secUser</code> attribute in this table (e.g.
                <code>&dollar;{sec.secAuthority}</code>).
              </p>
              <p>
                <strong>Functions</strong> — <code>uuid()</code> a fresh id;
                <code>now()</code> the current time;
                <code>nowPlusYears(n)</code> n years out.
              </p>
              <p>
                Literal text passes through, so
                <code>&dollar;{sec.secAuthority}%&dollar;{user.uid}</code> yields
                e.g. <code>Default%jdoe</code>.
              </p>
            </div>
          </details>
        </div>

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

        <div>
          <label class="label" for="managementDitBaseDn">Management DIT base DN (required)</label>
          <input id="managementDitBaseDn" type="text"
                 v-model="form.managementDitBaseDn"
                 class="input w-full" placeholder="secAuthority=Default,o=ibm,c=us" />
          <p class="text-xs text-gray-500 mt-1">
            Parent DN under which paired <code>secUser</code> entries live.
          </p>
        </div>

        <div>
          <label class="label" for="secuserRdnAttribute">secUser RDN attribute</label>
          <input id="secuserRdnAttribute" type="text"
                 v-model="form.secuserRdnAttribute"
                 class="input w-full" placeholder="secUUID" />
          <p class="text-xs text-gray-500 mt-1">
            The attribute that names <code>secUser</code> entries in the management
            DIT. <code>secUUID</code> (default) and <code>secLogin</code> are the
            stock choices; any attribute your configured object classes permit works
            (e.g. <code>principalName</code> from <code>eUser</code>).
          </p>
        </div>

        <fieldset>
          <legend class="text-sm font-medium text-gray-900 mb-2">RDN value source</legend>
          <div class="space-y-2">
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="rdnsource" value="GENERATED_UUID"
                     v-model="form.secuserRdnValueSource" class="mt-1" />
              <span>
                <span class="font-medium">Generated UUID</span> (default) — an opaque,
                immutable value per user. Pairs with <code>secUUID</code>.
              </span>
            </label>
            <label class="flex items-start gap-2 text-sm">
              <input type="radio" name="rdnsource" value="UID"
                     v-model="form.secuserRdnValueSource" class="mt-1" />
              <span>
                <span class="font-medium">User's <code>uid</code></span> — mirrors the
                login. Pairs with <code>secLogin</code> or a custom login-named
                attribute such as <code>principalName</code>. A <code>uid</code> rename
                then forces a directory rename of the entry.
              </span>
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
      </section>

      <!-- Probe — runs against the persisted config. Sits below the
           form so it's reachable without scrolling past linked-mode
           detail, and so the result panel lives next to its trigger. -->
      <section class="bg-white border border-gray-200 rounded-xl p-6 space-y-3">
        <div class="flex items-center justify-between">
          <div>
            <h2 class="text-base font-semibold text-gray-900">Probe management DIT</h2>
            <p class="text-xs text-gray-500 mt-0.5">
              Tests the <em>saved</em> config — reachability of the management DIT
              and presence of a sample <code>secUser</code> entry.
            </p>
          </div>
          <button
            type="button"
            class="btn-secondary"
            :disabled="!!probeDisabledReason"
            :title="probeDisabledReason ?? 'Probe the persisted config'"
            @click="probe"
          >
            {{ probing ? 'Probing…' : 'Probe' }}
          </button>
        </div>

        <div v-if="probeResult" class="border-t border-gray-100 pt-3 text-sm space-y-1.5">
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
          <p>
            secUser schema valid:
            <span :class="probeResult.schemaValid === true ? 'text-green-600 font-medium'
                          : probeResult.schemaValid === false ? 'text-red-600 font-medium'
                          : 'text-amber-600 font-medium'">
              {{ probeResult.schemaValid === true ? 'yes'
                 : probeResult.schemaValid === false ? 'no' : 'unknown' }}
            </span>
          </p>
          <!-- Code-vs-schema attribute mismatch: the attributes the app
               would write that the target secUser schema forbids (or the
               MUST attrs it requires that the app wouldn't write). This is
               the direct cause of "attribute not allowed" / "missing
               required attribute" provisioning failures. -->
          <div
            v-if="probeResult.disallowedWriteAttributes.length
                  || probeResult.missingRequiredAttributes.length"
            data-testid="probe-attr-mismatch"
            class="rounded-md border border-red-200 bg-red-50 dark:border-red-900 dark:bg-red-950/40 p-3 space-y-2"
          >
            <p class="font-medium text-red-700 dark:text-red-300">
              secUser attribute mismatches
            </p>
            <div v-if="probeResult.disallowedWriteAttributes.length">
              <p class="text-red-700 dark:text-red-300">
                Written by the app but <strong>not permitted</strong> by the
                target <code>secUser</code> schema — untick these under
                “secUser overlay attributes”:
              </p>
              <div class="flex flex-wrap gap-1.5 mt-1">
                <code
                  v-for="a in probeResult.disallowedWriteAttributes"
                  :key="a"
                  class="rounded bg-red-100 dark:bg-red-900/60 px-1.5 py-0.5 text-xs text-red-800 dark:text-red-200"
                >{{ a }}</code>
              </div>
            </div>
            <div v-if="probeResult.missingRequiredAttributes.length">
              <p class="text-red-700 dark:text-red-300">
                <strong>Required</strong> by the target <code>secUser</code>
                schema but not written by the app:
              </p>
              <div class="flex flex-wrap gap-1.5 mt-1">
                <code
                  v-for="a in probeResult.missingRequiredAttributes"
                  :key="a"
                  class="rounded bg-red-100 dark:bg-red-900/60 px-1.5 py-0.5 text-xs text-red-800 dark:text-red-200"
                >{{ a }}</code>
              </div>
            </div>
          </div>
          <ul v-if="probeResult.warnings.length" class="list-disc list-inside text-gray-600">
            <li v-for="w in probeResult.warnings" :key="w">{{ w }}</li>
          </ul>
        </div>
      </section>
    </form>

    <!-- Sticky action bar — visible only when the form has unsaved
         changes. Its visibility doubles as the dirty-state indicator;
         no separate badge needed. Fixed to the viewport bottom so the
         operator's eye finds it regardless of scroll position. -->
    <Transition
      enter-active-class="transition duration-150 ease-out"
      enter-from-class="translate-y-full"
      enter-to-class="translate-y-0"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="translate-y-0"
      leave-to-class="translate-y-full"
    >
      <div
        v-if="isDirty"
        class="fixed bottom-0 left-0 right-0 z-30 bg-white border-t border-gray-200 shadow-lg"
      >
        <div class="max-w-3xl mx-auto px-6 py-3 flex items-center justify-between gap-4">
          <span class="text-sm font-medium text-amber-700 inline-flex items-center gap-2">
            <span aria-hidden="true">●</span>
            Unsaved changes
          </span>
          <div class="flex items-center gap-2">
            <button
              type="button"
              class="btn-secondary"
              :disabled="saving"
              @click="discard"
            >Discard</button>
            <button
              type="button"
              class="btn-primary"
              :disabled="saving || !canSave"
              @click="save"
            >{{ saving ? 'Saving…' : 'Save' }}</button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
@reference "tailwindcss";
/* Field label, matched to the fieldset legends in this view
   (text-sm / font-medium / gray-900) so every labelled control reads
   the same. Without this the bare `class="label"` rendered unstyled. */
.label { @apply block text-sm font-medium text-gray-900 mb-1; }
</style>
