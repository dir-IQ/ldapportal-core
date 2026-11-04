<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="space-y-2" ref="rootEl">
    <!-- Toolbar -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-2">
        <button type="button" @click="addSection" class="btn-secondary text-xs">+ Add Section</button>
        <span class="text-xs text-gray-500">Drag fields to reorder. Choose width: 1/3, 1/2, 2/3, or full (password and DN lookup: 1/2 or full).</span>
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

    <!-- DN template: optional ${attr} expression that seeds the (editable)
         DN field on the admin create form. Blank falls back to the default
         "<rdn>=<value>,<targetUserDn>" composition. -->
    <div v-if="!hideDnToggle && localShowDnField" class="rounded-lg border border-gray-200 bg-gray-50 p-3 space-y-1">
      <label for="fld-dn-template" class="block text-xs font-medium text-gray-600">DN template (optional)</label>
      <input
        id="fld-dn-template"
        v-model="localDnTemplate"
        type="text"
        class="input w-full font-mono text-xs"
        placeholder="uid=${uid},ou=people,dc=example,dc=com"
      />
      <p class="text-[11px] text-gray-500">
        <code>${'{'}attr{'}'}</code> tokens resolve to the new user's values (reserved characters such as
        <code>+</code> and <code>,</code> are escaped automatically). Leave blank to use
        <code>&lt;rdn&gt;=&lt;value&gt;,&lt;target OU&gt;</code>. The DN stays editable and must remain within the profile's target OU.
        A literal <code>+</code> between tokens in the first component — e.g.
        <code>o=${'{'}o{'}'}+cn=${'{'}cn{'}'},ou=People,…</code> — creates entries with a
        multi-valued RDN; each referenced attribute then names the entry and becomes required.
      </p>
    </div>

    <!-- Live Preview -->
    <div v-if="showPreview" class="border border-blue-200 bg-blue-50/30 rounded-xl p-4">
      <h4 class="text-sm font-semibold text-blue-800 mb-3">Form Preview</h4>
      <div class="bg-white rounded-lg border border-gray-200 p-4 space-y-2">
        <template v-for="section in previewSections" :key="section.id">
          <fieldset v-if="section.fields.length" class="space-y-2">
            <legend v-if="section.name" class="text-sm font-semibold text-gray-800 pb-1 border-b border-gray-100 w-full mb-2">{{ section.name }}</legend>
            <div class="grid grid-cols-6 gap-2">
              <template v-for="field in section.fields" :key="field.attributeName">
                <!-- RDN field -->
                <div v-if="field.rdn" :style="{ gridColumn: `span ${field.columnSpan || 6}` }">
                  <label class="block text-sm font-medium text-gray-700 mb-1">
                    {{ field.customLabel || field.attributeName }}
                    <span class="text-red-500">*</span>
                    <span class="text-xs bg-amber-100 text-amber-700 rounded px-1 ml-1">RDN</span>
                  </label>
                  <div class="w-full h-9 border border-gray-200 rounded-lg bg-gray-50"></div>
                </div>
                <!-- DN field (placed/sized per its layout) -->
                <div v-else-if="field.isDn" :style="{ gridColumn: `span ${field.columnSpan || 4}` }">
                  <label class="block text-sm font-medium text-gray-700 mb-1">
                    DN
                    <span class="text-xs bg-blue-100 text-blue-700 rounded px-1 ml-1">editable</span>
                  </label>
                  <div class="w-full h-9 border border-gray-200 rounded-lg bg-white flex items-center px-3 text-xs text-gray-600 italic font-mono truncate">
                    {{ localDnTemplate || `${rdnFieldName}=…,ou=…,dc=…` }}
                  </div>
                </div>
                <!-- Regular field -->
                <div
                  v-else-if="!field.rdn"
                  :style="{ gridColumn: `span ${fieldSpan(field)}` }"
                >
                  <label class="block text-sm font-medium text-gray-700 mb-1">
                    {{ field.customLabel || field.attributeName }}
                    <span v-if="field.requiredOnCreate" class="text-red-500">*</span>
                    <!-- Naming badge: the attribute is referenced by the DN
                         template's leading RDN (multi-valued RDNs mark several
                         fields beyond the designated one). -->
                    <span v-if="field.naming" class="text-xs bg-amber-100 text-amber-700 rounded px-1 ml-1">RDN</span>
                  </label>
                  <div v-if="field.inputType === 'TEXTAREA' || field.inputType === 'MULTI_VALUE'" class="w-full h-16 border border-gray-200 rounded-lg bg-gray-50"></div>
                  <div v-else-if="field.inputType === 'BOOLEAN'" class="flex items-center gap-2">
                    <div class="w-4 h-4 border border-gray-300 rounded bg-white"></div>
                    <span class="text-sm text-gray-500">{{ field.customLabel || field.attributeName }}</span>
                  </div>
                  <!-- Dropdown mock so SELECT reads as a picker, not a text box -->
                  <div v-else-if="field.inputType === 'SELECT'" class="w-full h-9 border border-gray-200 rounded-lg bg-gray-50 flex items-center justify-between px-3">
                    <span class="text-xs text-gray-400 italic">Select…</span>
                    <svg class="w-4 h-4 text-gray-400 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
                    </svg>
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
                :style="{ gridColumn: `span ${field.columnSpan || 6}` }"
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
                    <span class="text-[13px] font-mono text-gray-500" v-if="field.customLabel">{{ field.attributeName }}</span>
                    <span class="text-[10px] bg-amber-100 text-amber-700 rounded px-1 font-medium">RDN</span>
                    <span class="text-red-400 text-xs">*</span>
                  </div>
                  <div class="text-[10px] text-gray-500">{{ field.inputType }} · {{ spanLabel(field.columnSpan) }}</div>
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
              <!-- DN field card — computed/read-only, but draggable + resizable -->
              <div
                v-else-if="field.isDn"
                :style="{ gridColumn: `span ${field.columnSpan || 4}` }"
                :class="[
                  fieldDrag.field?.attributeName === field.attributeName ? 'opacity-30' : '',
                ]"
                class="group relative flex items-center gap-2 px-3 py-2 bg-gray-50 border border-dashed border-gray-300 rounded-lg hover:border-blue-300 cursor-grab transition-colors"
                draggable="true"
                @dragstart="onFieldDragStart($event, sIdx, fIdx, field)"
                @dragend="onFieldDragEnd"
                @dragover.prevent.stop="onFieldDragOver($event, sIdx, fIdx)"
                @drop.prevent.stop="onFieldDrop($event, sIdx, fIdx)"
              >
                <svg class="w-3.5 h-3.5 text-gray-300 shrink-0" viewBox="0 0 20 20" fill="currentColor">
                  <path d="M7 2a2 2 0 10.001 4.001A2 2 0 007 2zm0 6a2 2 0 10.001 4.001A2 2 0 007 8zm0 6a2 2 0 10.001 4.001A2 2 0 007 14zm6-8a2 2 0 10-.001-4.001A2 2 0 0013 6zm0 2a2 2 0 10.001 4.001A2 2 0 0013 8zm0 6a2 2 0 10.001 4.001A2 2 0 0013 14z"/>
                </svg>
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-1.5">
                    <span class="text-sm font-medium text-gray-500">DN</span>
                    <span class="text-[10px] bg-gray-100 text-gray-600 rounded px-1 font-medium">computed</span>
                  </div>
                  <div class="text-[10px] text-gray-500">Auto-generated from the DN template / RDN · {{ spanLabel(field.columnSpan || 4) }}</div>
                </div>
                <div class="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    v-for="opt in spanOptions"
                    :key="opt.span"
                    type="button"
                    @click="setColumnSpan(sIdx, fIdx, opt.span)"
                    :class="[
                      'px-1.5 h-5 rounded text-[10px] font-bold border transition-colors whitespace-nowrap',
                      (field.columnSpan || 4) === opt.span
                        ? 'bg-blue-600 text-white border-blue-600'
                        : 'bg-white text-gray-500 border-gray-300 hover:border-blue-400'
                    ]"
                    :title="opt.label"
                  >{{ opt.label }}</button>
                </div>
              </div>

              <!-- Regular field card -->
              <div
                v-else-if="!field.rdn"
                :style="{ gridColumn: `span ${fieldSpan(field)}` }"
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
                    <span class="text-[13px] font-mono text-gray-500" v-if="field.customLabel">{{ field.attributeName }}</span>
                    <span v-if="field.naming" class="text-[10px] bg-amber-100 text-amber-700 rounded px-1 font-medium">RDN</span>
                    <span v-if="field.requiredOnCreate" class="text-red-400 text-xs">*</span>
                  </div>
                  <div class="text-[10px] text-gray-500">{{ field.inputType }}</div>
                </div>

                <!-- Column span selector (only the widths this input type allows) -->
                <div class="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    v-for="opt in spanOptionsFor(field.inputType)"
                    :key="opt.span"
                    type="button"
                    @click="setColumnSpan(sIdx, fIdx, opt.span)"
                    :class="[
                      'px-1.5 h-5 rounded text-[10px] font-bold border transition-colors whitespace-nowrap',
                      fieldSpan(field) === opt.span
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

