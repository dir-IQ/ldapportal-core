<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Server-side paged + filtered membership inventory for one sync set, in a modal.
  State filter is a row of clickable count chips; the search box filters tracked
  rows and — when nothing matches — offers to recompute the query as an untracked
  source DN / identity (replacing the old standalone recompute field).
-->
<template>
  <AppModal v-model="show" :title="`Membership inventory — ${set?.name ?? ''}`" size="xl"
            fill storage-key="sync-membership-inventory">
    <div class="space-y-3">
      <div class="flex items-center justify-between gap-2 flex-wrap -mt-1">
        <p class="text-xs text-gray-500 font-mono">{{ sourceName }} → {{ targetName }}</p>
        <div class="flex items-center gap-2">
          <button class="btn-secondary btn-compact" :disabled="verifying" @click="verify">
            {{ verifying ? 'Verifying…' : 'Verify contents' }}
          </button>
          <button class="btn-secondary btn-compact" :disabled="reconciling" @click="reconcile">
            {{ reconciling ? 'Reconciling…' : 'Reconcile now' }}
          </button>
        </div>
      </div>

      <!--
        Independent verification: re-reads both directories and compares them
        directly (no membership index), surfacing drift the index thinks is
        converged. Belts-and-suspenders beyond the inventory above.
      -->
      <div v-if="verifyResult" class="rounded border border-gray-200 bg-gray-50 px-3 py-2 text-xs space-y-1.5">
        <div class="flex items-center justify-between">
          <span class="font-medium text-gray-700">Content verification</span>
          <button type="button" class="text-gray-400 hover:text-gray-600" aria-label="Dismiss verification"
                  @click="verifyResult = null">✕</button>
        </div>
        <p class="text-gray-600">
          Source members: <span class="font-medium tabular-nums">{{ verifyResult.sourceMembers }}</span> ·
          Target entries: <span class="font-medium tabular-nums">{{ verifyResult.targetEntries }}</span> ·
          In sync: <span class="font-medium tabular-nums text-emerald-700">{{ verifyResult.inSync }}</span>
        </p>
        <p :class="hasMismatches ? 'text-rose-700' : 'text-emerald-700'">
          <template v-if="hasMismatches">
            Missing on target: <span class="font-medium tabular-nums">{{ verifyResult.missingOnTarget }}</span> ·
            Orphaned on target: <span class="font-medium tabular-nums">{{ verifyResult.orphanOnTarget }}</span> ·
            Content drift: <span class="font-medium tabular-nums">{{ verifyResult.contentMismatches }}</span>
          </template>
          <template v-else>✓ Source and target agree — no mismatches found.</template>
        </p>
        <p v-if="verifyResult.note" class="text-amber-700">{{ verifyResult.note }}</p>
        <p v-if="!verifyResult.sourceComplete || !verifyResult.targetComplete" class="text-amber-600">
          Enumeration was incomplete (a directory read failed); counts may be partial.
        </p>
        <div v-for="g in sampleGroups" :key="g.label">
          <template v-if="g.dns.length">
            <p class="text-gray-500">{{ g.label }}:</p>
            <ul class="font-mono text-[11px] text-gray-500 list-disc pl-4">
              <li v-for="dn in g.dns" :key="dn">{{ dn }}</li>
            </ul>
          </template>
        </div>
      </div>

      <!-- State filter chips with counts -->
      <div class="flex flex-wrap items-center gap-1.5">
        <button v-for="c in chips" :key="c.key" type="button"
                class="inline-flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1 rounded-full"
                :class="[c.chip, stateFilter === c.value ? 'ring-2 ring-offset-1 ring-blue-500' : '']"
                :aria-pressed="stateFilter === c.value"
                @click="setStateFilter(c.value)">
          <span v-if="c.dot" class="w-1.5 h-1.5 rounded-full" :class="c.dot" aria-hidden="true"></span>
          {{ c.label }} <span class="tabular-nums">{{ c.n }}</span>
        </button>
      </div>

      <input v-model="search" class="input input-sm w-full"
             placeholder="Filter by identity or DN…" aria-label="Filter memberships" />

      <DataTable :columns="cols" :rows="rows" :loading="loading" row-key="identity"
                 empty-text="No membership rows match." empty-icon="users">
        <template #cell-identity="{ row }">
          <span class="font-mono">{{ row.identity }}</span>
        </template>
        <template #cell-state="{ row }">
          <span class="px-2 py-0.5 rounded text-xs" :class="stateClass(row.state)">{{ row.state }}</span>
        </template>
        <template #cell-sourceDn="{ row }">
          <span class="font-mono">{{ row.sourceDn }}</span>
        </template>
        <template #cell-targetDn="{ row }">
          <span class="font-mono">{{ row.targetDn }}</span>
        </template>
        <template #cell-failReason="{ row }">
          <span class="cell-muted line-clamp-2" :title="row.failReason || undefined">{{ row.failReason || '—' }}</span>
        </template>
        <template #actions="{ row }">
          <ActionMenu :items="[{ label: 'Dismiss', onClick: () => dismiss(row.identity) }]">
            <template #primary>
              <button class="btn-secondary btn-compact" @click="recompute(row.identity)">Recompute</button>
            </template>
          </ActionMenu>
        </template>
      </DataTable>

      <!-- Untracked-key affordance: recompute the search term directly. -->
      <div v-if="!loading && !rows.length && search.trim()" class="text-center pb-1">
        <button class="btn-secondary btn-compact" @click="recomputeQuery">
          Recompute "{{ search.trim() }}"
        </button>
        <p class="text-xs text-gray-500 mt-1">
          No tracked row matches — recompute it as a source DN or identity to pull it in.
        </p>
      </div>

      <div class="flex items-center justify-between gap-2 flex-wrap text-xs text-gray-600 pt-1 border-t border-gray-100">
        <label class="flex items-center gap-1.5">
          Rows
          <select v-model.number="size" class="input input-sm py-0.5 w-20" aria-label="Rows per page">
            <option :value="25">25</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
            <option :value="200">200</option>
          </select>
          <span class="text-gray-500">of {{ total }}</span>
        </label>
        <div class="flex items-center gap-2">
          <button class="btn-neutral btn-compact" :disabled="page <= 0" @click="page--">Prev</button>
          <span class="tabular-nums">Page {{ page + 1 }} of {{ Math.max(totalPages, 1) }}</span>
          <button class="btn-neutral btn-compact" :disabled="page >= totalPages - 1" @click="page++">Next</button>
        </div>
      </div>
    </div>
  </AppModal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import DataTable from '@/components/DataTable.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import { useNotificationStore } from '@/stores/notifications'
