<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { IVIA_ABBR } from '@/constants/productNames'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import FormField from '@/components/FormField.vue'
import AppModal from '@/components/AppModal.vue'
import { getIsvaConfig } from '@/api/isvaConfig'
import { getEntryTimeline } from '@/api/audit'
import {
  getIsvaAccountStatus,
  grantIsvaAccount,
  revokeIsvaAccount,
  suspendIsvaAccount,
  restoreIsvaAccount,
  renewIsvaAccount,
  forceCredentialReset,
  type IsvaAccountStatus,
  type IsvaRefusalProblemDetail,
} from '@/api/isvaAccount'

// IVIA Account panel rendered as a tab inside UserForm.vue. Self-gates
// on (a) addon-present (auth.isIsvaIntegrationEnabled) and (b)
// directory has IVIA enabled — matches IsvaProfileOverrideControl.
// The host UserForm can mount it unconditionally; when not applicable
// it renders nothing.

const props = defineProps<{
  dirId: string
  dn: string
}>()

const emit = defineEmits<{
  // Bubbled so the parent header can refresh its IVIA badge.
  (e: 'status-changed', status: IsvaAccountStatus): void
}>()

const auth = useAuthStore()
const notif = useNotificationStore()

const visible = ref<boolean>(false)
const loading = ref<boolean>(false)
const acting = ref<boolean>(false)
const status = ref<IsvaAccountStatus | null>(null)

/** Inline error help — shape derived from a refusal `code`. Null when
 *  there's no pending refusal. Different codes get different CTAs. */
type InlineHelp = {
  klass: 'alert-amber' | 'alert-red' | 'alert-blue'
  title: string
  body: string
  retryable?: boolean
}
const inlineHelp = ref<InlineHelp | null>(null)

interface AuditEvent {
  id: string | number
  action: string
  occurredAt: string
  actorUsername: string | null
  detail: Record<string, unknown> | null
}
const lifecycle = ref<AuditEvent[]>([])
const lifecycleLoading = ref<boolean>(false)

// Renew + Revoke dialogs
const showRenewDialog = ref<boolean>(false)
const renewDate = ref<string>('')
const showRevokeHardConfirm = ref<boolean>(false)
const revokeTypedName = ref<string>('')

/** uid-or-displayName extracted from the DN, used for the HARD-revoke
 *  type-to-confirm challenge. */
const accountName = computed<string>(() => {
  // First RDN value, e.g. uid=alice.contractor,... → "alice.contractor"
  const rdn = props.dn.split(',')[0] ?? ''
  const eq = rdn.indexOf('=')
  return eq >= 0 ? rdn.slice(eq + 1) : rdn
})

// ── load + reload ───────────────────────────────────────────────

async function load(): Promise<void> {
  visible.value = false
  inlineHelp.value = null
  if (!auth.isIsvaIntegrationEnabled || !props.dirId || !props.dn) {
    return
  }
  loading.value = true
  try {
    // Directory must have an active IVIA config; mirror the
    // IsvaProfileOverrideControl gating exactly so a community-plus-isva
    // deployment with IVIA off on this directory hides the panel.
    const cfg = await getIsvaConfig(props.dirId)
    if (!cfg.data?.enabled) {
      return
    }
    visible.value = true
    const s = await getIsvaAccountStatus(props.dirId, props.dn)
    status.value = s.data
    emit('status-changed', s.data)
    void loadLifecycle()
  } catch (err) {
    const code = readRefusalCode(err)
    if (code === 'ivia_directory_disabled') {
      // Treat exactly like the "config row absent" case — hide the tab.
      visible.value = false
      return
    }
    visible.value = true
    handleError(err)
  } finally {
    loading.value = false
  }
}

