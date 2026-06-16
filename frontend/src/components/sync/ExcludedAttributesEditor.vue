<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="space-y-2">
    <div class="flex items-center justify-between">
      <p class="text-xs text-gray-500">
        <span v-if="isDefault" class="badge-gray mr-1">Defaults</span>
        Attributes never copied from source nor deleted from the target.
      </p>
      <button v-if="!isDefault" type="button" class="text-xs text-blue-600 hover:text-blue-700"
              @click="resetToDefaults">Reset to defaults</button>
    </div>

    <div class="flex flex-wrap gap-1.5 rounded-lg border border-gray-200 p-2 min-h-[2.5rem]">
      <span v-for="(attr, i) in items" :key="`${attr}-${i}`"
            class="inline-flex items-center gap-1 rounded-full bg-blue-50 text-blue-700 text-xs px-2 py-0.5">
        {{ attr }}
        <button type="button" :aria-label="`Remove ${attr}`"
                class="text-blue-400 hover:text-blue-700" @click="removeAt(i)">&times;</button>
      </span>
      <span v-if="items.length === 0" class="text-xs text-gray-400 px-1 py-0.5">
        Nothing excluded — every source attribute will sync.
      </span>
    </div>

    <div class="flex items-center gap-2">
      <input v-model="draft" type="text" placeholder="Add attribute (e.g. telephoneNumber)"
             class="input text-xs flex-1" :aria-label="'Attribute to exclude'"
             @keydown.enter.prevent="addDraft" />
      <button type="button" class="btn-secondary btn-compact" :disabled="!draft.trim()"
              @click="addDraft">Add</button>
    </div>

    <p v-if="syncsPassword" class="text-xs text-amber-600 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
      A password attribute is no longer excluded, so password values will be synced. This works only
      for a same-vendor copy whose target password policy allows pre-encoded passwords
      (<span class="font-mono">allow-pre-encoded-passwords: true</span>) and a compatible hash scheme.
      For a hot backup, prefer native directory replication.
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  /** The sync set's override. null ⇒ engine defaults are in effect. */
  attributes: string[] | null
  /** Default excluded attributes (backend-driven), used when attributes is null. */
  defaults: string[]
}>()
const emit = defineEmits<{ 'update:attributes': [value: string[] | null] }>()

const draft = ref<string>('')

// Password value attributes whose removal from the exclusion list means passwords
// will be synced — surfaced as a warning.
const PASSWORD_ATTRS = new Set(['userpassword', 'authpassword', 'unicodepwd', 'pwdhistory'])

const isDefault = computed<boolean>(() => props.attributes === null)
const items = computed<string[]>(() => props.attributes ?? props.defaults)

/** A default password attribute that is no longer in the effective list. */
const syncsPassword = computed<boolean>(() => {
  const lower = new Set(items.value.map(a => a.toLowerCase()))
  return props.defaults.some(d => PASSWORD_ATTRS.has(d.toLowerCase()) && !lower.has(d.toLowerCase()))
})

// Any edit converts the implicit "defaults" state into an explicit list.
function commit(next: string[]): void {
  emit('update:attributes', next)
}

function addDraft(): void {
  const v = draft.value.trim()
  if (!v) return
  if (items.value.some(a => a.toLowerCase() === v.toLowerCase())) {
    draft.value = ''
    return
  }
  commit([...items.value, v])
  draft.value = ''
}

function removeAt(i: number): void {
  commit(items.value.filter((_, idx) => idx !== i))
}

function resetToDefaults(): void {
  emit('update:attributes', null)
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