import {
  listMemberships, reconcileSet, recomputeKey, dismissMembership, verifyContents,
  type SyncSet, type Membership, type MembershipState, type SyncVerifyResult,
} from '@/api/sync'

const show = defineModel<boolean>('show', { default: false })
const props = defineProps<{ set: SyncSet | null; sourceName: string; targetName: string }>()
const emit = defineEmits<{ changed: [] }>()

const notif = useNotificationStore()

const rows = ref<Membership[]>([])
const total = ref(0)
const totalPages = ref(0)
const page = ref(0)
const size = ref(50)
const stateFilter = ref<'' | MembershipState>('')
const search = ref('')
const loading = ref(false)
const reconciling = ref(false)
const verifying = ref(false)
const verifyResult = ref<SyncVerifyResult | null>(null)

const hasMismatches = computed(() =>
  !!verifyResult.value
  && (verifyResult.value.missingOnTarget
    + verifyResult.value.orphanOnTarget
    + verifyResult.value.contentMismatches) > 0)

const sampleGroups = computed(() => [
  { label: 'Missing on target', dns: verifyResult.value?.sampleMissing ?? [] },
  { label: 'Orphaned on target', dns: verifyResult.value?.sampleOrphans ?? [] },
  { label: 'Content drift', dns: verifyResult.value?.sampleMismatches ?? [] },
])

const cols = [
  { key: 'identity', label: 'Identity' },
  { key: 'state', label: 'State' },
  { key: 'sourceDn', label: 'Source DN' },
  { key: 'targetDn', label: 'Target DN' },
  { key: 'failReason', label: 'Reason' },
]

