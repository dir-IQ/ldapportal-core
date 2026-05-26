<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <span :title="absolute" class="cursor-default">{{ relative }}</span>
</template>

<script setup>
import { computed } from 'vue'
import { formatRelativeTime, formatAbsoluteTime } from '@/composables/useRelativeTime'

const props = defineProps({
  value: { type: [String, Date, Number], default: null },
})

const relative = computed(() => formatRelativeTime(props.value))
const absolute = computed(() => formatAbsoluteTime(props.value))
</script>
