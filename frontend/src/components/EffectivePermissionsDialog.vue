<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { Ref } from 'vue'
import AppModal from './AppModal.vue'
import { getEffectivePermissions, getPermissions } from '@/api/adminManagement'
import { setFeaturePermissions } from '@/api/adminPermissions'
import { featurePermissionLabel } from '@/constants/featurePermissions'

const props = withDefaults(defineProps<{
  modelValue: boolean
  adminId?: string | null
  adminLabel?: string // e.g. username — used in the modal title
}>(), { adminId: null, adminLabel: '' })
defineEmits<{ 'update:modelValue': [value: boolean] }>()

// ── API response shapes (mirror the backend EffectivePermissionsResponse) ──
interface FeatureEffective {
  key: string
  dbValue: string
  allowed: boolean
  source: string
}
interface ProfileEffective {
  profileId: string
  profileName: string
  directoryId: string | null
  directoryName: string | null
  baseRole: string
  targetUserDn: string | null
  features: FeatureEffective[]
}
interface EffectiveData {
  adminId: string
  username: string
  role: string
  superadmin: boolean
  profiles: ProfileEffective[]
}
interface FeatureOverride {
  featureKey: string
  enabled: boolean
  profileId: string | null
}

// Repo-standard axios/native error narrowing (see docs/frontend-conventions.md).
function errMsg(e: unknown, fallback: string): string {
  const err = e as { response?: { data?: { detail?: string } }; message?: string }
  return err.response?.data?.detail || err.message || fallback
}

const loading = ref<boolean>(false)
const saving  = ref<boolean>(false)
const error   = ref<string | null>(null)
const data: Ref<EffectiveData | null> = ref(null)

async function refresh(): Promise<void> {
  if (!props.adminId) return
  loading.value = true
  error.value   = null
  try {
    const { data: resp } = await getEffectivePermissions(props.adminId)
    data.value = resp as EffectiveData
  } catch (e) {
    error.value = errMsg(e, 'Failed to load effective permissions')
  } finally {
    loading.value = false
  }
}

watch(() => props.modelValue, (open) => { if (open) refresh() })

/**
 * Set or clear a per-profile override. The PUT /permissions/features
 * endpoint replaces the full override list in one call, so we round-trip
 * through getPermissions, mutate the list, and POST it back. The backend
 * diffs the new set against the stored one in place, so flipping an existing
 * override updates the row rather than delete+reinsert.
 */
async function setProfileOverride(
  profileId: string,
  featureKey: string,
  next: 'allow' | 'deny' | 'clear',
): Promise<void> {
  if (saving.value || !props.adminId) return
  saving.value = true
  try {
    const { data: current } = await getPermissions(props.adminId)
    const overrides: FeatureOverride[] = ((current.featurePermissions || []) as FeatureOverride[])
      .filter(p => !(p.featureKey === featureKey && (p.profileId || null) === profileId))
    if (next !== 'clear') {
      overrides.push({ featureKey, enabled: next === 'allow', profileId })
    }
    await setFeaturePermissions(props.adminId, overrides)
    await refresh()
  } catch (e) {
    error.value = errMsg(e, 'Failed to update override')
  } finally {
    saving.value = false
  }
}