<script setup lang="ts">
import { ref, reactive, computed, watch, type PropType } from 'vue'
import { useDragAutoScroll } from '@/composables/useDragAutoScroll'
import { SPAN_OPTIONS, spanOptionsFor, effectiveSpan } from '@/utils/fieldWidth'

/** A field as the designer lays it out (an attribute config, or the DN pseudo-field). */
interface LayoutField {
  attributeName: string
  customLabel?: string | null
  inputType?: string | null
  requiredOnCreate?: boolean
  sectionName?: string | null
  columnSpan?: number | null
  hidden?: boolean
  rdn?: boolean
  /** Referenced by the DN template's leading RDN (display-only badge). */
  naming?: boolean
  /** Marks the synthetic DN field. */
  isDn?: boolean
}

interface Section {
  id: string
  name: string
  fields: LayoutField[]
}

interface HiddenPosition {
  sectionName: string
  afterAttribute: string | null
}

// Root element + smooth edge auto-scroll for native drag (the browser's own
// autoscroll inside the scrollable modal body is jerky/absent).
const rootEl = ref<HTMLElement | null>(null)
const { start: startAutoScroll, stop: stopAutoScroll } = useDragAutoScroll()

const props = defineProps({
  attributeConfigs: { type: Array as PropType<LayoutField[]>, required: true },
  showDnField: { type: Boolean, default: true },
  hideDnToggle: { type: Boolean, default: false },
  dnTemplate: { type: String, default: '' },
  // DN field layout — unset (undefined) reproduces the default: after the RDN,
  // 2/3 width.
  dnColumnSpan: { type: Number, default: undefined },
  dnSectionName: { type: String, default: undefined },
  dnDisplayOrder: { type: Number, default: undefined },
})
const emit = defineEmits([
  'update:attributeConfigs', 'update:showDnField', 'update:dnTemplate',
  'update:dnColumnSpan', 'update:dnSectionName', 'update:dnDisplayOrder',
])

