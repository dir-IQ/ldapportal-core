<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="space-y-2">
    <!-- Toolbar -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-2">
        <button type="button" @click="addSection" class="btn-secondary text-xs">+ Add Section</button>
        <span class="text-xs text-gray-500">Drag fields to reorder. Choose width: 1/3, 1/2, 2/3, or full.</span>
      </div>
      <div class="flex items-center gap-2">
        <label v-if="!hideDnToggle" class="flex items-center gap-2 text-xs text-gray-600 cursor-pointer select-none">
          <input type="checkbox" v-model="localShowDnField" class="rounded" />
          Show DN field on form
        </label>
        <button
          type="button"
          @click="showPreview = !showPreview"
          :class="showPreview ? 'btn-primary' : 'btn-secondary'"
          class="text-xs"
        >{{ showPreview ? 'Hide Preview' : 'Preview' }}</button>
      </div>
    </div>

    <!-- Live Preview -->
    <div v-if="showPreview" class="border border-blue-200 bg-blue-50/30 rounded-xl p-4">
      <h4 class="text-sm font-semibold text-blue-800 mb-3">Form Preview</h4>
      <div class="bg-white rounded-lg border border-gray-200 p-4 space-y-2">
        <template v-for="(section, sIdx) in previewSections" :key="section.id">
          <fieldset v-if="section.fields.length" class="space-y-2">
            <legend v-if="section.name" class="text-sm font-semibold text-gray-800 pb-1 border-b border-gray-100 w-full mb-2">{{ section.name }}</legend>
            <div class="grid grid-cols-6 gap-2">
              <template v-for="field in section.fields" :key="field.attributeName">
                <!-- RDN field -->
                <div v-if="field.rdn" :style="{ gridColumn: localShowDnField ? 'span 2' : `span ${field.columnSpan || 6}` }">
                  <label class="block text-sm font-medium text-gray-700 mb-1">
                    {{ field.customLabel || field.attributeName }}
                    <span class="text-red-500">*</span>
                    <span class="text-xs bg-amber-100 text-amber-700 rounded px-1 ml-1">RDN</span>
                  </label>
                  <div class="w-full h-9 border border-gray-200 rounded-lg bg-gray-50"></div>
                </div>
                <!-- Computed DN (shown immediately after RDN when enabled) -->
                <div v-if="field.rdn && localShowDnField" class="col-span-4">
                  <label class="block text-sm font-medium text-gray-700 mb-1">
                    DN
                    <span class="text-xs bg-gray-100 text-gray-600 rounded px-1 ml-1">computed</span>
                  </label>
                  <div class="w-full h-9 border border-gray-200 rounded-lg bg-gray-100 flex items-center px-3 text-xs text-gray-600 italic">
                    {{ field.attributeName }}=…,ou=…,dc=…
                  </div>
                </div>
                <!-- Regular field -->
                <div
                  v-if="!field.rdn"
                  :style="{ gridColumn: `span ${field.columnSpan || 6}` }"
                >
                  <label class="block text-sm font-medium text-gray-700 mb-1">
                    {{ field.customLabel || field.attributeName }}
                    <span v-if="field.requiredOnCreate" class="text-red-500">*</span>
                  </label>
                  <div v-if="field.inputType === 'TEXTAREA' || field.inputType === 'MULTI_VALUE'" class="w-full h-16 border border-gray-200 rounded-lg bg-gray-50"></div>
                  <div v-else-if="field.inputType === 'BOOLEAN'" class="flex items-center gap-2">
                    <div class="w-4 h-4 border border-gray-300 rounded bg-white"></div>
                    <span class="text-sm text-gray-500">{{ field.customLabel || field.attributeName }}</span>
                  </div>
                  <div v-else class="w-full h-9 border border-gray-200 rounded-lg bg-gray-50"></div>
                </div>
              </template>
            </div>
          </fieldset>
        </template>
      </div>
    </div>

    <!-- Section editor -->
    <div class="space-y-2">
      <div
        v-for="(section, sIdx) in sections"
        :key="section.id"
        class="border border-gray-200 rounded-xl overflow-hidden"
        :class="[
          fieldDrag.field && fieldDrag.overSection === sIdx ? 'ring-2 ring-blue-400' : '',
          sectionDrag.source !== null && sectionDrag.source !== sIdx && sectionDrag.overIdx === sIdx ? 'border-t-2 border-t-blue-500' : '',
          sectionDrag.source === sIdx ? 'opacity-40' : '',
        ]"
        @dragover.prevent="onDragOverSection($event, sIdx)"
        @dragleave="onDragLeaveSection"
        @drop="onDropSection($event, sIdx)"
      >
        <!-- Section header (draggable for section reorder) -->
        <div
          class="flex items-center gap-2 px-3 py-2 bg-gray-50 border-b border-gray-100"
          :class="sections.length > 1 ? 'cursor-grab' : ''"
          :draggable="sections.length > 1"
          @dragstart.stop="onSectionDragStart($event, sIdx)"
          @dragend.stop="onSectionDragEnd"
          @dragover.prevent.stop="onSectionHeaderDragOver($event, sIdx)"
          @drop.prevent.stop="onSectionDrop($event, sIdx)"
        >
          <svg class="w-4 h-4 text-gray-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16"/>
          </svg>
          <input
            v-model="section.name"
            placeholder="Section name (optional)"
            class="flex-1 bg-transparent text-sm font-medium text-gray-700 placeholder-gray-400 focus:outline-none"
            @input="syncToParent"
            @mousedown.stop
          />
          <span class="text-xs text-gray-500">{{ section.fields.length }} field{{ section.fields.length !== 1 ? 's' : '' }}</span>
          <button v-if="sIdx > 0" type="button" @click="moveSectionUp(sIdx)" class="text-gray-500 hover:text-blue-500 text-xs" title="Move section up">&uarr;</button>
          <button v-if="sIdx < sections.length - 1" type="button" @click="moveSectionDown(sIdx)" class="text-gray-500 hover:text-blue-500 text-xs" title="Move section down">&darr;</button>
          <button
            v-if="sections.length > 1"
            type="button"
            @click="removeSection(sIdx)"
            class="text-gray-500 hover:text-red-500 text-xs"
            title="Remove section"
          >&times;</button>
        </div>

        <!-- Fields in section -->
        <div class="p-2 min-h-[48px]">
          <div v-if="section.fields.length === 0" class="text-center text-xs text-gray-500 py-3">
            Drag fields here or add attributes above
          </div>
          <div class="grid grid-cols-6 gap-2">
            <template
              v-for="(field, fIdx) in section.fields"
              :key="field.attributeName"
            >
              <!-- Drop indicator line (before this field) -->
              <div
                v-if="fieldDrag.field && fieldDrag.overSection === sIdx && fieldDrag.overIdx === fIdx && !(fieldDrag.sourceSIdx === sIdx && fieldDrag.sourceFIdx === fIdx)"
                class="col-span-6 h-0.5 bg-blue-500 rounded-full -my-0.5 pointer-events-none"
              ></div>

              <!-- RDN field card -->
              <div
                v-if="field.rdn"
                :style="{ gridColumn: localShowDnField ? 'span 2' : `span ${field.columnSpan || 6}` }"
                :class="[
                  fieldDrag.field?.attributeName === field.attributeName ? 'opacity-30' : '',
                ]"
                class="group relative flex items-center gap-2 px-3 py-2 bg-amber-50 border border-amber-200 rounded-lg hover:border-amber-400 cursor-grab transition-colors"
                draggable="true"
                @dragstart="onFieldDragStart($event, sIdx, fIdx, field)"
                @dragend="onFieldDragEnd"
                @dragover.prevent.stop="onFieldDragOver($event, sIdx, fIdx)"
                @drop.prevent.stop="onFieldDrop($event, sIdx, fIdx)"
              >
                <svg class="w-3.5 h-3.5 text-amber-300 shrink-0" viewBox="0 0 20 20" fill="currentColor">
                  <path d="M7 2a2 2 0 10.001 4.001A2 2 0 007 2zm0 6a2 2 0 10.001 4.001A2 2 0 007 8zm0 6a2 2 0 10.001 4.001A2 2 0 007 14zm6-8a2 2 0 10-.001-4.001A2 2 0 0013 6zm0 2a2 2 0 10.001 4.001A2 2 0 0013 8zm0 6a2 2 0 10.001 4.001A2 2 0 0013 14z"/>
                </svg>
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-1.5">
                    <span class="text-sm font-medium text-gray-800 truncate">{{ field.customLabel || field.attributeName }}</span>
                    <span class="text-[10px] font-mono text-gray-500" v-if="field.customLabel">{{ field.attributeName }}</span>
                    <span class="text-[10px] bg-amber-100 text-amber-700 rounded px-1 font-medium">RDN</span>
                    <span class="text-red-400 text-xs">*</span>
                  </div>
                  <div class="text-[10px] text-gray-500">{{ field.inputType }} · {{ localShowDnField ? '1/3' : spanLabel(field.columnSpan) }}</div>
                </div>
                <!-- Column span selector (only when DN is not shown, otherwise forced to 1/3) -->
                <div v-if="!localShowDnField" class="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    v-for="opt in spanOptions"
                    :key="opt.span"
                    type="button"
                    @click="setColumnSpan(sIdx, fIdx, opt.span)"
                    :class="[
                      'px-1.5 h-5 rounded text-[10px] font-bold border transition-colors whitespace-nowrap',
                      (field.columnSpan || 6) === opt.span
                        ? 'bg-blue-600 text-white border-blue-600'
                        : 'bg-white text-gray-500 border-gray-300 hover:border-blue-400'
                    ]"
                    :title="opt.label"
                  >{{ opt.label }}</button>
                </div>
              </div>
              <!-- Computed DN card (shown after RDN when enabled) -->
              <div
                v-if="field.rdn && localShowDnField"
                class="col-span-4 flex items-center gap-2 px-3 py-2 bg-gray-50 border border-dashed border-gray-300 rounded-lg"
              >
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-1.5">
                    <span class="text-sm font-medium text-gray-500">DN</span>
                    <span class="text-[10px] bg-gray-100 text-gray-600 rounded px-1 font-medium">computed</span>
                  </div>
                  <div class="text-[10px] text-gray-500">Auto-generated from RDN · 2/3</div>
                </div>
              </div>

              <!-- Regular field card -->
              <div
                v-if="!field.rdn"
                :style="{ gridColumn: `span ${field.columnSpan || 6}` }"
                :class="[
                  fieldDrag.field?.attributeName === field.attributeName ? 'opacity-30' : '',
                ]"
                class="group relative flex items-center gap-2 px-3 py-2 bg-white border border-gray-200 rounded-lg hover:border-blue-300 cursor-grab transition-colors"
                draggable="true"
                @dragstart="onFieldDragStart($event, sIdx, fIdx, field)"
                @dragend="onFieldDragEnd"
                @dragover.prevent.stop="onFieldDragOver($event, sIdx, fIdx)"
                @drop.prevent.stop="onFieldDrop($event, sIdx, fIdx)"
              >
                <!-- Drag handle -->
                <svg class="w-3.5 h-3.5 text-gray-300 shrink-0" viewBox="0 0 20 20" fill="currentColor">
                  <path d="M7 2a2 2 0 10.001 4.001A2 2 0 007 2zm0 6a2 2 0 10.001 4.001A2 2 0 007 8zm0 6a2 2 0 10.001 4.001A2 2 0 007 14zm6-8a2 2 0 10-.001-4.001A2 2 0 0013 6zm0 2a2 2 0 10.001 4.001A2 2 0 0013 8zm0 6a2 2 0 10.001 4.001A2 2 0 0013 14z"/>
                </svg>

                <!-- Field info -->
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-1.5">
                    <span class="text-sm font-medium text-gray-800 truncate">{{ field.customLabel || field.attributeName }}</span>
                    <span class="text-[10px] font-mono text-gray-500" v-if="field.customLabel">{{ field.attributeName }}</span>
                    <span v-if="field.requiredOnCreate" class="text-red-400 text-xs">*</span>
                  </div>
                  <div class="text-[10px] text-gray-500">{{ field.inputType }}</div>
                </div>

                <!-- Column span selector -->
                <div class="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    v-for="opt in spanOptions"
                    :key="opt.span"
                    type="button"
                    @click="setColumnSpan(sIdx, fIdx, opt.span)"
                    :class="[
                      'px-1.5 h-5 rounded text-[10px] font-bold border transition-colors whitespace-nowrap',
                      (field.columnSpan || 6) === opt.span
                        ? 'bg-blue-600 text-white border-blue-600'
                        : 'bg-white text-gray-500 border-gray-300 hover:border-blue-400'
                    ]"
                    :title="opt.label"
                  >{{ opt.label }}</button>
                </div>
              </div>
            </template>

            <!-- Drop indicator at end of field list -->
            <div
              v-if="fieldDrag.field && fieldDrag.overSection === sIdx && fieldDrag.overIdx === section.fields.length && !(fieldDrag.sourceSIdx === sIdx && fieldDrag.sourceFIdx === section.fields.length)"
              class="col-span-6 h-0.5 bg-blue-500 rounded-full -my-0.5 pointer-events-none"
            ></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'

