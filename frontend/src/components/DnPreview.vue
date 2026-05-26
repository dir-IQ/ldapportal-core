<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <span class="relative inline" @mouseenter="show" @mouseleave="hide" ref="anchorRef">
    <slot />
    <Teleport to="body">
      <Transition name="preview-fade">
        <div v-if="visible && entry" class="fixed z-50 w-72 bg-white border border-gray-200 rounded-xl shadow-xl p-4"
             :style="{ top: pos.top + 'px', left: pos.left + 'px' }">
          <div class="flex items-center gap-2 mb-2">
            <div class="w-8 h-8 rounded-full bg-blue-100 text-blue-600 flex items-center justify-center text-xs font-bold shrink-0">
              {{ initial }}
            </div>
            <div class="min-w-0">
              <div class="text-sm font-medium text-gray-900 truncate">{{ entry.displayName || entry.cn || dn }}</div>
              <div v-if="entry.mail" class="text-[10px] text-gray-500 truncate">{{ entry.mail }}</div>
            </div>
          </div>
          <div class="text-[10px] font-mono text-gray-500 truncate mb-2" :title="dn">{{ dn }}</div>
          <div v-if="entry.groups && entry.groups.length" class="flex flex-wrap gap-1">
            <span v-for="g in entry.groups.slice(0, 5)" :key="g"
                  class="bg-gray-100 text-gray-600 text-[10px] px-1.5 py-0.5 rounded">{{ g }}</span>
            <span v-if="entry.groups.length > 5" class="text-[10px] text-gray-500">+{{ entry.groups.length - 5 }} more</span>
          </div>
          <div v-if="loading" class="text-xs text-gray-500 text-center py-2">Loading...</div>
        </div>
      </Transition>
    </Teleport>
  </span>
</template>

<script setup>
import { ref, computed } from 'vue'
import { searchUsers } from '@/api/users'

const props = defineProps({
  dn: { type: String, required: true },
  directoryId: { type: String, default: '' },
})

const visible = ref(false)
const loading = ref(false)
const entry = ref(null)
const anchorRef = ref(null)
const pos = ref({ top: 0, left: 0 })
let hoverTimeout = null
const cache = new Map()

const initial = computed(() => {
  const name = entry.value?.displayName || entry.value?.cn || props.dn || '?'
  return name[0].toUpperCase()
})

function show() {
  hoverTimeout = setTimeout(async () => {
    if (!anchorRef.value) return
    const rect = anchorRef.value.getBoundingClientRect()
    pos.value = {
      top: rect.bottom + 4 + window.scrollY,
      left: Math.min(rect.left, window.innerWidth - 300),
    }
    visible.value = true

    if (cache.has(props.dn)) {
      entry.value = cache.get(props.dn)
      return
    }

    if (!props.directoryId) {
      entry.value = { cn: props.dn.split(',')[0]?.replace(/^[^=]+=/, '') || props.dn }
      return
    }

    loading.value = true
    try {
      const { data } = await searchUsers(props.directoryId, {
        filter: `(distinguishedName=${props.dn})`,
        size: 1,
      })
      const user = data?.content?.[0] || data?.[0]
      if (user) {
        entry.value = user
        cache.set(props.dn, user)
      } else {
        entry.value = { cn: props.dn.split(',')[0]?.replace(/^[^=]+=/, '') || props.dn }
      }
    } catch {
      entry.value = { cn: props.dn.split(',')[0]?.replace(/^[^=]+=/, '') || props.dn }
    }
    loading.value = false
  }, 400) // 400ms hover delay
}

function hide() {
  clearTimeout(hoverTimeout)
  visible.value = false
}
</script>

<style>
.preview-fade-enter-active, .preview-fade-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.preview-fade-enter-from { opacity: 0; transform: translateY(4px); }
.preview-fade-leave-to { opacity: 0; }
</style>