// ── Source → UI styling ─────────────────────────────────────────────────
// Each PermissionSource gets a chip colour and a plain-English tooltip so
// the user can understand why each feature resolved the way it did.
interface SourceMeta { chip: string; label: string; hint: string }
const SOURCE_META: Record<string, SourceMeta> = {
  PROFILE_OVERRIDE_ALLOW:       { chip: 'bg-emerald-100 text-emerald-800', label: 'Profile override',  hint: 'Explicitly allowed for this admin in this profile.' },
  PROFILE_OVERRIDE_DENY:        { chip: 'bg-rose-100 text-rose-800',       label: 'Profile override',  hint: 'Explicitly denied for this admin in this profile.' },
  ADMIN_OVERRIDE_ALLOW:         { chip: 'bg-emerald-100 text-emerald-800', label: 'Admin override',    hint: 'Explicitly allowed for this admin across all profiles.' },
  ADMIN_OVERRIDE_DENY:          { chip: 'bg-rose-100 text-rose-800',       label: 'Admin override',    hint: 'Explicitly denied for this admin across all profiles.' },
  BASE_ROLE_ADMIN:              { chip: 'bg-blue-100 text-blue-800',       label: 'Base role: ADMIN',  hint: "Granted by the profile's ADMIN base role." },
  BASE_ROLE_READ_ONLY_DEFAULT:  { chip: 'bg-slate-100 text-slate-700',     label: 'Read-only default', hint: 'Read-only base role grants this feature by default.' },
  BASE_ROLE_DENIED:             { chip: 'bg-slate-100 text-slate-500',     label: 'Base role: READ_ONLY', hint: 'Read-only base role does not grant this feature.' },
}
function sourceChip(src: string): string  { return SOURCE_META[src]?.chip  || 'bg-gray-100 text-gray-700' }
function sourceLabel(src: string): string { return SOURCE_META[src]?.label || src }
function sourceHint(src: string): string  { return SOURCE_META[src]?.hint  || src }

const featureLabel = featurePermissionLabel

const profiles = computed<ProfileEffective[]>(() => data.value?.profiles || [])
const selectedProfileIdx = ref<number>(0)
watch(profiles, (list) => {
  if (list.length && selectedProfileIdx.value >= list.length) selectedProfileIdx.value = 0
})
const selected = computed<ProfileEffective | null>(() => profiles.value[selectedProfileIdx.value] || null)

const filter = ref<string>('')
const filteredFeatures = computed<FeatureEffective[]>(() => {
  const features = selected.value?.features || []
  const q = filter.value.trim().toLowerCase()
  if (!q) return features
  // Match the friendly label as well as the raw dot-notation key.
  return features.filter(f =>
    f.dbValue.toLowerCase().includes(q)
    || featureLabel(f.dbValue).toLowerCase().includes(q))
})
</script>