const props = defineProps({
  attributeConfigs: { type: Array, required: true },
  showDnField: { type: Boolean, default: true },
  hideDnToggle: { type: Boolean, default: false },
})
const emit = defineEmits(['update:attributeConfigs', 'update:showDnField'])

const showPreview = ref(false)
const localShowDnField = ref(props.showDnField)

// Column span options: 6-column grid allows 1/3, 1/2, 2/3, and full widths
const spanOptions = [
  { span: 2, label: '1/3' },
  { span: 3, label: '1/2' },
  { span: 4, label: '2/3' },
  { span: 6, label: 'Full' },
]
const spanLabels = Object.fromEntries(spanOptions.map(o => [o.span, o.label]))
function spanLabel(span) { return spanLabels[span] || 'Full' }

watch(() => props.showDnField, (v) => { localShowDnField.value = v })
watch(localShowDnField, (v) => {
  emit('update:showDnField', v)
  // When DN display is toggled on, force RDN field to 1/3 width
  if (v) {
    for (const section of sections.value) {
      for (const field of section.fields) {
        if (field.rdn) field.columnSpan = 2
      }
    }
    syncToParent()
  }
})

let sectionIdCounter = 0
function nextSectionId() { return `section-${++sectionIdCounter}` }

// ── Build sections from flat attribute list ──────────────────────────────────

