<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">API Tokens</h1>
        <p class="text-sm text-gray-500 mt-1">
          Long-lived bearer tokens for machine-to-machine and IaC automation. The secret is
          shown once at creation or rotation — store it immediately.
        </p>
      </div>
      <button @click="openCreate" class="btn-primary">+ New token</button>
    </div>

    <div class="flex items-center gap-3 mb-3">
      <label class="flex items-center gap-2 text-sm text-gray-600 cursor-pointer select-none">
        <input type="checkbox" v-model="includeRevoked" @change="load"
               class="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
        Show revoked
      </label>
    </div>

    <DataTable :columns="cols" :rows="tokens" :loading="loading" row-key="id"
               empty-text="No API tokens yet.">
      <template #cell-name="{ row }">
        <div>
          <p class="font-medium text-gray-900">{{ (row as ApiTokenResponse).name }}</p>
          <p v-if="(row as ApiTokenResponse).description" class="cell-muted">
            {{ (row as ApiTokenResponse).description }}
          </p>
        </div>
      </template>
      <template #cell-tokenPrefix="{ value }">
        <span class="font-mono text-xs text-gray-600">{{ value }}…</span>
      </template>
      <template #cell-status="{ value }">
        <span :class="statusBadge(value as string)">{{ value }}</span>
      </template>
      <template #cell-createdAt="{ row }">
        <div>
          <span class="cell-muted">{{ fmtDate((row as ApiTokenResponse).createdAt) }}</span>
          <p class="cell-muted">by {{ (row as ApiTokenResponse).createdByUsername }}</p>
        </div>
      </template>
      <template #cell-expiresAt="{ value }">
        <span class="cell-muted">{{ fmtDate(value as string | undefined) }}</span>
      </template>
      <template #cell-lastUsedAt="{ value }">
        <span class="cell-muted">{{ value ? fmtDateTime(value as string) : 'never' }}</span>
      </template>
      <template #actions="{ row }">
        <ActionMenu :items="rowActions(row as ApiTokenResponse)" />
      </template>
    </DataTable>

    <!-- Create modal -->
    <AppModal v-model="showCreate" title="Create API token" size="lg">
      <FormValidationSummary v-if="showSummary" :errors="validationSummary" />

      <form @submit.prevent="submitCreate" class="space-y-0.5">
        <FormField label="Name" v-model="form.name" required
          field-key="name" :error="fieldErrors.name"
          placeholder="e.g. ci-terraform"
          hint="A short identifier for where this token is used." />
        <FormField label="Description" v-model="form.description" type="textarea" :rows="2"
          field-key="description" :error="fieldErrors.description"
          placeholder="What is this token for? (optional)" />

        <div class="mb-2">
          <label class="block text-sm font-medium text-gray-700 mb-1">
            Expires <span class="text-red-500">*</span>
          </label>
          <div class="flex flex-wrap gap-2" data-field="expiresAt">
            <button v-for="p in PRESETS" :key="p.days" type="button"
              @click="selectPreset(p.days)"
              :class="[
                'px-3 py-1.5 rounded-full text-sm border transition-colors',
                expiryMode === p.days
                  ? 'bg-blue-50 border-blue-500 text-blue-700 font-medium'
                  : 'bg-gray-50 border-gray-200 text-gray-700 hover:bg-gray-100',
              ]">{{ p.label }}</button>
            <button type="button" @click="selectCustom"
              :class="[
                'px-3 py-1.5 rounded-full text-sm border transition-colors',
                expiryMode === 'custom'
                  ? 'bg-blue-50 border-blue-500 text-blue-700 font-medium'
                  : 'bg-gray-50 border-gray-200 text-gray-700 hover:bg-gray-100',
              ]">Custom…</button>
          </div>
          <input v-if="expiryMode === 'custom'" type="datetime-local"
            v-model="customExpiry" :min="minLocal" :max="maxLocal"
            class="input block w-full mt-2" aria-label="Custom expiry date and time" />
          <p v-if="expiryError" class="mt-1 text-xs text-red-500">{{ expiryError }}</p>
          <p v-else-if="expiresAtIso" class="mt-1 text-xs text-gray-500">
            Expires {{ fmtDateTime(expiresAtIso) }}
          </p>
          <p v-else class="mt-1 text-xs text-gray-500">Maximum lifetime is 2 years from now.</p>
        </div>
      </form>

      <template #footer>
        <button @click="showCreate = false" class="btn-neutral">Cancel</button>
        <button @click="submitCreate" :disabled="saving || !form.name.trim()" class="btn-primary">
          {{ saving ? 'Creating…' : 'Create token' }}
        </button>
      </template>
    </AppModal>

    <!-- Show-once secret modal (shared by create + rotate) -->
    <AppModal v-model="showSecret" :title="secretTitle" size="md">
      <p class="text-sm text-gray-600">
        Copy this secret now. For security it is hashed at rest and
        <strong>cannot be shown again</strong>.
      </p>
      <div class="mt-3 flex items-center gap-2 bg-gray-50 border border-gray-200 rounded-lg px-3 py-2">
        <code class="flex-1 font-mono text-xs break-all text-gray-800">{{ secretValue }}</code>
        <CopyButton :text="secretValue" />
      </div>
      <p class="mt-3 text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
        This is the only time the full token is visible. Losing it means rotating or re-creating
        the token.
      </p>
      <template #footer>
        <button @click="showSecret = false" class="btn-primary">Done</button>
      </template>
    </AppModal>

    <!-- Revoke confirm -->
    <ConfirmDialog
      v-model="showRevoke"
      title="Revoke token"
      :message="`Revoke '${revokeTarget?.name}'? Any automation using this token will immediately start receiving 401 responses. This cannot be undone.`"
      confirm-label="Revoke"
      danger
      @confirm="doRevoke"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type { Ref } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import {
  listApiTokens,
  createApiToken,
  rotateApiToken,
  revokeApiToken,
  type ApiTokenResponse,
} from '@/api/apiTokens'
import DataTable from '@/components/DataTable.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import AppModal from '@/components/AppModal.vue'
import FormField from '@/components/FormField.vue'
import FormValidationSummary from '@/components/FormValidationSummary.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import CopyButton from '@/components/CopyButton.vue'
import { useFormErrors, serverFieldErrors } from '@/composables/useFormErrors'