<template>
  <AppModal :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)"
            :title="`Effective permissions — ${adminLabel}`" size="xl">

    <div v-if="loading" class="text-sm text-gray-500 py-8 text-center">Loading…</div>
    <div v-else-if="error" class="bg-red-50 border border-red-200 rounded-xl p-4 text-sm text-red-700">{{ error }}</div>

    <template v-else-if="data">

      <!-- Superadmin short-circuit -->
      <div v-if="data.superadmin" class="bg-amber-50 border border-amber-200 rounded-xl p-4 text-sm text-amber-900">
        <p class="font-semibold mb-1">Superadmin</p>
        <p>This account bypasses all scoping and feature checks — it can perform any action in any directory.</p>
      </div>

      <template v-else>

        <!-- No profiles assigned -->
        <div v-if="!profiles.length" class="bg-gray-50 border border-gray-200 rounded-xl p-4 text-sm text-gray-700">
          No profile roles assigned — this admin cannot access any directory. Grant profile access from the Permissions dialog to activate the account.
        </div>

        <!-- Fill the modal body and let only the feature table scroll: the
             profile tabs, header and filter stay pinned while the (often long)
             feature list scrolls within the panel. -->
        <template v-else>
          <!-- Profile tab bar -->
          <div class="flex flex-wrap gap-1 mb-4 border-b border-gray-200 shrink-0">
            <button v-for="(p, i) in profiles" :key="p.profileId"
                    type="button"
                    @click="selectedProfileIdx = i; filter = ''"
                    :class="i === selectedProfileIdx
                      ? 'border-blue-500 text-blue-700 bg-blue-50/50'
                      : 'border-transparent text-gray-600 hover:text-gray-900'"
                    class="px-3 py-1.5 text-xs border-b-2 -mb-px transition-colors rounded-t">
              <span class="font-medium">{{ p.profileName }}</span>
              <span class="text-gray-500 ml-1.5">·</span>
              <span class="text-gray-500 ml-1">{{ p.directoryName || 'Directory' }}</span>
            </button>
          </div>

          <!-- Profile header -->
          <div v-if="selected" class="mb-4 p-3 bg-gray-50 border border-gray-200 rounded-lg text-xs space-y-1 shrink-0">
            <div class="flex items-baseline justify-between">
              <p>
                <span class="font-semibold">Base role:</span>
                <span class="ml-1 font-mono" :class="selected.baseRole === 'ADMIN' ? 'text-blue-700' : 'text-gray-600'">{{ selected.baseRole }}</span>
              </p>
              <input v-model="filter" type="search" placeholder="Filter features…"
                     class="input text-xs w-56" />
            </div>
            <p><span class="font-semibold">Target User DN:</span>
              <code class="ml-1 text-[11px] bg-white border border-gray-200 rounded px-1 py-0.5">{{ selected.targetUserDn || '—' }}</code>
            </p>
          </div>

          <!-- Feature matrix. Owns the remaining height and scrolls; the
               header row sticks so columns stay labelled while scrolling. -->
          <div class="bg-white border border-gray-200 rounded-xl flex-1 min-h-0 overflow-y-auto">
            <table class="w-full text-sm">
              <thead class="bg-gray-50 sticky top-0 z-10">
                <tr>
                  <th class="px-4 py-2 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">Feature</th>
                  <th class="px-4 py-2 text-center text-xs font-semibold text-gray-500 uppercase tracking-wider w-24">Allowed</th>
                  <th class="px-4 py-2 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">Source</th>
                  <th class="px-4 py-2 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider w-56" title="Set a per-profile override for this feature. Applies only within this profile; most-permissive wins across profiles.">
                    Profile override
                  </th>
                </tr>
              </thead>
              <tbody class="divide-y divide-gray-100">
                <tr v-for="f in filteredFeatures" :key="f.key"
                    :class="f.allowed ? '' : 'bg-gray-50/50 text-gray-500'">
                  <td class="px-4 py-2 text-xs"><span :title="f.dbValue">{{ featureLabel(f.dbValue) }}</span></td>
                  <td class="px-4 py-2 text-center">
                    <span v-if="f.allowed" class="inline-flex items-center text-emerald-600" aria-label="allowed">
                      <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 13l4 4L19 7"/></svg>
                    </span>
                    <span v-else class="inline-flex items-center text-rose-500" aria-label="denied">
                      <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12M6 18L18 6"/></svg>
                    </span>
                  </td>
                  <td class="px-4 py-2">
                    <span class="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium"
                          :class="sourceChip(f.source)"
                          :title="sourceHint(f.source)">
                      {{ sourceLabel(f.source) }}
                    </span>
                  </td>
                  <td class="px-4 py-2 text-right whitespace-nowrap">
                    <!-- Tri-state toggle. Highlights the active state; clicking an active
                         state again clears the per-profile override. -->
                    <div class="inline-flex gap-1">
                      <button type="button" :disabled="saving"
                              @click="setProfileOverride(selected!.profileId, f.key,
                                        f.source === 'PROFILE_OVERRIDE_ALLOW' ? 'clear' : 'allow')"
                              :class="f.source === 'PROFILE_OVERRIDE_ALLOW' ? 'btn-success-soft' : 'btn-secondary'"
                              class="btn-compact"
                              title="Allow this feature in this profile only">Allow</button>
                      <button type="button" :disabled="saving"
                              @click="setProfileOverride(selected!.profileId, f.key,
                                        f.source === 'PROFILE_OVERRIDE_DENY' ? 'clear' : 'deny')"
                              :class="f.source === 'PROFILE_OVERRIDE_DENY' ? 'btn-danger-soft' : 'btn-secondary'"
                              class="btn-compact"
                              title="Deny this feature in this profile only">Deny</button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!filteredFeatures.length">
                  <td colspan="4" class="px-4 py-6 text-center text-xs text-gray-500">No features match the filter.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </template>
    </template>
  </AppModal>
</template>

<style scoped>
@reference "tailwindcss";
</style>
