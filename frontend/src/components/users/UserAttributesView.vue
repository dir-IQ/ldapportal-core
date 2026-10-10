<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <input
      v-model="filter"
      type="search"
      class="input mb-3"
      placeholder="Filter attributes"
      aria-label="Filter attributes"
    />
    <dl class="divide-y divide-gray-100">
      <div
        v-for="row in filteredRows"
        :key="row.name"
        class="grid grid-cols-3 gap-4 py-2 text-sm"
        data-testid="user-attr-row"
      >
        <dt class="font-medium text-gray-700 break-all">{{ row.name }}</dt>
        <dd class="col-span-2 text-gray-900">
          <span v-if="row.masked" class="text-gray-500 italic">(hidden)</span>
          <template v-else>
            <div v-for="(v, i) in row.values" :key="i" class="break-all whitespace-pre-wrap">{{ v }}</div>
          </template>
        </dd>
      </div>
    </dl>
    <p v-if="!rows.length" class="py-4 text-center text-gray-500 text-sm">No attributes</p>
    <p v-else-if="!filteredRows.length" class="py-4 text-center text-gray-500 text-sm">No attributes match the filter</p>
  </div>
</template>

<script setup lang="ts">
/**
 * View-only attribute listing for a directory user — what an admin without
 * user.edit (e.g. a READ_ONLY admin) sees in place of the edit form.
 * Password-bearing attributes are listed but their values are never shown.
 */
import { computed, ref } from 'vue'

const props = defineProps<{
  attributes: Record<string, string[] | string | null>
}>()

interface AttrRow {
  name: string
  values: string[]
  masked: boolean
}

const MASKED_ATTRS = new Set(['userpassword', 'authpassword', 'unicodepwd', 'pwdhistory'])

const filter = ref<string>('')

const rows = computed<AttrRow[]>(() =>
  Object.entries(props.attributes || {})
    .map(([name, v]) => ({
      name,
      values: Array.isArray(v) ? v : (v == null || v === '' ? [] : [String(v)]),
      masked: MASKED_ATTRS.has(name.toLowerCase()),
    }))
    .filter(r => r.values.length > 0)
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })),
)

const filteredRows = computed<AttrRow[]>(() => {
  const q = filter.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter(r =>
    r.name.toLowerCase().includes(q)
    || (!r.masked && r.values.some(v => v.toLowerCase().includes(q))))
})
</script>
