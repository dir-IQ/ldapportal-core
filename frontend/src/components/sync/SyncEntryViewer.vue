<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  A read-only look at the entries behind an inventory row, on top of the
  membership inventory instead of navigating away from it. Each side (the
  target entry, the source entry, or one alone) is read live from its
  directory and shown attribute by attribute, side by side; the attributes the
  drift check found differing are flagged and listed first. Password material
  is never shown. "Open in Directory Browser" opens a new tab, for editing.
-->
<template>
  <AppModal :model-value="show" :title="title" size="xl" storage-key="sync-entry-viewer"
            @update:model-value="(v: boolean) => { if (!v) emit('close') }">
    <div class="space-y-3 text-sm" data-testid="entry-viewer">
      <p class="text-xs text-gray-500">Read-only, as each directory has it right now.</p>

      <!-- Each side: its DN, how the read went, and a way to edit it elsewhere. -->
      <div class="grid gap-2" :class="loaded.length > 1 ? 'sm:grid-cols-2' : ''">
        <div v-for="s in loaded" :key="s.key" class="rounded border border-gray-200 px-3 py-2 space-y-1"
             :data-testid="`entry-side-${s.key}`">
          <div class="flex items-center justify-between gap-2">
            <span class="text-xs font-semibold uppercase tracking-wider text-gray-500">{{ s.label }}</span>
            <a v-if="s.directoryId && s.dn && s.state === 'ok'" :href="browserHref(s.directoryId, s.dn)"
               target="_blank" rel="noopener" class="text-xs text-blue-700 hover:underline"
               title="Open this entry in the Directory Browser in a new tab, to edit it">
              Open in Directory Browser <span aria-hidden="true">↗</span>
            </a>
          </div>
          <p class="font-mono text-xs break-all text-gray-800">{{ s.dn ?? '—' }}</p>
          <p v-if="s.state === 'loading'" class="text-xs text-gray-500">Reading…</p>
          <p v-else-if="s.state === 'missing'" class="text-xs text-amber-700">{{ s.missingText }}</p>
          <p v-else-if="s.state === 'error'" class="text-xs text-rose-700" role="alert">{{ s.message }}</p>
          <p v-else-if="s.state === 'none'" class="text-xs text-gray-500">{{ s.missingText }}</p>
        </div>
      </div>

      <template v-if="rows.length">
        <div class="flex items-center justify-between gap-2 flex-wrap">
          <p v-if="flagged.size" class="text-xs text-gray-600">
            <span class="inline-block w-2 h-2 rounded-sm bg-amber-300 align-middle" aria-hidden="true"></span>
            {{ flagLabel ?? `Flagged: the attributes the content check found differing (${flagged.size}).` }}
          </p>
          <label v-if="flagged.size > 0 && rows.length > flagged.size" class="flex items-center gap-1.5 text-xs text-gray-600">
            <input v-model="onlyFlagged" type="checkbox" class="rounded border-gray-300" />
            Only flagged attributes
          </label>
        </div>
        <div class="overflow-x-auto rounded-lg border border-gray-200">
          <table class="min-w-full divide-y divide-gray-200 text-sm" data-testid="entry-attributes">
            <thead class="bg-gray-50">
              <tr>
                <th scope="col" class="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                  Attribute
                </th>
                <th v-for="s in shown" :key="s.key" scope="col"
                    class="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                  {{ s.label }}
                </th>
              </tr>
            </thead>
            <tbody class="bg-white divide-y divide-gray-100">
              <tr v-for="r in visibleRows" :key="r.key" :class="r.flagged ? 'bg-amber-50' : ''"
                  :data-testid="`entry-attr-${r.key}`">
                <th scope="row" class="px-4 py-2 align-top text-left font-mono text-[13px] font-normal text-gray-700">
                  {{ r.name }}
                  <span v-if="r.flagged" class="sr-only">(flagged)</span>
                </th>
                <td v-for="s in shown" :key="s.key" class="px-4 py-2 align-top font-mono text-[13px] break-all text-gray-700">
                  <span v-if="r.secret && r.values[s.key]?.length" class="italic font-sans text-gray-500">(hidden)</span>
                  <span v-else-if="!r.values[s.key]?.length" class="italic font-sans text-gray-400">(none)</span>
                  <template v-else>
                    <div v-for="(v, i) in r.values[s.key].slice(0, VALUE_LIMIT)" :key="i">{{ v }}</div>
                    <div v-if="r.values[s.key].length > VALUE_LIMIT" class="font-sans text-xs text-gray-500">
                      … and {{ r.values[s.key].length - VALUE_LIMIT }} more
                    </div>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="shown.length > 1" class="text-xs text-gray-500">
          Rows pair attributes by name. The sync may map or transform attributes on their way to the target, so a
          difference between the columns isn't always drift.
        </p>
      </template>
    </div>
    <template #footer>
      <button type="button" class="btn-neutral" @click="emit('close')">Close</button>
    </template>
  </AppModal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import { browse } from '@/api/browse'
