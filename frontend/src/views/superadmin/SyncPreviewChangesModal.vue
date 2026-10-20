<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <AppModal :model-value="modelValue" :title="`Planned changes — ${setName}`" size="2xl"
            storage-key="sync-preview-changes" @update:model-value="emit('update:modelValue', $event)">
    <div class="flex-1 min-h-0 flex flex-col gap-3">
      <div class="flex flex-wrap items-center gap-3">
        <label class="flex items-center gap-2 text-sm text-gray-700">
          <input type="checkbox" v-model="changesOnly" class="rounded" @change="load(0)" />
          Changes only
        </label>
        <label class="flex items-center gap-2 text-sm text-gray-700">
          Per page
          <select v-model.number="size" aria-label="Entries per page" class="input-sm" @change="load(0)">
            <option v-for="n in PAGE_SIZES" :key="n" :value="n">{{ n }}</option>
          </select>
        </label>
        <button type="button" class="btn-secondary btn-sm" :disabled="loading" @click="load(page)">
          {{ loading ? 'Checking…' : 'Refresh' }}
        </button>
      </div>

      <div v-if="result" class="text-xs text-gray-600 space-y-1">
        <p>
          Source entries: <span class="font-medium">{{ result.sourceCount }}</span> ·
          Managed: <span class="font-medium">{{ result.managedCount }}</span> ·
          {{ result.changesOnly ? 'Entries that would change' : 'Entries' }}:
          <span class="font-medium">{{ result.totalEntries }}</span>
          <template v-if="result.unchangedCount !== null">
            · Unchanged (hidden): <span class="font-medium">{{ result.unchangedCount }}</span>
          </template>
        </p>
        <p v-if="result.note" class="text-amber-600">{{ result.note }}</p>
        <p v-if="result.guardTripped"
           class="text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1">
          Delete guard would trip: {{ result.guardReason }}. Deletions are shown as held for review.
        </p>
        <p class="text-gray-500">
          A dry run of the sync engine: nothing is written. Each page re-reads the source, so a large set takes
          as long to preview as to reconcile.
        </p>
      </div>

      <div class="flex-1 min-h-0 overflow-y-auto">
        <DataTable :columns="columns" :rows="rows" :loading="loading" row-key="identity"
                   :expanded-key="expanded ?? undefined" :highlight-key="expanded ?? undefined"
                   empty-text="Nothing to change." @row-click="toggle">
          <template #cell-action="{ row }">
            <span :class="ACTION_BADGE[row.action]">{{ ACTION_LABEL[row.action] }}</span>
          </template>
          <template #cell-targetDn="{ row }">
            <span class="font-mono break-all">{{ row.targetDn ?? row.sourceDn ?? row.identity }}</span>
          </template>
          <template #cell-detail="{ row }">
            <span class="cell-muted">{{ summary(row) }}</span>
          </template>
          <template #row-detail="{ row }">
            <div class="px-4 py-3 space-y-2 text-xs">
              <p v-if="row.previousTargetDn" class="text-gray-600">
                Moves from <span class="font-mono">{{ row.previousTargetDn }}</span>
              </p>
              <p v-if="row.sourceDn" class="text-gray-500">
                Source: <span class="font-mono">{{ row.sourceDn }}</span>
              </p>
              <p v-if="row.reason" class="text-gray-600">{{ row.reason }}</p>
              <dl v-if="row.changes.length" class="space-y-1.5">
                <div v-for="c in row.changes" :key="c.attribute">
                  <dt class="font-medium text-gray-700">{{ c.attribute }}</dt>
                  <dd class="font-mono pl-3 space-y-0.5">
                    <div v-for="(v, i) in c.removed" :key="`r${i}`" class="text-rose-700 break-all">− {{ v }}</div>
                    <div v-if="c.omittedRemoved" class="text-rose-700">− … and {{ c.omittedRemoved }} more</div>
                    <div v-for="(v, i) in c.added" :key="`a${i}`" class="text-emerald-700 break-all">+ {{ v }}</div>
                    <div v-if="c.omittedAdded" class="text-emerald-700">+ … and {{ c.omittedAdded }} more</div>
                  </dd>
                </div>
              </dl>
            </div>
          </template>
        </DataTable>
      </div>

      <div v-if="result && result.totalPages > 1" class="flex items-center justify-between text-sm text-gray-600">
        <button type="button" class="btn-secondary btn-sm" :disabled="loading || page === 0"
                @click="load(page - 1)">Previous</button>
        <span>Page {{ page + 1 }} of {{ result.totalPages }}</span>
        <button type="button" class="btn-secondary btn-sm" :disabled="loading || page + 1 >= result.totalPages"
                @click="load(page + 1)">Next</button>
      </div>
    </div>
  </AppModal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import DataTable from '@/components/DataTable.vue'