interface CreateForm {
  name: string
  description: string
}

interface ApiError {
  response?: { data?: { detail?: string } }
  message?: string
}

function errMsg(e: unknown, fallback: string): string {
  const err = e as ApiError
  return err.response?.data?.detail || err.message || fallback
}

const notif = useNotificationStore()

// Friendly labels for the server's validation field keys (the
// CreateApiTokenRequest record components) so a 400 surfaces inline + in the
// summary banner rather than a generic toast.
const FIELD_LABELS: Record<string, string> = {
  name: 'Name',
  description: 'Description',
  expiresAt: 'Expiry',
}

const {
  errors: fieldErrors,
  summary: validationSummary,
  showSummary,
  setErrors,
  clear: clearFieldErrors,
  report: reportErrors,
} = useFormErrors({ labelFor: (k) => FIELD_LABELS[k] ?? k })

const cols = [
  { key: 'name', label: 'Name' },
  { key: 'tokenPrefix', label: 'Prefix' },
  { key: 'status', label: 'Status' },
  { key: 'createdAt', label: 'Created' },
  { key: 'expiresAt', label: 'Expires' },
  { key: 'lastUsedAt', label: 'Last used' },
]

const DAY_MS = 86_400_000
const PRESETS = [
  { label: '30 days', days: 30 },
  { label: '90 days', days: 90 },
  { label: '1 year', days: 365 },
  { label: '2 years', days: 730 },
] as const

const loading = ref(false)
const saving = ref(false)
const includeRevoked = ref(false)
const tokens: Ref<ApiTokenResponse[]> = ref([])

// ── Status helpers ───────────────────────────────────────────────────────────

function statusBadge(status: string): string {
  if (status === 'ACTIVE') return 'badge-green'
  if (status === 'REVOKED') return 'badge-red'
  return 'badge-gray' // EXPIRED
}

function fmtDate(iso?: string): string {
  return iso ? new Date(iso).toLocaleDateString() : '—'
}
function fmtDateTime(iso: string): string {
  return new Date(iso).toLocaleString()
}

// ── Row actions ──────────────────────────────────────────────────────────────

function rowActions(row: ApiTokenResponse) {
  return [
    { label: 'Rotate', onClick: () => rotate(row), hidden: row.status !== 'ACTIVE' },
    {
      label: 'Revoke',
      onClick: () => confirmRevoke(row),
      danger: true,
      hidden: row.status === 'REVOKED',
    },
  ]
}

