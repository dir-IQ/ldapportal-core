<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Edits a sync set's attribute-mapping rules (rename + single ${value} template).
  Mirrors the engine's capability exactly: blank target keeps the source name,
  blank/${value} template passes the value through, first matching rule wins.
  Empty / blank-source rows are pruned by the caller on save.
-->
<template>
  <div class="space-y-2">
    <div v-if="!rules.length" class="text-xs text-gray-500">
      No mappings — every source attribute is synced to the target unchanged.
    </div>

    <div v-for="(rule, i) in rules" :key="i" class="flex items-center gap-2">
      <div class="flex-1">
        <label :for="`tr-src-${i}`" class="sr-only">Source attribute for mapping {{ i + 1 }}</label>
        <input :id="`tr-src-${i}`" v-model="rule.sourceAttr" class="input input-sm w-full"
               placeholder="Source attribute" />
      </div>
      <span class="text-gray-400" aria-hidden="true">→</span>
      <div class="flex-1">
        <label :for="`tr-tgt-${i}`" class="sr-only">Target attribute for mapping {{ i + 1 }}</label>
        <input :id="`tr-tgt-${i}`" v-model="rule.targetAttr" class="input input-sm w-full"
               placeholder="Target (same as source)" />
      </div>
      <div class="flex-1">
        <label :for="`tr-tpl-${i}`" class="sr-only">Value template for mapping {{ i + 1 }}</label>
        <input :id="`tr-tpl-${i}`" v-model="rule.valueTemplate" class="input input-sm w-full"
               placeholder="Value template (${value})" />
      </div>
      <button type="button" class="btn-neutral btn-compact" :aria-label="`Remove mapping ${i + 1}`"
              @click="remove(i)">Remove</button>
    </div>

    <button type="button" class="btn-secondary btn-compact" @click="add">+ Add mapping</button>
  </div>
</template>

<script setup lang="ts">
import type { SyncTransformRule } from '@/api/sync'

// Two-way bound to the parent form's array. Field edits mutate the shared
// objects in place; add/remove reassign the array so the parent re-syncs.
const rules = defineModel<SyncTransformRule[]>('rules', { default: () => [] })

function add(): void {
  rules.value = [...rules.value, { sourceAttr: '', targetAttr: '', valueTemplate: '' }]
}

function remove(i: number): void {
  rules.value = rules.value.filter((_, idx) => idx !== i)
}
</script>