const sections = ref([])

// Tracks last-known position of hidden fields so un-hiding restores them
// Map<attributeName, { sectionName, afterAttribute }>
const hiddenPositions = ref(new Map())

function buildSections(attrs) {
  const map = new Map()
  for (const attr of attrs) {
    if (attr.hidden) {
      // Remember position relative to the previous visible field
      continue
    }
    const key = attr.sectionName || ''
    if (!map.has(key)) {
      map.set(key, { id: nextSectionId(), name: key, fields: [] })
    }
    const field = { ...attr }
    // RDN field defaults to 1/3 width when DN display is enabled
    if (field.rdn && localShowDnField.value && !field.columnSpan) {
      field.columnSpan = 2
    }
    map.get(key).fields.push(field)
  }
  const result = Array.from(map.values())
  if (result.length === 0) {
    result.push({ id: nextSectionId(), name: '', fields: [] })
  }
  // Ensure RDN field is always first in the first section
  moveRdnToFirst(result)
  // Build initial hidden position map
  recordHiddenPositions(attrs)
  return result
}

function moveRdnToFirst(sectionList) {
  for (let s = 0; s < sectionList.length; s++) {
    const rdnIdx = sectionList[s].fields.findIndex(f => f.rdn)
    if (rdnIdx > 0) {
      const [rdnF] = sectionList[s].fields.splice(rdnIdx, 1)
      sectionList[0].fields.unshift(rdnF)
      return
    } else if (rdnIdx === 0 && s === 0) {
      return // already in the right place
    }
  }
}

