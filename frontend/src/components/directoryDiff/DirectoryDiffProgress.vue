<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <section class="bg-white border border-gray-200 rounded-xl p-5" aria-live="polite">
    <ol class="space-y-2 mb-5 text-sm">
      <li v-for="step in steps" :key="step.phase" class="flex items-center gap-2" :data-testid="`step-${step.phase}`">
        <span :class="['inline-block w-2.5 h-2.5 rounded-full', stepDot(step.phase)]" aria-hidden="true"></span>
        <span :class="stepState(step.phase) === 'todo' ? 'text-gray-400' : 'text-gray-800'">{{ step.label }}</span>
        <span v-if="stepState(step.phase) === 'active'" class="sr-only">(in progress)</span>
      </li>
    </ol>

    <div class="mb-1 flex justify-between text-sm text-gray-700">
      <span>{{ PHASE_LABELS[run.phase] }}<template v-if="run.total">: {{ n(run.processed) }} of {{ n(run.total) }}</template><template v-else-if="run.processed">: {{ n(run.processed) }} entries read</template></span>
      <span v-if="percent !== null" class="tabular-nums">{{ percent }}%</span>
    </div>
    <div class="h-2 rounded-full bg-gray-100 overflow-hidden mb-3"
      role="progressbar" :aria-valuenow="percent ?? undefined" aria-valuemin="0" aria-valuemax="100"
      :aria-label="PHASE_LABELS[run.phase]">
      <div class="h-full bg-blue-500 transition-all" :style="{ width: `${percent ?? 5}%` }"></div>
    </div>
    <p class="text-xs text-gray-500 mb-5">
      <template v-if="run.currentDn">Now at <span class="font-mono">{{ run.currentDn }}</span> · </template>
      running {{ formatDuration(run.startedAt) }}
      <template v-if="run.heartbeatAt"> · last update {{ formatDuration(run.heartbeatAt) }} ago</template>
    </p>

    <div v-if="run.phase === 'COMPARING'" class="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-5">
      <div v-for="c in CATEGORIES" :key="c" class="border border-gray-200 rounded-lg p-3">
        <div class="text-xs text-gray-500">{{ CATEGORY_LABELS[c] }} so far</div>
        <div class="text-lg font-semibold tabular-nums">
          {{ c === 'ONLY_B' ? '—' : n(countFor(run.counts, c)) }}
        </div>
        <div v-if="c === 'ONLY_B'" class="text-xs text-gray-400">counted at the end</div>
      </div>
    </div>

    <div class="flex items-center justify-between gap-4">
      <p class="text-xs text-gray-500">
        You can leave this page: the comparison keeps running, and Recent comparisons opens it when it's done.
      </p>
      <button type="button" class="btn-danger-soft" :disabled="cancelling" @click="emit('cancel')">
        {{ cancelling ? 'Cancelling…' : 'Cancel comparison' }}
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { DirectoryDiffPhase, DirectoryDiffRun } from '@/api/directoryDiff'
import { CATEGORIES, CATEGORY_LABELS, PHASE_LABELS, countFor, formatDuration, n } from './directoryDiffFormat'

const props = defineProps<{
  run: DirectoryDiffRun
  cancelling?: boolean
}>()

const emit = defineEmits<{ cancel: [] }>()

const ORDER: DirectoryDiffPhase[] = ['READING_A', 'READING_B', 'COMPARING']

const steps = computed(() => [
  { phase: 'READING_A' as const, label: 'Read side A' },
  { phase: 'READING_B' as const, label: 'Read side B' },
  { phase: 'COMPARING' as const, label: 'Compare entries' },
])

const percent = computed<number | null>(() => {
  if (!props.run.total) return null
  return Math.min(100, Math.floor((props.run.processed / props.run.total) * 100))
})

function stepState(phase: DirectoryDiffPhase): 'done' | 'active' | 'todo' {
  const at = ORDER.indexOf(props.run.phase)
  const i = ORDER.indexOf(phase)
  if (at === -1 || i < at) return 'done'
  return i === at ? 'active' : 'todo'
}

function stepDot(phase: DirectoryDiffPhase): string {
  const state = stepState(phase)
  return state === 'done' ? 'bg-green-500' : state === 'active' ? 'bg-blue-500 animate-pulse' : 'bg-gray-300'
}
</script>
