<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Live progress of a sync set action (reconcile, content verification): what
  it's doing, a bar when the total is known, the entry it's on, the time
  elapsed and when it last reported. Times are aged on the server's clock
  (`asOf` from the status read) and tick every second between polls.
-->
<template>
  <div class="text-sm text-gray-600 space-y-1" role="status">
    <p>
      {{ heading }}
      <span v-if="elapsed" class="text-gray-500"> · {{ elapsed }} elapsed</span>
    </p>
    <div v-if="total && total > 0" class="h-1.5 w-full rounded bg-gray-200 dark:bg-gray-700 overflow-hidden"
         role="progressbar" :aria-label="`${heading} progress`" :aria-valuenow="processed ?? 0" aria-valuemin="0"
         :aria-valuemax="total">
      <div class="h-full bg-blue-500 transition-all" :style="{ width: `${percent}%` }" />
    </div>
    <p v-if="currentDn" class="text-xs text-gray-500 truncate" :title="currentDn">
      {{ currentLabel }} <span class="font-mono">{{ currentDn }}</span>
    </p>
    <p v-if="lastUpdate" class="text-xs text-gray-500">
      Still working · last update {{ lastUpdate }} ago<template v-if="hint">. {{ hint }}</template>
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  heading: string
  processed?: number | null
  total?: number | null
  currentDn?: string | null
  currentLabel?: string
  startedAt?: string | null
  heartbeatAt?: string | null
  /** The server's time when the status was read. */
  asOf?: string | null
  /** Shown after "last update …" (e.g. why one entry can take a while). */
  hint?: string
}>(), {
  processed: null, total: null, currentDn: null, currentLabel: 'Now checking',
  startedAt: null, heartbeatAt: null, asOf: null, hint: '',
})

const now = ref<number>(Date.now())
const clockOffset = ref<number>(0)
let ticker: ReturnType<typeof setInterval> | null = null

watch(() => props.asOf, (asOf) => {
  now.value = Date.now()
  clockOffset.value = asOf ? Date.parse(asOf) - now.value : 0
}, { immediate: true })

onMounted(() => { ticker = setInterval(() => { now.value = Date.now() }, 1000) })
onBeforeUnmount(() => { if (ticker !== null) clearInterval(ticker) })

function duration(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}

const serverNow = computed<number>(() => now.value + clockOffset.value)
const elapsed = computed<string>(() => props.startedAt ? duration(serverNow.value - Date.parse(props.startedAt)) : '')
const lastUpdate = computed<string>(() =>
  props.heartbeatAt ? duration(serverNow.value - Date.parse(props.heartbeatAt)) : '')
const percent = computed<number>(() => {
  const t = props.total ?? 0
  return t > 0 ? Math.min(100, Math.round(((props.processed ?? 0) / t) * 100)) : 0
})
</script>