function recordHiddenPositions(attrs) {
  let lastVisible = null
  let lastSectionName = ''
  for (const attr of attrs) {
    if (!attr.hidden) {
      lastVisible = attr.attributeName
      lastSectionName = attr.sectionName || ''
    } else {
      hiddenPositions.value.set(attr.attributeName, {
        sectionName: attr.sectionName || lastSectionName,
        afterAttribute: lastVisible,
      })
    }
  }
}

/** Sections for preview (includes all fields). */
const previewSections = computed(() => sections.value)

// Initialize from props
sections.value = buildSections(props.attributeConfigs)

// Watch for external changes to attributeConfigs (e.g. attributes added/removed on the Attributes tab).
// Merge new attributes into existing sections rather than rebuilding, so empty sections survive.
let syncing = false
watch(() => props.attributeConfigs, (newConfigs) => {
  if (syncing) return

  const currentFields = sections.value.flatMap(s => s.fields)
  const currentNames = new Set(currentFields.map(f => f.attributeName))
  const visibleConfigs = newConfigs.filter(a => !a.hidden)
  const incomingNames = new Set(visibleConfigs.map(a => a.attributeName))

  // Detect RDN change (even when attribute set hasn't changed)
  const incomingRdn = visibleConfigs.find(a => a.rdn)?.attributeName || null
  const currentRdn = currentFields.find(f => f.rdn)?.attributeName || null
  const rdnChanged = incomingRdn !== currentRdn

  // Detect hidden→visible transitions
  const newlyVisible = visibleConfigs.filter(a => !currentNames.has(a.attributeName) && hiddenPositions.value.has(a.attributeName))
  const newlyHidden = newConfigs.filter(a => a.hidden && currentNames.has(a.attributeName))

  // Record positions of newly hidden fields before removing them
  for (const attr of newlyHidden) {
    for (let s = 0; s < sections.value.length; s++) {
      const idx = sections.value[s].fields.findIndex(f => f.attributeName === attr.attributeName)
      if (idx >= 0) {
        const prevField = idx > 0 ? sections.value[s].fields[idx - 1].attributeName : null
        hiddenPositions.value.set(attr.attributeName, {
          sectionName: sections.value[s].name || '',
          afterAttribute: prevField,
        })
        break
      }
    }
  }

  // Nothing changed — skip
  if (
    !rdnChanged &&
    newlyVisible.length === 0 &&
    newlyHidden.length === 0 &&
    currentNames.size === incomingNames.size &&
    [...currentNames].every(n => incomingNames.has(n))
  ) return

  // Remove fields that were deleted or hidden on the Attributes tab
  for (const section of sections.value) {
    section.fields = section.fields.filter(f => incomingNames.has(f.attributeName))
  }

  // Add newly-added visible fields
  for (const attr of visibleConfigs) {
    if (!currentNames.has(attr.attributeName)) {
      const field = { ...attr }
      if (field.rdn && localShowDnField.value) field.columnSpan = 2

      // Try to restore un-hidden fields to their previous position
      const savedPos = hiddenPositions.value.get(attr.attributeName)
      let inserted = false
      if (savedPos) {
        // Find the section matching the saved position
        const targetSection = sections.value.find(s => (s.name || '') === savedPos.sectionName) || sections.value[0]
        if (savedPos.afterAttribute) {
          const afterIdx = targetSection.fields.findIndex(f => f.attributeName === savedPos.afterAttribute)
          if (afterIdx >= 0) {
            targetSection.fields.splice(afterIdx + 1, 0, field)
            inserted = true
          }
        }
        if (!inserted) {
          targetSection.fields.unshift(field)
          inserted = true
        }
        hiddenPositions.value.delete(attr.attributeName)
      }

      if (!inserted) {
        sections.value[0].fields.push(field)
      }
    }
  }

  // Record positions of any newly hidden configs
  for (const attr of newConfigs.filter(a => a.hidden)) {
    if (!hiddenPositions.value.has(attr.attributeName)) {
      hiddenPositions.value.set(attr.attributeName, {
        sectionName: attr.sectionName || '',
        afterAttribute: null,
      })
    }
  }

  // Sync RDN flag changes and move new RDN to first position
  if (rdnChanged) {
    for (const section of sections.value) {
      for (const field of section.fields) {
        field.rdn = field.attributeName === incomingRdn
      }
    }
    // Move the new RDN field to index 0 of the first section
    for (let s = 0; s < sections.value.length; s++) {
      const rdnIdx = sections.value[s].fields.findIndex(f => f.rdn)
      if (rdnIdx >= 0) {
        if (s === 0 && rdnIdx === 0) break // already in place
        const [rdnF] = sections.value[s].fields.splice(rdnIdx, 1)
        if (localShowDnField.value) rdnF.columnSpan = 2
        sections.value[0].fields.unshift(rdnF)
        break
      }
    }
    syncToParent()
  }
}, { deep: true })

