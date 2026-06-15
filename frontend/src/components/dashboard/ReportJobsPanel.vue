<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { useRouter } from 'vue-router'

const props = defineProps({
  enabled: { type: Number, default: 0 },
  failed: { type: Number, default: 0 },
  to: { type: String, default: null },
})

const router = useRouter()

function go() {
  if (props.to) router.push(props.to)
}
</script>

<template>
  <div class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm"
       :class="to ? 'cursor-pointer hover:shadow-md transition-shadow' : ''"
       @click="go">
    <div class="px-5 py-3 border-b border-gray-100">
      <h2 class="text-sm font-semibold text-gray-700">Report Jobs</h2>
    </div>
    <div class="px-5 py-4 flex items-center gap-6">
      <div>
        <p class="text-2xl font-bold text-gray-900">{{ enabled }}</p>
        <p class="text-[10px] text-gray-500 uppercase tracking-wider">Enabled</p>
      </div>
      <div>
        <p class="text-2xl font-bold" :class="failed > 0 ? 'text-red-600' : 'text-green-700'">{{ failed }}</p>
        <p class="text-[10px] text-gray-500 uppercase tracking-wider">Failed</p>
      </div>
    </div>
  </div>
</template>