const showPreview = ref(false)
const localShowDnField = ref(props.showDnField)
const localDnTemplate = ref(props.dnTemplate || '')
watch(() => props.dnTemplate, (v) => { localDnTemplate.value = v || '' })
watch(localDnTemplate, (v) => { emit('update:dnTemplate', v) })

// Column span options: 6-column grid allows 1/3, 1/2, 2/3, and full widths.
// Regular fields are limited per input type (spanOptionsFor) and render at
// their effective width — the same rule the rendered forms apply.
const spanOptions = SPAN_OPTIONS
const spanLabels: Record<number, string> = Object.fromEntries(spanOptions.map(o => [o.span, o.label]))
function spanLabel(span?: number | null): string { return (span && spanLabels[span]) || 'Full' }
function fieldSpan(field: LayoutField): number { return effectiveSpan(field.inputType, field.columnSpan, 6) }

// ── DN pseudo-field ─────────────────────────────────────────────────────────
// The read-only DN is modelled as a synthetic layout field so it can be dragged
// and resized with the same machinery as real attributes. It is never emitted
// as an attribute config — its layout is reported back through the dn* model
// values (see emitDnLayout).
const DN_KEY = '__dn__'
const DEFAULT_DN_SPAN = 4

function makeDnField(): LayoutField {
  return { attributeName: DN_KEY, isDn: true, rdn: false, columnSpan: props.dnColumnSpan || DEFAULT_DN_SPAN }
}

