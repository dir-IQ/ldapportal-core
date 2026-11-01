<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <Teleport to="body">
    <!-- The backdrop closes on a mouse click; keyboard users have Escape and the Close button. -->
    <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
    <div class="fixed inset-0 z-40 flex justify-end bg-black/30" @click.self="emit('close')" @keydown.esc="emit('close')">
      <aside
        v-dialog-a11y
        class="w-full max-w-3xl h-full overflow-y-auto bg-white dark:bg-gray-800 shadow-xl p-5"
        role="dialog" aria-modal="true" aria-labelledby="dd-panel-title" tabindex="-1"
      >
        <div class="flex items-center justify-between gap-2 mb-4">
          <h2 id="dd-panel-title" class="text-base font-semibold text-gray-900">
            {{ CATEGORY_LABELS[entry.category] }} · {{ position }}
          </h2>
          <div class="flex gap-2">
            <button type="button" class="btn-secondary btn-sm" :disabled="!hasPrevious" @click="emit('previous')">‹ Previous</button>
            <button type="button" class="btn-secondary btn-sm" :disabled="!hasNext" @click="emit('next')">Next ›</button>
            <button type="button" class="btn-neutral btn-sm" aria-label="Close" @click="emit('close')">✕</button>
          </div>
        </div>

        <dl class="text-sm mb-4 space-y-1">
          <div v-if="entry.dnA" class="flex gap-2"><dt class="badge-blue shrink-0">A</dt><dd class="font-mono break-all">{{ entry.dnA }}</dd></div>
          <div v-if="entry.dnB" class="flex gap-2"><dt class="badge-gray shrink-0">B</dt><dd class="font-mono break-all">{{ entry.dnB }}</dd></div>
        </dl>
        <p v-if="entry.note" class="text-sm text-gray-600 mb-4">{{ entry.note }}</p>

        <p v-if="loading" class="text-sm text-gray-500">Loading…</p>
        <p v-else-if="loadError" class="text-sm text-red-600">{{ loadError }}</p>
        <template v-else>
          <div v-if="live" class="mb-3 text-sm" data-testid="live-banner">
            <span :class="live.category === entry.category ? 'badge-gray' : 'badge-amber'">
              Re-read {{ formatTime(live.readAt) }}: {{ liveSummary }}
            </span>
          </div>
          <p v-else class="text-xs text-gray-500 mb-3">
            As found at {{ formatTime(foundAt) }}. Re-read live fetches both entries again to see whether this still holds.
          </p>

          <template v-if="diffs.length">
            <div class="flex gap-3 text-xs text-gray-600 mb-2" aria-hidden="true">
              <span><span class="inline-block w-3 h-3 align-middle rounded-sm bg-red-100 border border-red-300"></span> only in A</span>
              <span><span class="inline-block w-3 h-3 align-middle rounded-sm bg-green-100 border border-green-300"></span> only in B</span>
              <span><span class="inline-block w-3 h-3 align-middle rounded-sm bg-gray-100 border border-gray-300"></span> on both</span>
            </div>
            <table class="w-full text-sm mb-4" data-testid="diff-table">
              <thead>
                <tr class="text-left text-gray-500 border-b border-gray-200">
                  <th class="py-2 pr-3 font-medium w-40">Attribute</th>
                  <th class="py-2 pr-3 font-medium">A</th>
                  <th class="py-2 font-medium">B</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in diffs" :key="d.name" class="border-b border-gray-100 align-top">
                  <th scope="row" class="py-2 pr-3 font-mono font-medium text-left">{{ d.name }}</th>
                  <td class="py-2 pr-3">
                    <ValueList :values="d.both" :total="d.bothCount" tone="both" />
                    <ValueList :values="d.onlyA" :total="d.onlyACount" tone="a" label="only in A" />
                    <span v-if="!d.onlyACount && !d.bothCount" class="cell-muted">(absent)</span>
                  </td>
                  <td class="py-2">
                    <ValueList :values="d.both" :total="d.bothCount" tone="both" />
                    <ValueList :values="d.onlyB" :total="d.onlyBCount" tone="b" label="only in B" />
                    <span v-if="!d.onlyBCount && !d.bothCount" class="cell-muted">(absent)</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
          <p v-else-if="isPaired" class="text-sm text-gray-600 mb-4">Every compared attribute matches.</p>

          <div v-if="sameRows.length" class="mb-4">
            <button type="button" class="btn-neutral btn-sm mb-2" :aria-expanded="showSame" @click="showSame = !showSame">
              {{ showSame ? 'Hide' : 'Show' }} {{ sameRows.length }} matching attribute{{ sameRows.length === 1 ? '' : 's' }}
            </button>
            <table v-if="showSame" class="w-full text-sm" data-testid="same-table">
              <tbody>
                <tr v-for="s in sameRows" :key="s.name" class="border-b border-gray-100 align-top">
                  <th scope="row" class="py-1.5 pr-3 font-mono font-medium text-left w-40">{{ s.name }}</th>
                  <td class="py-1.5 break-all">
                    <template v-if="s.values">{{ s.values.join(', ') }}</template>
                    <span v-else class="cell-muted">Re-read live to see the values</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>

        <div class="flex flex-wrap gap-2 pt-2 border-t border-gray-200">
          <button v-if="isPaired" type="button" class="btn-primary btn-sm" :disabled="rechecking" @click="recheck">
            {{ rechecking ? 'Re-reading…' : 'Re-read live' }}
          </button>
          <RouterLink v-if="entry.dnA" :to="browserLink(dirA, entry.dnA)" class="btn-secondary btn-sm">Open A in browser</RouterLink>
          <RouterLink v-if="entry.dnB" :to="browserLink(dirB, entry.dnB)" class="btn-secondary btn-sm">Open B in browser</RouterLink>
        </div>
      </aside>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  getDirectoryDiffEntry, recheckDirectoryDiffEntry,
  type DirectoryDiffAttributeDiff, type DirectoryDiffEntry, type DirectoryDiffEntryDetail, type DirectoryDiffLiveEntry,
} from '@/api/directoryDiff'
import ValueList from './DirectoryDiffValueList.vue'
import { CATEGORY_LABELS, errMsg, formatTime } from './directoryDiffFormat'