interface ChipMeta { label: string; chip: string; dot: string }
const STATE_META: Record<string, ChipMeta> = {
  APPLIED: { label: 'Applied', chip: 'bg-green-50 text-green-700', dot: 'bg-green-600' },
  PENDING: { label: 'Pending', chip: 'bg-gray-100 text-gray-600', dot: 'bg-gray-400' },
  REVIEW: { label: 'Review', chip: 'bg-amber-50 text-amber-800', dot: 'bg-amber-500' },
  FAILED: { label: 'Failed', chip: 'bg-red-50 text-red-700', dot: 'bg-red-600' },
}
const STATE_ORDER = ['FAILED', 'REVIEW', 'PENDING', 'APPLIED'] as const

function stateClass(state: MembershipState): string {
  return {
    APPLIED: 'bg-green-100 text-green-800',
    FAILED: 'bg-red-100 text-red-800',
    REVIEW: 'bg-amber-100 text-amber-800',
    PENDING: 'bg-gray-100 text-gray-700',
  }[state]
}

const chips = computed(() => {
  const c = props.set?.stateCounts ?? {}
  const all = Object.values(c).reduce((a, b) => a + b, 0)
  return [
    { key: 'all', value: '' as '' | MembershipState, label: 'All', n: all, dot: '', chip: 'bg-gray-100 text-gray-700' },
    ...STATE_ORDER.map((k) => ({
      key: k, value: k as MembershipState, label: STATE_META[k].label,
      n: c[k] ?? 0, dot: STATE_META[k].dot, chip: STATE_META[k].chip,
    })),
  ]
})

function errMsg(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string; message?: string } } }
  return err.response?.data?.detail || err.response?.data?.message || 'Request failed'
}

async function load() {
  if (!props.set) return
  loading.value = true
  try {
    const { data } = await listMemberships(props.set.id, {
      state: stateFilter.value || undefined,
      q: search.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = data.content
    total.value = data.totalElements
    totalPages.value = data.totalPages
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    loading.value = false
  }
}

function setStateFilter(v: '' | MembershipState) {
  stateFilter.value = v
  page.value = 0
  load()
}

// Reset and load whenever the modal opens or the set changes.
watch([show, () => props.set?.id], ([open]) => {
  if (open && props.set) {
    stateFilter.value = ''
    search.value = ''
    page.value = 0
    verifyResult.value = null
    load()
  }
}, { immediate: true })
watch([size], () => { page.value = 0; load() })
watch(page, load)

let searchTimer: ReturnType<typeof setTimeout> | undefined
watch(search, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { page.value = 0; load() }, 250)
})

async function reconcile() {
  if (!props.set) return
  reconciling.value = true
  try {
    const { data } = await reconcileSet(props.set.id)
    notif.success(`Reconcile enumerated ${data.enumerated} source identities`)
    await load()
    emit('changed')
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    reconciling.value = false
  }
}
async function verify() {
  if (!props.set) return
  verifying.value = true
  try {
    const { data } = await verifyContents(props.set.id)
    verifyResult.value = data
    const mismatches = data.missingOnTarget + data.orphanOnTarget + data.contentMismatches
    if (mismatches > 0) {
      notif.error(`Verification found ${mismatches} mismatch(es) between source and target`)
    } else {
      notif.success('Verification passed — source and target agree')
    }
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    verifying.value = false
  }
}
async function recompute(identity: string) {
  if (!props.set) return
  try {
    await recomputeKey(props.set.id, identity)
    notif.success('Recompute enqueued')
    emit('changed')
  } catch (e) {
    notif.error(errMsg(e))
  }
}
async function recomputeQuery() {
  if (!props.set) return
  const key = search.value.trim()
  if (!key) return
  try {
    await recomputeKey(props.set.id, key)
    notif.success('Recompute enqueued')
    emit('changed')
  } catch (e) {
    notif.error(errMsg(e))
  }
}
async function dismiss(identity: string) {
  if (!props.set) return
  try {
    await dismissMembership(props.set.id, identity)
    notif.success('Membership dismissed')
    await load()
    emit('changed')
  } catch (e) {
    notif.error(errMsg(e))
  }
}
</script>