function hasDnField(list: Section[]): boolean {
  return list.some(s => s.fields.some(f => f.isDn))
}

function removeDnField(list: Section[]): void {
  for (const s of list) s.fields = s.fields.filter(f => !f.isDn)
}

// Insert the DN pseudo-field at its configured position, defaulting to
// immediately after the RDN in the RDN's section.
function insertDnField(list: Section[]): void {
  if (!localShowDnField.value || hasDnField(list)) return
  let section: Section | null | undefined = (props.dnSectionName != null)
    ? list.find(s => (s.name || '') === props.dnSectionName)
    : null
  if (!section) section = list.find(s => s.fields.some(f => f.rdn)) || list[0]
  if (!section) return
  let idx: number
  if (props.dnDisplayOrder != null && props.dnDisplayOrder >= 0) {
    idx = Math.min(props.dnDisplayOrder, section.fields.length)
  } else {
    const rdnIdx = section.fields.findIndex(f => f.rdn)
    idx = rdnIdx >= 0 ? rdnIdx + 1 : section.fields.length
  }
  section.fields.splice(idx, 0, makeDnField())
}

// The RDN attribute name, for the DN preview placeholder.
const rdnFieldName = computed(() => {
  for (const s of sections.value) {
    const r = s.fields.find(f => f.rdn)
    if (r) return r.attributeName
  }
  return 'uid'
})

watch(() => props.showDnField, (v) => { localShowDnField.value = v })
watch(localShowDnField, (v) => {
  emit('update:showDnField', v)
  if (v) {
    // When DN display is toggled on, add the draggable DN pseudo-field. The RDN
    // keeps its own configured width — the DN is independently sized.
    insertDnField(sections.value)
  } else {
    removeDnField(sections.value)
  }
  syncToParent()
})

let sectionIdCounter = 0
function nextSectionId() { return `section-${++sectionIdCounter}` }

// ── Build sections from flat attribute list ──────────────────────────────────

const sections = ref<Section[]>([])

// Tracks last-known position of hidden fields so un-hiding restores them
// Map<attributeName, { sectionName, afterAttribute }>
const hiddenPositions = ref(new Map<string, HiddenPosition>())

function buildSections(attrs: LayoutField[]): Section[] {
  const map = new Map<string, Section>()
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
    map.get(key)!.fields.push(field)
  }
  const result = Array.from(map.values())
  if (result.length === 0) {
    result.push({ id: nextSectionId(), name: '', fields: [] })
  }
  // The RDN keeps whatever position the saved layout gives it — the order in
  // attributeConfigs is authoritative, so a repositioned RDN survives a reload.
  // Build initial hidden position map
  recordHiddenPositions(attrs)
  // Add the draggable/resizable DN pseudo-field at its configured position.
  insertDnField(result)
  return result
}

