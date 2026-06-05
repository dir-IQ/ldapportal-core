<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Compact approval-aging distribution for the Pending Approvals metric card's
  subtitle slot. Folds the former standalone Approval Aging panel into the
  card: a slim stacked bar (segments proportional to each age bucket, hover
  for exact counts) plus an "oldest" hint so staleness reads at a glance. The
  card's big value already shows the total, so this only adds the breakdown.
-->
<template>
  <span v-if="total === 0">Click to review</span>
  <span v-else class="block">
    <span class="flex h-1.5 w-full rounded-full overflow-hidden bg-gray-100" :title="tooltip" role="img" :aria-label="tooltip">
      <span v-for="b in nonEmpty" :key="b.key" :class="b.color" :style="{ width: b.pct }"></span>
    </span>
    <span class="mt-0.5 inline-block">{{ oldestLabel }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Aging {
  lessThan24h?: number
  oneToThreeDays?: number
  threeToSevenDays?: number
  moreThanSevenDays?: number
}
const props = defineProps<{ aging: Aging | null | undefined }>()

const BUCKETS = [
  { key: 'lessThan24h',      label: '< 24h', color: 'bg-green-500' },
  { key: 'oneToThreeDays',   label: '1–3d',  color: 'bg-yellow-500' },
  { key: 'threeToSevenDays', label: '3–7d',  color: 'bg-amber-500' },
  { key: 'moreThanSevenDays', label: '7+d',  color: 'bg-red-500' },
] as const

const counts = computed(() =>
  BUCKETS.map(b => ({ ...b, count: props.aging?.[b.key] ?? 0 })),
)
const total = computed(() => counts.value.reduce((s, b) => s + b.count, 0))
const nonEmpty = computed(() =>
  counts.value
    .filter(b => b.count > 0)
    .map(b => ({ ...b, pct: Math.max(4, (b.count / total.value) * 100) + '%' })),
)
const tooltip = computed(() =>
  counts.value.filter(b => b.count > 0).map(b => `${b.label}: ${b.count}`).join(' · '),
)
// The oldest non-empty bucket — scan from the 7+d end backwards.
const oldest = computed(() => [...counts.value].reverse().find(b => b.count > 0))
const oldestLabel = computed(() => {
  const o = oldest.value
  if (!o) return ''
  return o.key === 'lessThan24h' ? 'all under 24h' : `oldest ${o.label}`
})
</script>