function flattenSections() {
  const result = []
  for (const section of sections.value) {
    for (const field of section.fields) {
      result.push({
        ...field,
        sectionName: section.name || '',
      })
    }
  }
  return result
}

function syncToParent() {
  syncing = true
  emit('update:attributeConfigs', flattenSections())
  // Allow the next tick to propagate before re-enabling the watch
  setTimeout(() => { syncing = false }, 0)
}

// ── Section management ───────────────────────────────────────────────────────

function addSection() {
  sections.value.push({ id: nextSectionId(), name: '', fields: [] })
}

function removeSection(idx) {
  const removed = sections.value.splice(idx, 1)[0]
  // Move orphaned fields to the first section
  if (removed.fields.length && sections.value.length) {
    sections.value[0].fields.push(...removed.fields)
  }
  syncToParent()
}

function moveSectionUp(idx) {
  if (idx <= 0) return
  const arr = sections.value
  ;[arr[idx - 1], arr[idx]] = [arr[idx], arr[idx - 1]]
  syncToParent()
}

function moveSectionDown(idx) {
  if (idx >= sections.value.length - 1) return
  const arr = sections.value
  ;[arr[idx], arr[idx + 1]] = [arr[idx + 1], arr[idx]]
  syncToParent()
}

// ── Column span ──────────────────────────────────────────────────────────────