function recordHiddenPositions(attrs: LayoutField[]): void {
  let lastVisible: string | null = null
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
  // Exclude the synthetic DN field from the diff against real attribute configs.
  const currentNames = new Set(currentFields.filter(f => !f.isDn).map(f => f.attributeName))
  const visibleConfigs = newConfigs.filter(a => !a.hidden)
  const incomingNames = new Set(visibleConfigs.map(a => a.attributeName))

  // Detect RDN change (even when attribute set hasn't changed)
  const incomingRdn = visibleConfigs.find(a => a.rdn)?.attributeName || null
  const currentRdn = currentFields.find(f => f.rdn)?.attributeName || null
  const rdnChanged = incomingRdn !== currentRdn

  // The rdn/naming badge flags can change without the attribute set changing —
  // e.g. switching DN mode or editing the template moves which attributes name
  // the entry. Detect that so the "nothing changed" skip below doesn't drop it.
  const incomingByName = new Map(visibleConfigs.map(a => [a.attributeName, a]))
  const namingChanged = currentFields.some(f => {
    const inc = f.isDn ? undefined : incomingByName.get(f.attributeName)
    return !!inc && (!!inc.rdn !== !!f.rdn || !!inc.naming !== !!f.naming)
  })

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
    !namingChanged &&
    newlyVisible.length === 0 &&
    newlyHidden.length === 0 &&
    currentNames.size === incomingNames.size &&
    [...currentNames].every(n => incomingNames.has(n))
  ) return

  // Remove fields that were deleted or hidden on the Attributes tab (keep the
  // synthetic DN field, which isn't a real attribute).
  for (const section of sections.value) {
    section.fields = section.fields.filter(f => f.isDn || incomingNames.has(f.attributeName))
  }

  // Add newly-added visible fields
  for (const attr of visibleConfigs) {
    if (!currentNames.has(attr.attributeName)) {
      const field = { ...attr }

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

  // Refresh the rdn/naming badge flags on every field from the incoming configs,
  // so the layout tracks the current naming source (RDN attribute or DN template)
  // even when the attribute set is unchanged. These flags are display-only (the
  // parent recomputes them), so this needs no syncToParent.
  for (const section of sections.value) {
    for (const field of section.fields) {
      if (field.isDn) continue
      const inc = incomingByName.get(field.attributeName)
      if (inc) {
        field.rdn = !!inc.rdn
        field.naming = !!inc.naming
      }
    }
  }

  // Move the new RDN field to index 0 of the first section when it changed.
  if (rdnChanged) {
    for (let s = 0; s < sections.value.length; s++) {
      const rdnIdx = sections.value[s].fields.findIndex(f => f.rdn)
      if (rdnIdx >= 0) {
        if (s === 0 && rdnIdx === 0) break // already in place
        const [rdnF] = sections.value[s].fields.splice(rdnIdx, 1)
        sections.value[0].fields.unshift(rdnF)
        break
      }
    }
    syncToParent()
  }
}, { deep: true })

function flattenSections(): LayoutField[] {
  const result: LayoutField[] = []
  for (const section of sections.value) {
    for (const field of section.fields) {
      if (field.isDn) continue // DN isn't an attribute — emitted via emitDnLayout
      result.push({
        ...field,
        sectionName: section.name || '',
      })
    }
  }
  return result
}

// Report the DN field's current section / position / width back to the parent.
function emitDnLayout() {
  let span: number | undefined
  let sectionName: string | undefined
  let order: number | undefined
  for (const section of sections.value) {
    const idx = section.fields.findIndex(f => f.isDn)
    if (idx >= 0) {
      span = section.fields[idx].columnSpan || DEFAULT_DN_SPAN
      sectionName = section.name || ''
      order = section.fields.slice(0, idx).filter(f => !f.isDn).length
      break
    }
  }
  emit('update:dnColumnSpan', span)
  emit('update:dnSectionName', sectionName)
  emit('update:dnDisplayOrder', order)
}

function syncToParent() {
  syncing = true
  emit('update:attributeConfigs', flattenSections())
  emitDnLayout()
  // Allow the next tick to propagate before re-enabling the watch
  setTimeout(() => { syncing = false }, 0)
}

// ── Section management ───────────────────────────────────────────────────────

function addSection() {
  sections.value.push({ id: nextSectionId(), name: '', fields: [] })
}

function removeSection(idx: number) {
  const removed = sections.value.splice(idx, 1)[0]
  // Move orphaned fields to the first section
  if (removed.fields.length && sections.value.length) {
    sections.value[0].fields.push(...removed.fields)
  }
  syncToParent()
}

function moveSectionUp(idx: number) {
  if (idx <= 0) return
  const arr = sections.value
  ;[arr[idx - 1], arr[idx]] = [arr[idx], arr[idx - 1]]
  syncToParent()
}

function moveSectionDown(idx: number) {
  if (idx >= sections.value.length - 1) return
  const arr = sections.value
  ;[arr[idx], arr[idx + 1]] = [arr[idx + 1], arr[idx]]
  syncToParent()
}