const props = defineProps<{
  runId: string
  entry: DirectoryDiffEntry
  dirA: string
  dirB: string
  /** When the run finished: what "as found" refers to. */
  foundAt: string | null
  position: string
  hasPrevious: boolean
  hasNext: boolean
}>()

const emit = defineEmits<{ close: [], previous: [], next: [] }>()

const detail = ref<DirectoryDiffEntryDetail | null>(null)
const live = ref<DirectoryDiffLiveEntry | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const rechecking = ref(false)
const showSame = ref(false)

const isPaired = computed(() => !!props.entry.dnA && !!props.entry.dnB)

const diffs = computed<DirectoryDiffAttributeDiff[]>(() => live.value ? live.value.diffs : detail.value?.diffs ?? [])

const sameRows = computed<{ name: string, values: string[] | null }[]>(() => {
  if (live.value) return live.value.same.map(s => ({ name: s.name, values: s.values }))
  return (detail.value?.sameAttributes ?? []).map(name => ({ name, values: null }))
})

const liveSummary = computed(() => {
  const l = live.value
  if (!l) return ''
  if (!l.presentInA && !l.presentInB) return 'neither entry exists any more'
  if (!l.presentInA) return 'the entry on A no longer exists'
  if (!l.presentInB) return 'the entry on B no longer exists'
  if (l.category === 'SAME') return props.entry.category === 'SAME' ? 'still the same' : 'now the same'
  return `${l.diffs.length} attribute${l.diffs.length === 1 ? '' : 's'} differ${l.diffs.length === 1 ? 's' : ''}`
})

function browserLink(dir: string, dn: string) {
  return { path: '/superadmin/directory-browser', query: { dir, dn } }
}

async function load() {
  detail.value = null
  live.value = null
  loadError.value = null
  showSame.value = false
  if (props.entry.category !== 'DIFFERENT') return
  loading.value = true
  try {
    const { data } = await getDirectoryDiffEntry(props.runId, props.entry.seq)
    detail.value = data
  } catch (e) {
    loadError.value = errMsg(e, 'Could not load this entry')
  } finally {
    loading.value = false
  }
}

async function recheck() {
  rechecking.value = true
  loadError.value = null
  try {
    const { data } = await recheckDirectoryDiffEntry(props.runId, props.entry.seq)
    live.value = data
  } catch (e) {
    loadError.value = errMsg(e, 'Could not re-read the entries')
  } finally {
    rechecking.value = false
  }
}

watch(() => props.entry.seq, load)

onMounted(load)
</script>
