<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <ul :class="{ 'ml-4': depth > 0 }">
    <li v-for="node in nodes" :key="node.dn">
      <div
        class="flex items-center gap-1 px-2 py-1.5 rounded text-sm select-none"
        :class="[
          node.isGroup ? 'cursor-pointer' : 'cursor-default',
          selectedDn === node.dn ? 'bg-blue-100 text-blue-800 font-medium' : node.isGroup ? 'text-gray-700 hover:bg-gray-100' : 'text-gray-600'
        ]"
        @click="node.isGroup && $emit('select', node.dn)"
      >
        <!-- Expand/collapse toggle -->
        <button
          v-if="node.children.length > 0"
          class="w-4 h-4 flex items-center justify-center shrink-0 text-gray-500 hover:text-gray-600"
          @click.stop="toggle(node.dn)"
        >
          <svg
            class="w-3 h-3 transition-transform duration-150"
            :class="{ 'rotate-90': expanded.has(node.dn) }"
            viewBox="0 0 12 12" fill="currentColor"
          ><path d="M4 2l5 4-5 4z"/></svg>
        </button>
        <span v-else class="w-4 shrink-0"></span>

        <!-- Icon -->
        <svg v-if="node.isGroup" class="w-3.5 h-3.5 shrink-0 text-blue-500" viewBox="0 0 20 20" fill="currentColor">
          <path d="M13 6a3 3 0 11-6 0 3 3 0 016 0zM18 8a2 2 0 11-4 0 2 2 0 014 0zM14 15a4 4 0 00-8 0v1h8v-1zM6 8a2 2 0 11-4 0 2 2 0 014 0zM16 18v-1a5.972 5.972 0 00-.75-2.906A3.005 3.005 0 0119 17v1h-3zM4.75 14.094A5.973 5.973 0 004 17v1H1v-1a3 3 0 013.75-2.906z"/>
        </svg>
        <svg v-else class="w-3.5 h-3.5 shrink-0 text-gray-300" viewBox="0 0 20 20" fill="currentColor">
          <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z"/>
        </svg>

        <span class="truncate font-mono text-xs">{{ node.rdn }}</span>
        <span v-if="node.children.length > 0 && !node.isGroup"
          class="shrink-0 text-[10px] text-gray-600 bg-gray-100 rounded-full px-1.5 leading-4">{{ countGroups(node) }}</span>
      </div>

      <GroupTree
        v-if="node.children.length > 0 && expanded.has(node.dn)"
        :nodes="node.children"
        :depth="depth + 1"
        :selected-dn="selectedDn"
        @select="(dn) => $emit('select', dn)"
      />
    </li>
  </ul>
</template>

<script setup>
import { reactive, onMounted } from 'vue'

const props = defineProps({
  nodes:      { type: Array, required: true },
  depth:      { type: Number, default: 0 },
  selectedDn: { type: String, default: '' },
})

defineEmits(['select'])

const expanded = reactive(new Set())

// Auto-expand nodes at mount if they have few children (keeps tree navigable)
onMounted(() => {
  if (props.depth < 3) {
    for (const node of props.nodes) {
      if (node.children.length > 0) expanded.add(node.dn)
    }
  }
})

function toggle(dn) {
  if (expanded.has(dn)) expanded.delete(dn)
  else expanded.add(dn)
}

function countGroups(node) {
  let count = 0
  for (const child of node.children) {
    if (child.isGroup) count++
    count += countGroups(child)
  }
  return count
}
</script>