async function loadLifecycle(): Promise<void> {
  lifecycleLoading.value = true
  try {
    const r = await getEntryTimeline(props.dirId, props.dn, { size: 50 })
    const events = (r.data?.content ?? r.data ?? []) as AuditEvent[]
    lifecycle.value = events.filter((e) => {
      const src = e.detail?.['source']
      return typeof src === 'string' && src === 'ivia'
    })
  } catch {
    // The timeline is decorative — a failed load shouldn't break the panel.
    lifecycle.value = []
  } finally {
    lifecycleLoading.value = false
  }
}

watch(
  () => [props.dirId, props.dn] as const,
  () => { void load() },
  { immediate: true },
)

// ── action handlers ─────────────────────────────────────────────

async function doGrant(): Promise<void> {
  await runVerb(() => grantIsvaAccount(props.dirId, props.dn),
    `${IVIA_ABBR} account granted.`)
}

async function doSuspend(): Promise<void> {
  await runVerb(() => suspendIsvaAccount(props.dirId, props.dn),
    `${IVIA_ABBR} account suspended.`)
}

async function doRestore(): Promise<void> {
  await runVerb(() => restoreIsvaAccount(props.dirId, props.dn),
    `${IVIA_ABBR} account restored.`)
}

async function doForceReset(): Promise<void> {
  await runVerb(() => forceCredentialReset(props.dirId, props.dn),
    `${IVIA_ABBR} credential reset required on next sign-in.`)
}

async function doRevokeSoft(): Promise<void> {
  await runVerb(() => revokeIsvaAccount(props.dirId, props.dn, 'SOFT'),
    `${IVIA_ABBR} account revoked (soft).`)
}

async function doRevokeHard(): Promise<void> {
  if (revokeTypedName.value !== accountName.value) return
  await runVerb(() => revokeIsvaAccount(props.dirId, props.dn, 'HARD'),
    `${IVIA_ABBR} account revoked (hard).`)
  showRevokeHardConfirm.value = false
  revokeTypedName.value = ''
}

function openRenewDialog(): void {
  // Default to today + defaultValidUntilYears from the existing IVIA
  // status hint if available; otherwise 1 year out. Capped at +10y on
  // the server.
  const base = new Date()
  base.setFullYear(base.getFullYear() + 1)
  renewDate.value = base.toISOString().slice(0, 10)
  showRenewDialog.value = true
}

async function doRenew(): Promise<void> {
  if (!renewDate.value) return
  // Convert YYYY-MM-DD to an ISO instant at end-of-day UTC; the
  // backend accepts OffsetDateTime.
  const iso = `${renewDate.value}T23:59:59Z`
  await runVerb(() => renewIsvaAccount(props.dirId, props.dn, iso),
    `${IVIA_ABBR} account renewed.`)
  showRenewDialog.value = false
}

async function runVerb(
  call: () => Promise<{ data: IsvaAccountStatus }>,
  successMessage: string,
): Promise<void> {
  acting.value = true
  inlineHelp.value = null
  try {
    const r = await call()
    status.value = r.data
    emit('status-changed', r.data)
    notif.success(successMessage)
    void loadLifecycle()
  } catch (err) {
    handleError(err)
  } finally {
    acting.value = false
  }
}

// ── error → inline help mapping ─────────────────────────────────

function readRefusalCode(err: unknown): IsvaRefusalProblemDetail['code'] | null {
  const e = err as { response?: { data?: Partial<IsvaRefusalProblemDetail> } }
  const c = e.response?.data?.code
  return c ?? null
}

