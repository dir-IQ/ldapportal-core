<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <div class="bg-white border border-gray-200 rounded-xl p-5 mb-4">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div class="text-sm text-gray-600 space-y-1">
          <div><span class="badge-blue mr-1">A</span>{{ run.a.directoryName }} · <span class="font-mono">{{ sideSummary(run.a) }}</span></div>
          <div><span class="badge-gray mr-1">B</span>{{ run.b.directoryName }} · <span class="font-mono">{{ sideSummary(run.b) }}</span></div>
          <div class="text-xs text-gray-500">
            {{ pairingLabel(run) }} · finished {{ formatTime(run.finishedAt) }} · {{ n(totalEntries(run.counts)) }} results
            <template v-if="run.attributes.length"> · only {{ run.attributes.join(', ') }}</template>
            <template v-if="run.ignore.length"> · ignoring {{ run.ignore.join(', ') }}</template>
          </div>
        </div>
        <div class="flex gap-2">
          <button type="button" class="btn-secondary btn-sm" :disabled="exporting" @click="exportCsv(category)">
            Export {{ CATEGORY_LABELS[category] }} (CSV)
          </button>
          <button type="button" class="btn-secondary btn-sm" :disabled="exporting" @click="exportCsv()">Export all (CSV)</button>
          <button type="button" class="btn-primary btn-sm" @click="emit('runAgain')">Run again</button>
        </div>
      </div>
    </div>

    <div class="flex flex-wrap gap-2 mb-2" role="tablist" aria-label="Groups">
      <button
        v-for="c in CATEGORIES" :key="c" type="button" role="tab"
        :aria-selected="category === c"
        :class="category === c ? 'btn-tab-active' : 'btn-tab'"
        :data-testid="`group-${c}`"
        @click="selectCategory(c)"
      >
        {{ CATEGORY_LABELS[c] }} <span class="tabular-nums ml-1">{{ n(countFor(run.counts, c)) }}</span>
      </button>
    </div>
    <p class="text-xs text-gray-500 mb-3">{{ CATEGORY_HINTS[category] }}</p>

    <div v-if="category === 'DIFFERENT' && run.attributeCounts.length" class="flex flex-wrap items-center gap-1.5 mb-3"
      data-testid="attribute-filter">
      <span class="text-xs text-gray-500 mr-1">Attributes that differ:</span>
      <button
        v-for="f in run.attributeCounts" :key="f.name" type="button"
        :class="['btn-sm', attribute === f.name ? 'btn-tab-active' : 'btn-tab']"
        :aria-pressed="attribute === f.name"
        @click="toggleAttribute(f.name)"
      >
        <span class="font-mono">{{ f.name }}</span> <span class="tabular-nums ml-1">{{ n(f.count) }}</span>
      </button>
      <button v-if="attribute" type="button" class="btn-neutral btn-sm" @click="toggleAttribute(null)">Show all</button>
    </div>

    <div class="bg-white border border-gray-200 rounded-xl">
      <div class="flex flex-wrap items-center justify-between gap-3 p-3 border-b border-gray-200">
        <label class="flex items-center gap-2 text-sm">
          <span class="sr-only">Search by DN</span>
          <input v-model="search" type="search" class="input input-sm w-72" placeholder="Search by DN" />
        </label>
        <span class="text-xs text-gray-500">{{ showing }}</span>
      </div>

      <p v-if="loading && !rows.length" class="p-4 text-sm text-gray-500">Loading…</p>
      <p v-else-if="loadError" class="p-4 text-sm text-red-600">{{ loadError }}</p>
      <p v-else-if="!rows.length" class="p-4 text-sm text-gray-500">Nothing here.</p>
      <table v-else class="w-full text-sm" data-testid="results-table">
        <thead>
          <tr class="text-left text-gray-500 border-b border-gray-200">
            <th class="py-2 px-3 font-medium">Entry</th>
            <th class="py-2 px-3 font-medium">{{ detailColumn }}</th>
            <th class="py-2 px-3"><span class="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in rows" :key="r.seq" class="border-b border-gray-100 last:border-0 align-top">
            <td class="py-2 px-3 font-mono text-xs break-all">
              <div v-if="r.dnA">{{ r.dnA }}</div>
              <div v-if="r.dnA && r.dnB && !sameDn(r)" class="text-gray-500">↔ {{ r.dnB }}</div>
              <div v-else-if="!r.dnA">{{ r.dnB }}</div>
            </td>
            <td class="py-2 px-3">
              <span v-if="r.category === 'DIFFERENT'" class="flex flex-wrap gap-1">
                <span v-for="a in r.attributes" :key="a" class="badge-amber font-mono">{{ a }}</span>
              </span>
              <span v-else class="cell-muted">{{ r.note || '—' }}</span>
            </td>
            <td class="py-2 px-3 text-right whitespace-nowrap">
              <button type="button" class="btn-secondary btn-sm" @click="openAt(i)">
                {{ r.dnA && r.dnB ? 'Compare' : 'Details' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="totalPages > 1" class="flex items-center justify-between p-3 border-t border-gray-200 text-sm">
        <button type="button" class="btn-secondary btn-sm" :disabled="page === 0 || loading" @click="goTo(page - 1)">‹ Previous</button>
        <span class="text-gray-600">Page {{ page + 1 }} of {{ n(totalPages) }}</span>
        <button type="button" class="btn-secondary btn-sm" :disabled="page >= totalPages - 1 || loading" @click="goTo(page + 1)">Next ›</button>
      </div>
    </div>

    <DirectoryDiffEntryPanel
      v-if="openIndex !== null && rows[openIndex]"
      :run-id="run.id"
      :entry="rows[openIndex]"
      :dir-a="run.a.directoryId"
      :dir-b="run.b.directoryId"
      :found-at="run.finishedAt"
      :position="panelPosition"
      :has-previous="openIndex > 0 || page > 0"
      :has-next="openIndex < rows.length - 1 || page < totalPages - 1"
      @close="openIndex = null"
      @previous="step(-1)"
      @next="step(1)"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  downloadDirectoryDiffCsv, listDirectoryDiffEntries,
  type DirectoryDiffCategory, type DirectoryDiffEntry, type DirectoryDiffRun,
} from '@/api/directoryDiff'
import { useNotificationStore } from '@/stores/notifications'
import { downloadBlob } from '@/composables/useApi'
import DirectoryDiffEntryPanel from './DirectoryDiffEntryPanel.vue'
import {
  CATEGORIES, CATEGORY_HINTS, CATEGORY_LABELS, countFor, errMsg, formatTime, n, pairingLabel, sideSummary,
  totalEntries,
} from './directoryDiffFormat'

const PAGE_SIZE = 50

const props = defineProps<{ run: DirectoryDiffRun }>()
const emit = defineEmits<{ runAgain: [] }>()

const notif = useNotificationStore()

/** Open on the first group with something in it, Different first. */
const category = ref<DirectoryDiffCategory>(
  CATEGORIES.find(c => countFor(props.run.counts, c) > 0) ?? 'DIFFERENT')
const attribute = ref<string | null>(null)
const search = ref('')
const page = ref(0)
const rows = ref<DirectoryDiffEntry[]>([])
const total = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
const exporting = ref(false)
const openIndex = ref<number | null>(null)

let searchTimer: ReturnType<typeof setTimeout> | null = null
let loadToken = 0

const detailColumn = computed(() => category.value === 'DIFFERENT' ? 'Differs in' : 'Note')

const showing = computed(() => {
  if (!total.value) return ''
  const from = page.value * PAGE_SIZE + 1
  const to = Math.min(total.value, from + rows.value.length - 1)
  return `Showing ${n(from)}–${n(to)} of ${n(total.value)}`
})

const panelPosition = computed(() => {
  if (openIndex.value === null) return ''
  return `${n(page.value * PAGE_SIZE + openIndex.value + 1)} of ${n(total.value)}`
})

function sameDn(r: DirectoryDiffEntry): boolean {
  return (r.dnA ?? '').toLowerCase() === (r.dnB ?? '').toLowerCase()
}

async function load(): Promise<void> {
  const token = ++loadToken
  loading.value = true
  loadError.value = null
  try {
    const { data } = await listDirectoryDiffEntries(props.run.id, {
      category: category.value,
      attribute: attribute.value,
      q: search.value.trim() || null,
      page: page.value,
      size: PAGE_SIZE,
    })
    if (token !== loadToken) return
    rows.value = data.content
    total.value = data.totalElements
    totalPages.value = data.totalPages
  } catch (e) {
    if (token !== loadToken) return
    rows.value = []
    loadError.value = errMsg(e, 'Could not load the results')
  } finally {
    if (token === loadToken) loading.value = false
  }
}

function selectCategory(c: DirectoryDiffCategory) {
  if (category.value === c) return
  category.value = c
  attribute.value = null
  page.value = 0
  openIndex.value = null
  load()
}

function toggleAttribute(name: string | null) {
  attribute.value = attribute.value === name ? null : name
  page.value = 0
  openIndex.value = null
  load()
}

async function goTo(p: number) {
  page.value = p
  openIndex.value = null
  await load()
}

function openAt(i: number) {
  openIndex.value = i
}

/** Move the panel one entry along, crossing into the next or previous page when needed. */
async function step(delta: 1 | -1) {
  if (openIndex.value === null) return
  const next = openIndex.value + delta
  if (next >= 0 && next < rows.value.length) {
    openIndex.value = next
    return
  }
  const target = page.value + delta
  if (target < 0 || target >= totalPages.value) return
  page.value = target
  await load()
  openIndex.value = rows.value.length ? (delta > 0 ? 0 : rows.value.length - 1) : null
}

async function exportCsv(c?: DirectoryDiffCategory) {
  exporting.value = true
  try {
    const { data } = await downloadDirectoryDiffCsv(props.run.id, c)
    downloadBlob(data, `directory-diff-${props.run.id}${c ? `-${c.toLowerCase()}` : ''}.csv`)
  } catch (e) {
    notif.error(errMsg(e, 'Could not download the CSV'))
  } finally {
    exporting.value = false
  }
}

watch(search, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 0
    openIndex.value = null
    load()
  }, 300)
})

onMounted(load)

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
})
</script>