function setColumnSpan(sIdx, fIdx, span) {
  sections.value[sIdx].fields[fIdx].columnSpan = span
  syncToParent()
}

// ── Field drag and drop (with drop position indicator) ───────────────────────

const fieldDrag = reactive({
  field: null,
  sourceSIdx: null,
  sourceFIdx: null,
  overSection: null,
  overIdx: null,
})

function onFieldDragStart(e, sIdx, fIdx, field) {
  fieldDrag.field = field
  fieldDrag.sourceSIdx = sIdx
  fieldDrag.sourceFIdx = fIdx
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', 'field')
}

function onFieldDragEnd() {
  fieldDrag.field = null
  fieldDrag.sourceSIdx = null
  fieldDrag.sourceFIdx = null
  fieldDrag.overSection = null
  fieldDrag.overIdx = null
}

function onFieldDragOver(e, sIdx, fIdx) {
  if (!fieldDrag.field) return
  fieldDrag.overSection = sIdx

  // Determine if cursor is in the top or bottom half of the field card
  const rect = e.currentTarget.getBoundingClientRect()
  const midY = rect.top + rect.height / 2
  fieldDrag.overIdx = e.clientY < midY ? fIdx : fIdx + 1
}

function onFieldDrop(e, targetSIdx, targetFIdx) {
  if (!fieldDrag.field) return
  const srcSIdx = fieldDrag.sourceSIdx
  const srcFIdx = fieldDrag.sourceFIdx

  // Compute actual insert index from the indicator position
  const rect = e.currentTarget.getBoundingClientRect()
  const midY = rect.top + rect.height / 2
  let insertIdx = e.clientY < midY ? targetFIdx : targetFIdx + 1

  // Dropping on self — nothing to do
  if (srcSIdx === targetSIdx && (srcFIdx === insertIdx || srcFIdx === insertIdx - 1)) {
    onFieldDragEnd()
    return
  }

  // Remove from source
  const [moved] = sections.value[srcSIdx].fields.splice(srcFIdx, 1)

  // Adjust target index if same section and source was before target
  if (srcSIdx === targetSIdx && srcFIdx < insertIdx) {
    insertIdx--
  }

  // Insert at target position
  sections.value[targetSIdx].fields.splice(insertIdx, 0, moved)

  syncToParent()
  onFieldDragEnd()
}