// ── Column span ──────────────────────────────────────────────────────────────

function setColumnSpan(sIdx: number, fIdx: number, span: number) {
  sections.value[sIdx].fields[fIdx].columnSpan = span
  syncToParent()
}

// ── Field drag and drop (with drop position indicator) ───────────────────────

const fieldDrag = reactive<{
  field: LayoutField | null
  sourceSIdx: number | null
  sourceFIdx: number | null
  overSection: number | null
  overIdx: number | null
}>({
  field: null,
  sourceSIdx: null,
  sourceFIdx: null,
  overSection: null,
  overIdx: null,
})

function onFieldDragStart(e: DragEvent, sIdx: number, fIdx: number, field: LayoutField) {
  fieldDrag.field = field
  fieldDrag.sourceSIdx = sIdx
  fieldDrag.sourceFIdx = fIdx
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', 'field')
  }
  startAutoScroll(rootEl.value)
}

function onFieldDragEnd() {
  fieldDrag.field = null
  fieldDrag.sourceSIdx = null
  fieldDrag.sourceFIdx = null
  fieldDrag.overSection = null
  fieldDrag.overIdx = null
  stopAutoScroll()
}

function onFieldDragOver(e: DragEvent, sIdx: number, fIdx: number) {
  if (!fieldDrag.field) return
  fieldDrag.overSection = sIdx

  // Determine if cursor is in the top or bottom half of the field card
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const midY = rect.top + rect.height / 2
  fieldDrag.overIdx = e.clientY < midY ? fIdx : fIdx + 1
}

function onFieldDrop(e: DragEvent, targetSIdx: number, targetFIdx: number) {
  if (!fieldDrag.field || fieldDrag.sourceSIdx === null || fieldDrag.sourceFIdx === null) return
  const srcSIdx = fieldDrag.sourceSIdx
  const srcFIdx = fieldDrag.sourceFIdx

  // Compute actual insert index from the indicator position
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
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
function onDragOverSection(_e: DragEvent, sIdx: number) {
  if (fieldDrag.field) {
    fieldDrag.overSection = sIdx
    // When dragging over the section body (not a specific field), show indicator at end
    if (fieldDrag.overIdx === null || fieldDrag.overSection !== sIdx) {
      fieldDrag.overIdx = sections.value[sIdx].fields.length
    }
  }
}

function onDragLeaveSection(e: DragEvent) {
  // Only clear if we actually left the section (not entering a child)
  if (fieldDrag.field && !(e.currentTarget as HTMLElement).contains(e.relatedTarget as Node | null)) {
    fieldDrag.overSection = null
    fieldDrag.overIdx = null
  }
}

function onDropSection(e: DragEvent, targetSIdx: number) {
  // Handle field drop on section (not on a specific field card)
  if (fieldDrag.field && fieldDrag.sourceSIdx !== null && fieldDrag.sourceFIdx !== null) {
    const srcSIdx = fieldDrag.sourceSIdx
    const srcFIdx = fieldDrag.sourceFIdx

    // Remove from source
    const [moved] = sections.value[srcSIdx].fields.splice(srcFIdx, 1)

    // Determine insert position based on drop Y coordinate relative to field cards
    const targetFields = sections.value[targetSIdx].fields
    let insertIdx = targetFields.length
    const sectionEl = (e.currentTarget as HTMLElement).querySelector('.grid')
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

const sectionDrag = reactive<{ source: number | null, overIdx: number | null }>({
  source: null,
  overIdx: null,
})

function onSectionDragStart(e: DragEvent, sIdx: number) {
  sectionDrag.source = sIdx
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', 'section')
  }
  startAutoScroll(rootEl.value)
}

function onSectionDragEnd() {
  sectionDrag.source = null
  sectionDrag.overIdx = null
  stopAutoScroll()
}

function onSectionHeaderDragOver(_e: DragEvent, sIdx: number) {
  if (sectionDrag.source === null) return
  sectionDrag.overIdx = sIdx
}

function onSectionDrop(_e: DragEvent, targetSIdx: number) {
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