// ── Load ──────────────────────────────────────────────────────────────────────

async function load(): Promise<void> {
  loading.value = true
  try {
    const { data } = await listApiTokens(includeRevoked.value)
    tokens.value = data
  } catch (e) {
    notif.error(errMsg(e, 'Failed to load API tokens'))
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ── Create + expiry picker ─────────────────────────────────────────────────────

const showCreate = ref(false)
const form: Ref<CreateForm> = ref({ name: '', description: '' })
const expiryMode = ref<number | 'custom' | null>(null)
const customExpiry = ref('') // datetime-local string
const expiryError = ref('')

// datetime-local bounds: now → now + 2 years.
const minLocal = computed(() => toLocalInput(new Date(Date.now() + 60_000)))
const maxLocal = computed(() => toLocalInput(new Date(Date.now() + 730 * DAY_MS)))

// Resolved expiry as an ISO instant, or '' when nothing valid is selected.
const expiresAtIso = computed<string>(() => {
  if (expiryMode.value === 'custom') {
    if (!customExpiry.value) return ''
    const d = new Date(customExpiry.value)
    return isNaN(d.getTime()) ? '' : d.toISOString()
  }
  if (typeof expiryMode.value === 'number') {
    return new Date(Date.now() + expiryMode.value * DAY_MS).toISOString()
  }
  return ''
})

function toLocalInput(d: Date): string {
  // Format a Date as the local-time 'YYYY-MM-DDTHH:mm' a datetime-local wants.
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + `T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function selectPreset(days: number): void {
  expiryMode.value = days
  expiryError.value = ''
}
function selectCustom(): void {
  expiryMode.value = 'custom'
  expiryError.value = ''
}

function openCreate(): void {
  clearFieldErrors()
  form.value = { name: '', description: '' }
  expiryMode.value = null
  customExpiry.value = ''
  expiryError.value = ''
  showCreate.value = true
}

async function submitCreate(): Promise<void> {
  if (!form.value.name.trim()) return
  clearFieldErrors()
  expiryError.value = ''

  const expiresAt = expiresAtIso.value
  const now = Date.now()
  if (!expiresAt) {
    expiryError.value = 'Pick an expiry.'
    return
  }
  const expMs = new Date(expiresAt).getTime()
  if (expMs <= now) {
    expiryError.value = 'Expiry must be in the future.'
    return
  }
  if (expMs > now + 730 * DAY_MS + 60_000) {
    expiryError.value = 'Expiry cannot be more than 2 years from now.'
    return
  }

  saving.value = true
  try {
    const { data } = await createApiToken({
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      expiresAt,
    })
    showCreate.value = false
    await load()
    revealSecret('Token created', data.plaintext)
  } catch (e) {
    const fieldErrs = serverFieldErrors(e)
    if (fieldErrs) {
      setErrors(fieldErrs)
      reportErrors()
    } else {
      notif.error(errMsg(e, 'Failed to create token'))
    }
  } finally {
    saving.value = false
  }
}

// ── Rotate ──────────────────────────────────────────────────────────────────

async function rotate(row: ApiTokenResponse): Promise<void> {
  if (!row.id) return
  try {
    const { data } = await rotateApiToken(row.id)
    await load()
    revealSecret(`Token rotated — “${row.name}”`, data.plaintext)
    notif.success('Secret rotated. The previous secret no longer works.')
  } catch (e) {
    notif.error(errMsg(e, 'Failed to rotate token'))
  }
}

// ── Revoke ──────────────────────────────────────────────────────────────────

const showRevoke = ref(false)
const revokeTarget: Ref<ApiTokenResponse | null> = ref(null)

function confirmRevoke(row: ApiTokenResponse): void {
  revokeTarget.value = row
  showRevoke.value = true
}

async function doRevoke(): Promise<void> {
  const target = revokeTarget.value
  if (!target?.id) return
  try {
    await revokeApiToken(target.id)
    notif.success('Token revoked.')
    await load()
  } catch (e) {
    notif.error(errMsg(e, 'Failed to revoke token'))
  } finally {
    revokeTarget.value = null
  }
}

// ── Show-once secret ───────────────────────────────────────────────────────

const showSecret = ref(false)
const secretTitle = ref('')
const secretValue = ref('')

function revealSecret(title: string, plaintext?: string): void {
  if (!plaintext) return
  secretTitle.value = title
  secretValue.value = plaintext
  showSecret.value = true
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
