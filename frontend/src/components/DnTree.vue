<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <ul class="dn-tree" :class="{ 'ml-4': depth > 0 }">
    <li v-for="node in nodes" :key="node.dn">
      <div
        class="flex items-center gap-1 px-2 py-1.5 rounded cursor-pointer text-sm select-none"
        :class="selectedDn === node.dn ? 'bg-blue-100 text-blue-800 font-medium' : 'text-gray-700 hover:bg-gray-100'"
        @click="select(node)"
      >
        <!-- Expand/collapse toggle -->
        <button
          v-if="node.hasChildren"
          aria-label="Toggle children"
          class="w-4 h-4 flex items-center justify-center shrink-0 text-gray-500 hover:text-gray-600"
          @click.stop="toggle(node)"
        >
          <svg
            class="w-3 h-3 transition-transform duration-150"
            :class="{ 'rotate-90': expanded.has(node.dn) }"
            viewBox="0 0 12 12" fill="currentColor"
          ><path d="M4 2l5 4-5 4z"/></svg>
        </button>
        <span v-else class="w-4 shrink-0"></span>

        <!-- Node label -->
        <span class="truncate font-mono text-xs">{{ node.rdn || node.dn }}</span>
        <span v-if="childrenMap.has(node.dn)" class="shrink-0 text-[10px] text-gray-600 bg-gray-100 rounded-full px-1.5 leading-4">{{ childrenMap.get(node.dn).length }}</span>
      </div>

      <!-- Children (lazy-loaded, recursive) -->
      <template v-if="node.hasChildren && expanded.has(node.dn)">
        <div v-if="loading.has(node.dn)" class="ml-8 py-1 text-xs text-gray-500">Loading…</div>
        <DnTree
          v-else-if="childrenMap.has(node.dn)"
          :ref="el => setChildRef(node.dn, el)"
          :nodes="childrenMap.get(node.dn)"
          :depth="depth + 1"
          :selected-dn="selectedDn"
          :load-children="loadChildren"
          @select="(dn) => $emit('select', dn)"
        />
      </template>
    </li>
  </ul>
</template>

<script setup>
import { reactive, ref } from 'vue'

const props = defineProps({
  nodes:        { type: Array, required: true },
  depth:        { type: Number, default: 0 },
  selectedDn:   { type: String, default: '' },
  loadChildren: { type: Function, required: true },
})

const emit = defineEmits(['select'])

const expanded    = reactive(new Set())
const loading     = reactive(new Set())
const childrenMap = ref(new Map())
const childRefs   = new Map()

function setChildRef(dn, el) {
  if (el) {
    childRefs.set(dn, el)
  } else {
    childRefs.delete(dn)
  }
}

async function toggle(node) {
  if (expanded.has(node.dn)) {
    expanded.delete(node.dn)
    return
  }

  expanded.add(node.dn)

  // Lazy-load children if not cached
  if (!childrenMap.value.has(node.dn)) {
    loading.add(node.dn)
    try {
      const children = await props.loadChildren(node.dn)
      const updated = new Map(childrenMap.value)
      updated.set(node.dn, children)
      childrenMap.value = updated
    } catch (e) {
      console.warn('Failed to load children for', node.dn, e)
      expanded.delete(node.dn)
    } finally {
      loading.delete(node.dn)
    }
  }
}

function select(node) {
  emit('select', node.dn)
  // Auto-expand on select if it has children and isn't expanded
  if (node.hasChildren && !expanded.has(node.dn)) {
    toggle(node)
  }
}

/**
 * Refresh a node's children from externally provided data.
 * Propagates recursively through child DnTree instances until
 * the target DN is found at the correct level.
 */
function refreshNode(dn, children) {
  // Check if the target DN is one of our direct nodes
  const node = props.nodes.find(n => n.dn === dn)
  if (node) {
    // This is our level — update childrenMap and expand
    const updated = new Map(childrenMap.value)
    updated.set(dn, children)
    childrenMap.value = updated
    expanded.add(dn)
    node.hasChildren = children.length > 0
    return true
  }

  // Not at this level — delegate to child DnTree instances
  for (const [, childTree] of childRefs) {
    if (childTree?.refreshNode(dn, children)) {
      return true
    }
  }
  return false
}

defineExpose({ refreshNode })
</script>