function handleError(err: unknown): void {
  const code = readRefusalCode(err)
  const detail = (err as { response?: { data?: { detail?: string } } }).response?.data?.detail
  switch (code) {
    case 'ivia_force_off':
      inlineHelp.value = {
        klass: 'alert-amber',
        title: 'This profile is exempt from IVIA',
        body:
          'Flip the override under Profiles → [profile] → Policy → IVIA Integration, then retry.',
      }
      break
    case 'ivia_directory_disabled':
      inlineHelp.value = {
        klass: 'alert-amber',
        title: 'IVIA is disabled on this directory',
        body: 'Enable it under Directory → IVIA Integration, then retry.',
      }
      break
    case 'ivia_state_changed':
      inlineHelp.value = {
        klass: 'alert-blue',
        title: 'IVIA state changed under you',
        body: 'Another operator updated this account while you were viewing this page.',
        retryable: true,
      }
      break
    case 'ivia_orphan':
      inlineHelp.value = {
        klass: 'alert-amber',
        title: 'No IVIA account exists for this identity',
        body: 'Run Integrity reconcile to repair, or Grant a new account.',
      }
      break
    case 'ivia_already_linked':
    case 'ivia_renew_not_forward':
      // These are recoverable input-shaped errors; a toast keeps the
      // panel state clean and the message readable.
      notif.error(detail ?? 'Request refused.')
      break
    default:
      notif.error(detail ?? 'Request failed.')
  }
}

async function retryAfterStateChange(): Promise<void> {
  inlineHelp.value = null
  await load()
}

// ── render-state derivations ───────────────────────────────────

/** Disabled-button helpers, mirror the idempotency policy table. */
const canGrant = computed<boolean>(() => !!status.value?.orphaned)
const canSuspend = computed<boolean>(
  () => !!status.value && status.value.linked && status.value.acctValid,
)
const canRestore = computed<boolean>(
  () => !!status.value && status.value.linked && !status.value.acctValid,
)
const canRenew = computed<boolean>(() => !!status.value && status.value.linked)
const canForceReset = computed<boolean>(
  () => !!status.value && status.value.linked && status.value.pwdValid,
)
const canRevoke = computed<boolean>(() => !!status.value && status.value.linked)

const stateBadges = computed(() => {
  const s = status.value
  if (!s) return []
  if (s.orphaned) {
    return [{ label: 'Orphaned', klass: 'badge-yellow', dot: 'bg-yellow-500' }]
  }
  const badges = [{ label: 'Linked', klass: 'badge-green', dot: 'bg-green-500' }]
  badges.push(
    s.acctValid
      ? { label: 'Active', klass: 'badge-green', dot: 'bg-green-500' }
      : { label: 'Suspended', klass: 'badge-red', dot: 'bg-red-500' },
  )
  return badges
})

const pwdAgeDays = computed<number | null>(() => {
  const t = status.value?.pwdLastChanged
  if (!t) return null
  const ms = Date.now() - new Date(t).getTime()
  return Math.floor(ms / (1000 * 60 * 60 * 24))
})

/** Maps an audit row to a one-line lifecycle entry. ivia_op carries the
 *  semantic verb; the AuditAction is just the umbrella category. */
function lifecycleLabel(e: AuditEvent): string {
  const op = e.detail?.['ivia_op']
  switch (op) {
    case 'grant':         return 'Granted'
    case 'revoke_soft':   return 'Revoked (soft)'
    case 'revoke_hard':   return 'Revoked (hard)'
    case 'suspend':       return 'Suspended'
    case 'restore':       return 'Restored'
    case 'renew':         return 'Renewed'
    case 'force_reset':   return 'Force credential reset'
    default:              return typeof op === 'string' ? op : (e.action || 'IVIA event')
  }
}

function lifecycleDot(e: AuditEvent): string {
  const op = e.detail?.['ivia_op']
  if (op === 'revoke_hard' || op === 'revoke_soft') return 'bg-red-500'
  if (op === 'suspend' || op === 'force_reset') return 'bg-yellow-500'
  return 'bg-blue-500'
}

function formatTime(s: string): string {
  // Render in the browser's local zone but make zone explicit. The
  // audit endpoint returns ISO-8601 with offset.
  try {
    const d = new Date(s)
    return d.toLocaleString(undefined, {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    })
  } catch {
    return s
  }
}

function formatDate(s: string | null): string {
  if (!s) return '—'
  try {
    return new Date(s).toISOString().slice(0, 10)
  } catch {
    return s
  }
}
</script>