import { browserHref, isSecret } from './syncLinks'

/** One entry to show: where it lives and what to call it. */
export interface EntrySide {
  key: string
  label: string
  directoryId: string | null
  dn: string | null
  /** What to say when there's no entry at `dn` (or no DN at all). */
  missingText?: string
}

const props = defineProps<{
  show: boolean
  title: string
  sides: EntrySide[]
  /** Attributes to flag and list first (any case): by default, the ones the content check found differing. */
  flaggedAttributes?: string[]
  /** What the flag means, when it isn't "the content check found these differing". */
  flagLabel?: string
}>()
const emit = defineEmits<{ close: [] }>()

/** Values shown per attribute and side before "… and n more" (a big group's members). */
const VALUE_LIMIT = 50

type SideState = 'loading' | 'ok' | 'missing' | 'error' | 'none'
interface LoadedSide extends EntrySide {
  state: SideState
  attrs: Record<string, string[]>
  message: string
  missingText: string
}

const loaded = ref<LoadedSide[]>([])
const onlyFlagged = ref<boolean>(false)
const flagged = computed<Set<string>>(() =>
  new Set((props.flaggedAttributes ?? []).map(a => a.toLowerCase())))
/** The sides with attributes to show as columns. */
const shown = computed<LoadedSide[]>(() => loaded.value.filter(s => s.state === 'ok'))

interface Row { key: string; name: string; flagged: boolean; secret: boolean; values: Record<string, string[]> }
/** Every attribute any shown side has, matched by name (any case): flagged first, then objectClass, then A–Z. */
const rows = computed<Row[]>(() => {
  const byKey = new Map<string, Row>()
  for (const s of shown.value) {
    for (const [name, values] of Object.entries(s.attrs)) {
      const key = name.toLowerCase()
      let row = byKey.get(key)
      if (!row) {
        row = { key, name, flagged: flagged.value.has(key), secret: isSecret(name), values: {} }
        byKey.set(key, row)
      }
      row.values[s.key] = values ?? []
    }
  }
  // A flagged attribute one side lacks entirely is still a row: that's the drift.
  for (const key of flagged.value) {
    if (!byKey.has(key) && shown.value.length) {
      byKey.set(key, { key, name: props.flaggedAttributes!.find(a => a.toLowerCase() === key)!, flagged: true,
        secret: isSecret(key), values: {} })
    }
  }
  const rank = (r: Row) => (r.flagged ? 0 : r.key === 'objectclass' ? 1 : 2)
  return [...byKey.values()].sort((a, b) => rank(a) - rank(b) || a.key.localeCompare(b.key))
})
const visibleRows = computed<Row[]>(() => (onlyFlagged.value ? rows.value.filter(r => r.flagged) : rows.value))

function errorOf(e: unknown): { missing: boolean; message: string } {
  const err = e as { response?: { status?: number; data?: { detail?: string; message?: string } }; message?: string }
  const message = err.response?.data?.detail || err.response?.data?.message || err.message || 'Couldn’t read the entry'
  return { missing: err.response?.status === 404 || /no such object/i.test(message), message }
}

let readSeq = 0
async function read(): Promise<void> {
  const seq = ++readSeq
  onlyFlagged.value = false
  loaded.value = props.sides.map(s => ({
    ...s,
    state: s.directoryId && s.dn ? 'loading' : 'none',
    attrs: {},
    message: '',
    missingText: s.missingText ?? 'No entry here.',
  }))
  await Promise.all(loaded.value.map(async (s, i) => {
    if (s.state !== 'loading') return
    let next: Partial<LoadedSide>
    try {
      const { data } = await browse(s.directoryId!, s.dn!, { limit: 1 }) as
        { data: { attributes?: Record<string, string[]> } }
      const attrs = data.attributes ?? {}
      next = Object.keys(attrs).length ? { state: 'ok', attrs } : { state: 'missing' }
    } catch (e) {
      const { missing, message } = errorOf(e)
      next = missing ? { state: 'missing' } : { state: 'error', message }
    }
    if (seq === readSeq) loaded.value[i] = { ...loaded.value[i], ...next }
  }))
}

watch(() => [props.show, props.sides] as const, ([open]) => { if (open) void read() }, { immediate: true })
</script>
