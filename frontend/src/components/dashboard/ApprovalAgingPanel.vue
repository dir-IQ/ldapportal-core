<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { computed } from 'vue'

const props = defineProps({
  aging: { type: Object, required: true },
})

const total = computed(() => {
  const a = props.aging
  if (!a) return 0
  return (a.lessThan24h ?? 0) + (a.oneToThreeDays ?? 0) + (a.threeToSevenDays ?? 0) + (a.moreThanSevenDays ?? 0)
})

const buckets = computed(() => [
  { label: '< 24 hours', count: props.aging?.lessThan24h ?? 0, color: 'bg-green-500' },
  { label: '1–3 days', count: props.aging?.oneToThreeDays ?? 0, color: 'bg-yellow-500' },
  { label: '3–7 days', count: props.aging?.threeToSevenDays ?? 0, color: 'bg-amber-500' },
  { label: '7+ days', count: props.aging?.moreThanSevenDays ?? 0, color: 'bg-red-500' },
])

function barWidth(count) {
  if (total.value === 0) return '0%'
  return Math.max(2, (count / total.value) * 100) + '%'
}
</script>

<template>
  <div class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
    <div class="px-5 py-3 border-b border-gray-100">
      <h2 class="text-sm font-semibold text-gray-700">Approval Aging</h2>
    </div>
    <div class="p-5 space-y-3">
      <div v-if="total === 0" class="text-sm text-gray-500 text-center py-4">No pending approvals</div>
      <template v-else>
        <div v-for="(bucket, idx) in buckets" :key="idx" class="flex items-center gap-3">
          <span class="text-xs text-gray-500 w-20 shrink-0 text-right">{{ bucket.label }}</span>
          <div class="flex-1 h-5 bg-gray-100 rounded-full overflow-hidden">
            <div class="h-full rounded-full transition-all duration-300"
                 :class="bucket.color"
                 :style="{ width: barWidth(bucket.count) }"></div>
          </div>
          <span class="text-sm font-medium text-gray-700 w-8 text-right">{{ bucket.count }}</span>
        </div>
      </template>
    </div>
  </div>
</template>