import { previewEntries, type SyncPreviewAction, type SyncPreviewEntry, type SyncPreviewPage } from '@/api/sync'
import { useNotificationStore } from '@/stores/notifications'

const props = defineProps<{
  modelValue: boolean
  setId: string
  setName: string
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const notif = useNotificationStore()

const PAGE_SIZES = [25, 50, 100, 200]

const ACTION_LABEL: Record<SyncPreviewAction, string> = {
  ADD: 'Add', MODIFY: 'Modify', MOVE: 'Move', DELETE: 'Delete', REVIEW: 'Review',
  NO_CHANGE: 'No change', FORGET: 'Forget', UNCHANGED: 'Unchanged',
}
const ACTION_BADGE: Record<SyncPreviewAction, string> = {
  ADD: 'badge-green', MODIFY: 'badge-blue', MOVE: 'badge-yellow', DELETE: 'badge-red', REVIEW: 'badge-amber',
  NO_CHANGE: 'badge-gray', FORGET: 'badge-gray', UNCHANGED: 'badge-gray',
}

const columns = [
  { key: 'action', label: 'Action' },
  { key: 'targetDn', label: 'Target DN' },
  { key: 'detail', label: 'Details' },
]

const changesOnly = ref<boolean>(true)
const size = ref<number>(50)
const page = ref<number>(0)
const loading = ref<boolean>(false)
const result = ref<SyncPreviewPage | null>(null)
const expanded = ref<string | null>(null)

const rows = computed<SyncPreviewEntry[]>(() => result.value?.entries ?? [])

function summary(e: SyncPreviewEntry): string {
  if (e.reason) return e.reason
  const n = e.changes.length
  switch (e.action) {
    case 'ADD': return `${n} attribute${n === 1 ? '' : 's'}`
    case 'MODIFY': return `${n} attribute${n === 1 ? '' : 's'} changed`
    case 'MOVE': return n ? `renamed; ${n} attribute${n === 1 ? '' : 's'} changed` : 'renamed'
    case 'NO_CHANGE': return 'target already matches'
    case 'DELETE': return 'removed from the target'
    default: return ''
  }
}

function toggle(row: SyncPreviewEntry) {
  expanded.value = expanded.value === row.identity ? null : row.identity
}

async function load(target: number) {
  loading.value = true
  expanded.value = null
  try {
    const { data } = await previewEntries(props.setId, target, size.value, changesOnly.value)
    result.value = data
    page.value = data.page
  } catch (e) {
    const err = e as {
      response?: { data?: { detail?: string, message?: string } },
      message?: string,
    }
    notif.error(
      err.response?.data?.detail
      || err.response?.data?.message
      || err.message
      || 'Could not load the planned changes',
    )
  } finally {
    loading.value = false
  }
}

// Load fresh each time the dialog opens: the set or the directories may have changed.
watch(() => props.modelValue, (open) => {
  if (open) {
    result.value = null
    void load(0)
  }
}, { immediate: true })
</script>
