<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  One side of a content drift's attribute: its values, with the ones the other
  side lacks marked (inserted on the source side, deleted on the target side),
  and, for a single changed value, the characters that differ.
-->
<template>
  <template v-if="attr.hidden"><span class="italic text-gray-500">(hidden)</span></template>
  <template v-else-if="!values.length"><span class="italic text-gray-500">(none)</span></template>
  <template v-else>
    <div v-for="(v, i) in values" :key="i" :data-only="v.only || undefined">
      <template v-if="!v.only">{{ v.value }}</template>
      <template v-else-if="v.segments">
        <span class="sr-only">{{ onlyLabel }}: </span>
        <template v-for="(s, k) in v.segments" :key="k">
          <component :is="tag" v-if="s.changed" :class="markClass" data-testid="diff-char">{{ visibleWhitespace(s.text) }}</component>
          <template v-else>{{ s.text }}</template>
        </template>
      </template>
      <component :is="tag" v-else :class="markClass" data-testid="diff-value"><span
        class="sr-only">{{ onlyLabel }}: </span>{{ v.value }}</component>
    </div>
  </template>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { SyncDriftAttribute } from '@/api/sync'
import { shownValues, visibleWhitespace } from './valueDiff'

const props = defineProps<{
  attr: SyncDriftAttribute
  side: 'expected' | 'actual'
}>()

const values = computed(() => shownValues(props.attr, props.side))
// The sync would add what only the source has, and remove what only the target has.
const tag = computed(() => (props.side === 'expected' ? 'ins' : 'del'))
const onlyLabel = computed(() => (props.side === 'expected' ? 'not on target' : 'not in source'))
const markClass = computed(() => props.side === 'expected'
  ? 'rounded-sm bg-emerald-100 text-emerald-900 underline decoration-emerald-600 dark:bg-emerald-900/50 dark:text-emerald-100'
  : 'rounded-sm bg-red-100 text-red-900 line-through decoration-red-600 dark:bg-red-900/50 dark:text-red-100')
</script>