// Section-level drag/drop for field drops (when dropping on empty section area)
function onDragOverSection(e, sIdx) {
  if (fieldDrag.field) {
    fieldDrag.overSection = sIdx
    // When dragging over the section body (not a specific field), show indicator at end
    if (fieldDrag.overIdx === null || fieldDrag.overSection !== sIdx) {
      fieldDrag.overIdx = sections.value[sIdx].fields.length
    }
  }
}

function onDragLeaveSection(e) {
  // Only clear if we actually left the section (not entering a child)
  if (fieldDrag.field && !e.currentTarget.contains(e.relatedTarget)) {
    fieldDrag.overSection = null
    fieldDrag.overIdx = null
  }
}

function onDropSection(e, targetSIdx) {
  // Handle field drop on section (not on a specific field card)
  if (fieldDrag.field) {
    const srcSIdx = fieldDrag.sourceSIdx
    const srcFIdx = fieldDrag.sourceFIdx

    // Remove from source
    const [moved] = sections.value[srcSIdx].fields.splice(srcFIdx, 1)

    // Determine insert position based on drop Y coordinate relative to field cards
    const targetFields = sections.value[targetSIdx].fields
    let insertIdx = targetFields.length
    const sectionEl = e.currentTarget.querySelector('.grid')
    if (sectionEl) {
      const fieldCards = sectionEl.querySelectorAll('[draggable="true"]')
      for (let i = 0; i < fieldCards.length; i++) {
        const rect = fieldCards[i].getBoundingClientRect()
        if (e.clientY < rect.top + rect.height / 2) {
          insertIdx = i
          break
        }
      }
    }

    sections.value[targetSIdx].fields.splice(insertIdx, 0, moved)
    syncToParent()
    onFieldDragEnd()
    return
  }

  // Handle section drop
  if (sectionDrag.source !== null) {
    onSectionDrop(e, targetSIdx)
  }
}

// ── Section drag and drop ────────────────────────────────────────────────────

const sectionDrag = reactive({
  source: null,
  overIdx: null,
})

function onSectionDragStart(e, sIdx) {
  sectionDrag.source = sIdx
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', 'section')
}

function onSectionDragEnd() {
  sectionDrag.source = null
  sectionDrag.overIdx = null
}

function onSectionHeaderDragOver(e, sIdx) {
  if (sectionDrag.source === null) return
  sectionDrag.overIdx = sIdx
}

function onSectionDrop(e, targetSIdx) {
  if (sectionDrag.source === null || sectionDrag.source === targetSIdx) {
    onSectionDragEnd()
    return
  }
  const [moved] = sections.value.splice(sectionDrag.source, 1)
  const insertAt = sectionDrag.source < targetSIdx ? targetSIdx - 1 : targetSIdx
  sections.value.splice(insertAt, 0, moved)
  syncToParent()
  onSectionDragEnd()
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