<template>
  <div v-if="!visible" />
  <div v-else class="space-y-5">
    <!-- Loading state — keep the layout stable so the tab doesn't
         flash when status reloads after a verb. -->
    <div v-if="loading && !status" class="text-sm text-gray-500 py-6 text-center">
      Loading…
    </div>

    <template v-if="status">
      <!-- At-a-glance -->
      <div class="rounded-lg border border-gray-200 bg-gray-50 p-4">
        <div class="text-xs uppercase tracking-wider font-semibold text-gray-500 mb-3">
          At a glance
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <span v-for="b in stateBadges" :key="b.label" :class="b.klass">
            <span class="inline-block w-1.5 h-1.5 rounded-full mr-1" :class="b.dot"></span>
            {{ b.label }}
          </span>
          <span v-if="status.linked && status.daysRemaining != null"
                class="inline-flex items-center gap-1.5 rounded-md border border-gray-200 bg-white px-2.5 py-1 text-xs font-medium text-gray-700 shadow-sm">
            <span class="text-gray-400" aria-hidden="true">⏱</span>
            <template v-if="status.daysRemaining >= 0">
              Expires in <strong class="font-semibold text-gray-900 ml-0.5">{{ status.daysRemaining }} days</strong>
            </template>
            <template v-else>
              Expired <strong class="font-semibold text-red-700 ml-0.5">{{ -status.daysRemaining }} days ago</strong>
            </template>
          </span>
          <span v-if="status.linked && pwdAgeDays !== null"
                class="inline-flex items-center gap-1.5 rounded-md border border-gray-200 bg-white px-2.5 py-1 text-xs font-medium text-gray-700 shadow-sm">
            <span class="text-gray-400" aria-hidden="true">🔑</span>
            Password set <strong class="font-semibold text-gray-900 ml-0.5">{{ pwdAgeDays }} days ago</strong>
          </span>
        </div>
      </div>

      <!-- Inline help (refusal codes) -->
      <div v-if="inlineHelp" :class="inlineHelp.klass" class="rounded-md p-3 text-sm flex items-start gap-2">
        <span aria-hidden="true" class="mt-0.5">⚠</span>
        <div class="flex-1">
          <div class="font-medium">{{ inlineHelp.title }}</div>
          <div class="text-xs mt-0.5">{{ inlineHelp.body }}</div>
          <button v-if="inlineHelp.retryable" @click="retryAfterStateChange"
                  class="mt-2 text-xs font-medium underline">
            Refresh
          </button>
        </div>
      </div>

      <!-- Orphan alert + Grant CTA -->
      <div v-if="status.orphaned" class="alert-amber rounded-md p-3 text-sm flex items-start gap-2">
        <span aria-hidden="true" class="mt-0.5">⚠</span>
        <div>
          <div class="font-medium">This identity has no {{ IVIA_ABBR }} account.</div>
          <div class="text-xs mt-0.5">
            Grant one now, or run Integrity reconcile to repair in bulk.
          </div>
        </div>
      </div>

      <!-- Lifecycle -->
      <div class="rounded-lg border border-gray-200 bg-gray-50 p-4">
        <div class="text-xs uppercase tracking-wider font-semibold text-gray-500 mb-3">
          Lifecycle
        </div>
        <p v-if="lifecycleLoading" class="text-xs text-gray-500 italic">Loading…</p>
        <p v-else-if="lifecycle.length === 0" class="text-sm text-gray-500 italic">
          No {{ IVIA_ABBR }} events recorded for this DN.
        </p>
        <ol v-else class="ml-2 text-sm space-y-2.5">
          <li v-for="e in lifecycle" :key="e.id" class="relative pl-5">
            <span class="absolute left-0 top-1.5 w-2.5 h-2.5 rounded-full ring-2 ring-white"
                  :class="lifecycleDot(e)" aria-hidden="true"></span>
            <div class="flex items-baseline justify-between flex-wrap gap-2">
              <div>
                <span class="font-medium text-gray-900">{{ lifecycleLabel(e) }}</span>
                <span v-if="e.actorUsername" class="text-gray-500">
                  · by <code class="text-xs">{{ e.actorUsername }}</code>
                </span>
              </div>
              <time class="text-xs text-gray-500">{{ formatTime(e.occurredAt) }}</time>
            </div>
          </li>
        </ol>
        <p v-if="status.validUntil && !status.orphaned" class="text-xs text-gray-500 mt-3">
          Scheduled expiry: <code>{{ formatDate(status.validUntil) }}</code>
        </p>
      </div>

      <!-- Actions -->
      <div class="flex flex-wrap items-center gap-2 pt-1">
        <button v-if="status.orphaned" @click="doGrant" :disabled="acting || !canGrant" class="btn-primary">
          Grant {{ IVIA_ABBR }} account
        </button>
        <button v-if="canSuspend" @click="doSuspend" :disabled="acting" class="btn-secondary">
          Suspend
        </button>
        <button v-if="canRestore" @click="doRestore" :disabled="acting" class="btn-primary">
          Restore
        </button>
        <button @click="openRenewDialog" :disabled="acting || !canRenew" class="btn-secondary">
          Renew
        </button>
        <button @click="doForceReset" :disabled="acting || !canForceReset" class="btn-secondary">
          Force credential reset
        </button>
        <div v-if="canRevoke" class="ml-auto flex items-center gap-2">
          <button @click="doRevokeSoft" :disabled="acting" class="btn-danger-soft">
            Revoke (soft)
          </button>
          <button @click="showRevokeHardConfirm = true" :disabled="acting" class="btn-danger">
            Revoke (hard)
          </button>
        </div>
      </div>

      <p v-if="canForceReset" class="text-xs text-gray-500">
        Force-reset writes <code>secPwdValid=FALSE</code>. In <strong>linked</strong> mode the
        {{ IVIA_ABBR }} bind path is invalidated; in <strong>inline</strong> mode the
        plain-LDAP bind still succeeds but {{ IVIA_ABBR }} may refuse.
      </p>
    </template>

    <!-- Renew dialog -->
    <AppModal v-model="showRenewDialog" :title="`Renew ${IVIA_ABBR} account`" size="sm">
      <div class="space-y-3">
        <p class="text-sm text-gray-600">
          Pick the new <code>secValidUntil</code>. Must be after the current value
          and within 10 years from today.
        </p>
        <FormField label="Valid until" type="date" v-model="renewDate" required />
      </div>
      <template #footer>
        <button @click="showRenewDialog = false" class="btn-neutral">Cancel</button>
        <button @click="doRenew" :disabled="acting || !renewDate" class="btn-primary">
          {{ acting ? 'Renewing…' : 'Renew' }}
        </button>
      </template>
    </AppModal>

    <!-- HARD revoke confirm -->
    <AppModal v-model="showRevokeHardConfirm" :title="`Revoke ${IVIA_ABBR} account (hard)`" size="sm">
      <div class="space-y-3">
        <p class="text-sm text-red-700">
          This deletes the {{ IVIA_ABBR }} account outright. The underlying identity
          stays, but all {{ IVIA_ABBR }}-side state is removed.
        </p>
        <p class="text-sm text-gray-600">
          Type <code class="bg-gray-100 px-1 py-0.5 rounded">{{ accountName }}</code> to confirm.
        </p>
        <FormField label="Account name" v-model="revokeTypedName" />
      </div>
      <template #footer>
        <button @click="showRevokeHardConfirm = false" class="btn-neutral">Cancel</button>
        <button
          @click="doRevokeHard"
          :disabled="acting || revokeTypedName !== accountName"
          class="btn-danger"
        >
          {{ acting ? 'Revoking…' : 'Revoke (hard)' }}
        </button>
      </template>
    </AppModal>
  </div>
</template>
