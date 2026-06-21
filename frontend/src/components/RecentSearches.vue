<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
/**
 * Recent-searches disclosure for the admin User / Group list pages. The
 * trigger mirrors LdapFilterBuilder's "Build filter visually" styling so the
 * two sit side by side on one row; expanding it reveals a collapsible panel
 * (same look as the builder's) listing the recently-run LDAP filters. Picking
 * one emits `select` so the parent can apply and re-run it.
 *
 * History is scoped per (page, directory) and persists in the server-side
 * preferences document (namespace `search`, key `${storageKey}:${directoryId}`)
 * — the same store the superadmin Directory Search uses for its history — so it
 * follows the user across browsers and devices rather than living in
 * localStorage.
 */
import { ref, watch } from 'vue'
import { usePreferencesStore } from '@/stores/preferences'

const props = defineProps<{
  directoryId: string
  /** Distinguishes the User page's history from the Group page's. */
  storageKey: string
}>()

const emit = defineEmits<{ (e: 'select', filter: string): void }>()

const MAX_RECENT = 10
const prefs = usePreferencesStore()
const expanded = ref(false)
const recent = ref<string[]>([])

interface RecentSearchPrefs { recent?: string[] }
function prefKey(): string { return `${props.storageKey}:${props.directoryId}` }
function loadRecent(): string[] {
  const stored = prefs.read<RecentSearchPrefs>('search', prefKey(), {})
  return Array.isArray(stored.recent) ? stored.recent.filter(s => typeof s === 'string') : []
}
function persist(): void { prefs.write('search', prefKey(), { recent: recent.value }) }

recent.value = loadRecent()
// History is per directory — re-read when the page switches directories.
watch(() => props.directoryId, () => { recent.value = loadRecent() })

/**
 * Record a just-run filter at the head of the list. No-ops on a blank filter
 * (an empty filter means "all entries" and isn't worth remembering).
 * De-duplicates so re-running a search moves it to the front instead of
 * stacking a duplicate chip.
 */
function record(filter: string): void {
  const f = (filter ?? '').trim()
  if (!f) return
  recent.value = [f, ...recent.value.filter(s => s !== f)].slice(0, MAX_RECENT)
  persist()
}

function pick(filter: string): void {
  emit('select', filter)
  expanded.value = false
}

function clearRecent(): void {
  recent.value = []
  persist()
}

defineExpose({ record })
</script>

<template>
  <div>
    <!-- Disclosure trigger. Deliberately mirrors LdapFilterBuilder's trigger
         (same text size/colour, ▸ rotation) so the pair reads as a matched
         set when laid out on one row. -->
    <button
      type="button"
      @click="expanded = !expanded"
      class="text-xs text-gray-500 hover:text-gray-700 cursor-pointer select-none flex items-center gap-1"
      :aria-expanded="expanded"
    >
      <span class="inline-block transition-transform" :class="expanded ? 'rotate-90' : ''">▸</span>
      Recent searches
      <span v-if="recent.length" class="text-blue-600 ml-1 normal-case">({{ recent.length }})</span>
    </button>

    <div v-if="expanded" class="mt-2 border border-gray-200 rounded-lg p-3 bg-gray-50/50">
      <div v-if="recent.length === 0" class="text-xs text-gray-500 italic py-1">
        No recent searches yet. Run a search and it'll show up here.
      </div>
      <template v-else>
        <div class="flex items-center justify-between mb-2">
          <span class="text-xs font-medium text-gray-600">Click a search to run it again</span>
          <button type="button" @click="clearRecent" class="text-xs text-gray-500 hover:text-gray-600">Clear</button>
        </div>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="(f, i) in recent" :key="i"
            type="button"
            @click="pick(f)"
            class="text-xs bg-white border border-gray-200 hover:bg-gray-100 text-gray-700 px-3 py-1.5 rounded-full font-mono truncate max-w-xs transition-colors"
            :title="f"
          >{{ f }}</button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
@reference "tailwindcss";
</style>
