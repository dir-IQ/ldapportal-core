<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { ref, computed } from 'vue'
import { SECTIONS, sectionIsDirty } from './sectionsRegistry'
import { useAuthStore } from '@/stores/auth'

const props = defineProps({
  activeId: { type: String, required: true },
  form:     { type: Object, default: null },
  savedForm:{ type: Object, default: null },
})
defineEmits(['select'])

const auth = useAuthStore()
const query = ref('')
const searchInput = ref(null)

const visibleSections = computed(() => (
  auth.isCommunityDistribution ? SECTIONS.filter(s => !s.hideOnCommunity) : SECTIONS
))

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return visibleSections.value
  return visibleSections.value.filter(s => s.label.toLowerCase().includes(q))
})

function isDirty(section) {
  return sectionIsDirty(section, props.form, props.savedForm)
}

// Exposed so the parent can wire a keyboard shortcut (Cmd/Ctrl+K) to focus
// the search input without poking at the DOM from the outside.
function focusSearch() {
  searchInput.value?.focus()
  searchInput.value?.select()
}
defineExpose({ focusSearch })
</script>

<template>
  <aside class="w-56 shrink-0 border-r border-gray-200 bg-white">
    <div class="p-3 border-b border-gray-100">
      <input
        ref="searchInput"
        v-model="query"
        type="search"
        aria-label="Search settings"
        placeholder="Search settings… (⌘K)"
        class="input w-full text-sm"
      />
    </div>
    <nav class="py-2">
      <button v-for="s in filtered" :key="s.id"
              type="button"
              @click="$emit('select', s.id)"
              class="w-full text-left px-4 py-2 text-sm border-l-2 transition-colors flex items-center gap-2.5"
              :class="s.id === activeId
                ? 'border-blue-500 bg-blue-50 text-blue-700 font-medium'
                : 'border-transparent text-gray-700 hover:bg-gray-50'">
        <svg class="w-4 h-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path :d="s.icon" />
        </svg>
        <span class="flex-1 min-w-0 truncate">{{ s.label }}</span>
        <span v-if="isDirty(s)"
              class="w-1.5 h-1.5 rounded-full bg-amber-500 shrink-0"
              title="Unsaved changes in this section"></span>
      </button>
      <p v-if="!filtered.length" class="px-4 py-3 text-xs text-gray-500">No matches.</p>
    </nav>
  </aside>
</template>
