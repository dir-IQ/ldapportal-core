<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
/**
 * Visual LDAP filter builder. Renders a list of attribute/operator/value
 * rule cards plus an AND/OR conjunction toggle. Recompiles a filter
 * string on every change and emits via v-model so consumers can two-way-
 * bind to a plain string ref.
 *
 * The component is one-way (rules → string only). Editing the string
 * directly does NOT update the rules — that's the v2 parser feature.
 * Users can always type into the raw filter input alongside the builder;
 * builder rules and direct edits don't conflict because the builder only
 * emits when its own state changes.
 *
 * Schema autocomplete: when `directoryId` is provided, the component
 * fetches attribute types via `/api/v1/directories/{id}/schema/attribute-
 * types` and offers them as suggestions in a custom combobox under the
 * attr input. If the call fails (no schema exposed, RBAC, network), we
 * silently fall back to free-text input — never blocks the user.
 *
 * The combobox is custom Tailwind-styled rather than a native
 * `<datalist>`. Datalist popovers are OS-rendered chrome and don't
 * inherit page styling, which produced a jarring dark-on-light popup
 * on systems running OS-level dark mode.
 */
import { ref, computed, watch, nextTick, useId } from 'vue'
import {
  compileRules,
  operatorHasValue,
  OPERATORS,
  type Conjunction,
  type FilterOp,
  type FilterRule,
} from '@/composables/useLdapFilter'
import { listAttributeTypes } from '@/api/schema'

interface AttributeTypeInfo {
  name?: string
  oid?: string
  syntaxOid?: string
  singleValued?: boolean
}

const props = defineProps<{
  modelValue: string
  directoryId: string
  /**
   * Attribute names the parent considers "relevant" — typically the
   * provisioning profile's attributeConfigs. When non-empty, the
   * suggestion list is restricted to these by default; a "Show all
   * attributes" toggle in the panel header escapes to the full
   * directory-schema list. If empty or omitted, the builder behaves
   * as before (full schema only).
   */
  relevantAttributes?: string[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()

// Rules state. `expanded` tracks the disclosure; collapsed by default so
// the builder doesn't dominate the search panel for users who don't need
// it. We start with no rules — clicking "Build filter visually" opens
// the disclosure but the user has to click "+ Add rule" to begin.
//
// Each rule carries a stable `id` so the v-for in the template can use
// it as a Vue `:key`. Index-based keys broke focus/diffing when rules
// were removed: Vue reused DOM nodes and the template-ref array kept
// stale entries pointing at the old elements. compileRule/compileRules
// ignore the extra field — they read attr/op/value only.
interface IndexedRule extends FilterRule {
  id: number
}
let nextRuleId = 0

const expanded = ref(false)
const conjunction = ref<Conjunction>('AND')
const rules = ref<IndexedRule[]>([])

// Schema attributes per directory, cached in module scope (across mount/
// unmount) so toggling the disclosure doesn't refetch. Keyed by
// directoryId so switching directories pulls a fresh list.
const attributeNames = ref<string[]>([])
const schemaState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

// When `relevantAttributes` is provided, default to suggesting only that
// subset (typically the profile's attributeConfigs). The toggle below
// flips this for power users who need to filter on something the
// profile doesn't surface (objectClass, memberOf, operational attrs).
const showAllAttributes = ref(false)
const hasRelevantList = computed(
  () => Array.isArray(props.relevantAttributes) && props.relevantAttributes.length > 0,
)
const isScopedToProfile = computed(() => hasRelevantList.value && !showAllAttributes.value)

// Module-level cache so unmounting the component doesn't drop fetched
// schemas. The map lives for the page lifetime (acceptable tradeoff —
// memory is small and a hard reload clears it).
const schemaCache = new Map<string, string[]>()

async function loadSchemaIfNeeded(): Promise<void> {
  const dirId = props.directoryId
  if (!dirId) { attributeNames.value = []; schemaState.value = 'idle'; return }
  const cached = schemaCache.get(dirId)
  if (cached) { attributeNames.value = cached; schemaState.value = 'loaded'; return }
  schemaState.value = 'loading'
  try {
    const { data } = await listAttributeTypes(dirId)
    const names = (Array.isArray(data) ? data : [])
      .map((a: AttributeTypeInfo) => a.name)
      .filter((n): n is string => !!n)
      .sort()
    schemaCache.set(dirId, names)
    attributeNames.value = names
    schemaState.value = 'loaded'
  } catch {
    // Silent fallback: free-text attribute input. Don't push a notification
    // — schema unavailability isn't an actionable error and would scare
    // users when it's actually fine.
    attributeNames.value = []
    schemaState.value = 'error'
  }
}

watch(() => props.directoryId, () => {
  if (expanded.value) loadSchemaIfNeeded()
})

watch(expanded, (open) => {
  if (open && schemaState.value === 'idle') loadSchemaIfNeeded()
})

// Compile and emit on any rule/conjunction change. Default flush ('pre')
// is correct here: it fires synchronously with the reactive update, so a
// parent v-model'd to `form.filter` sees the new value before the DOM is
// painted. The `compiled !== modelValue` guard suppresses spurious emits
// when the parent's string already matches what we'd emit (avoids feedback
// loops with two-way binding).
watch(
  [rules, conjunction],
  () => {
    const compiled = compileRules(rules.value, conjunction.value)
    if (compiled !== props.modelValue) {
      emit('update:modelValue', compiled)
    }
  },
  { deep: true },
)

const compiledPreview = computed(() => compileRules(rules.value, conjunction.value))

// useId() guarantees uniqueness even if two LdapFilterBuilders are ever
// mounted (e.g. a future "compare two filters" view).
const builderUid = useId()
const panelId = `ldap-builder-panel-${builderUid}`
const comboListboxId = (ruleId: number): string => `${builderUid}-combo-list-${ruleId}`
const comboOptionId = (ruleId: number, idx: number): string => `${builderUid}-combo-opt-${ruleId}-${idx}`

// Template refs keyed by stable rule id (not by index). The setter
// callback fires with `el === null` when a row unmounts, letting us
// delete the entry; without that path, removing a rule would leave a
// stale element reference behind. Plain Map (not a reactive ref)
// because no template depends on observing it.
const ruleAttrRefs = new Map<number, HTMLInputElement>()
function setRuleAttrRef(id: number, el: Element | null): void {
  if (el instanceof HTMLInputElement) ruleAttrRefs.set(id, el)
  else ruleAttrRefs.delete(id)
}

// ── Combobox state ────────────────────────────────────────────────────
// Single-source-of-truth for which rule's combobox is currently open
// and which suggestion is keyboard-highlighted within it. Only one
// combobox can be open at a time (matches focus-tied behaviour: opening
// row B's combobox blurs row A's input, which closes A's combobox).
const SUGGESTION_RENDER_CAP = 50
const activeComboRuleId = ref<number | null>(null)
const comboHighlightIdx = ref(-1)

const isComboOpen = (id: number): boolean => activeComboRuleId.value === id

// Suggestion source: the profile-relevant subset when scoped, otherwise
// the full directory schema. Profile-relevant names are normalised to
// the schema's casing where possible so the dropdown shows e.g.
// "givenName" instead of whatever case the profile happened to declare.
const suggestionSource = computed<string[]>(() => {
  if (isScopedToProfile.value) {
    const schemaLc = new Map(attributeNames.value.map(n => [n.toLowerCase(), n]))
    const seen = new Set<string>()
    const out: string[] = []
    for (const raw of (props.relevantAttributes ?? [])) {
      const name = schemaLc.get(raw.toLowerCase()) ?? raw
      const key = name.toLowerCase()
      if (seen.has(key)) continue
      seen.add(key)
      out.push(name)
    }
    return out.sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }))
  }
  return attributeNames.value
})

function filteredSuggestions(rule: IndexedRule): string[] {
  const source = suggestionSource.value
  if (!source.length) return []
  const q = (rule.attr ?? '').trim().toLowerCase()
  const matched = q ? source.filter(n => n.toLowerCase().includes(q)) : source
  // Render cap keeps the listbox cheap when the user hasn't typed
  // anything yet (Active Directory schemas can return 1500+ attrs).
  // Scroll handles overflow within the cap; users typically narrow with
  // a few keystrokes well before reaching the bottom.
  return matched.slice(0, SUGGESTION_RENDER_CAP)
}

function openCombo(id: number): void {
  activeComboRuleId.value = id
  comboHighlightIdx.value = -1
}

function closeCombo(): void {
  activeComboRuleId.value = null
  comboHighlightIdx.value = -1
}

function onComboInput(rule: IndexedRule): void {
  // Reset highlight when query changes — index would point into a
  // stale filtered list otherwise.
  comboHighlightIdx.value = -1
  if (!isComboOpen(rule.id)) openCombo(rule.id)
}

function onComboNav(rule: IndexedRule, dir: 'up' | 'down'): void {
  if (!isComboOpen(rule.id)) openCombo(rule.id)
  const list = filteredSuggestions(rule)
  if (!list.length) return
  if (dir === 'down') {
    comboHighlightIdx.value = (comboHighlightIdx.value + 1) % list.length
  } else {
    comboHighlightIdx.value =
      comboHighlightIdx.value <= 0 ? list.length - 1 : comboHighlightIdx.value - 1
  }
}

function onComboEnter(rule: IndexedRule): void {
  const list = filteredSuggestions(rule)
  if (isComboOpen(rule.id) && comboHighlightIdx.value >= 0 && list[comboHighlightIdx.value]) {
    selectSuggestion(rule, list[comboHighlightIdx.value])
  } else {
    // No highlight — leave the user's typed value as-is and just close.
    closeCombo()
  }
}

function selectSuggestion(rule: IndexedRule, name: string): void {
  rule.attr = name
  closeCombo()
}

function addRule(): void {
  const id = nextRuleId++
  rules.value.push({ id, attr: '', op: 'equals', value: '' })
  // Focus the new attr input by the stable id, not by array index —
  // this is correct even after a previous remove-then-add sequence
  // because the Map lookup ignores stale rows.
  nextTick(() => {
    ruleAttrRefs.get(id)?.focus()
  })
}

function removeRule(idx: number): void {
  rules.value.splice(idx, 1)
}

function setOp(idx: number, op: FilterOp): void {
  rules.value[idx].op = op
  // If the new op doesn't take a value, clear the existing one so the
  // compiled filter doesn't keep stale data after a Present switch (and
  // doesn't surprise the user by reappearing if they switch back).
  if (!operatorHasValue(op)) rules.value[idx].value = ''
}

function clearAll(): void {
  rules.value = []
  conjunction.value = 'AND'
}

/**
 * Test-only helper: reset the module-scoped schema cache so component
 * tests don't have to depend on import order. Production code never
 * calls this. Exposed via defineExpose below for the spec file.
 */
function __resetSchemaCacheForTesting(): void {
  schemaCache.clear()
}

defineExpose({
  // For tests — direct access to internals.
  rules,
  conjunction,
  expanded,
  __resetSchemaCacheForTesting,
  // Exposed so tests can verify the ref Map stays in sync with the
  // rendered DOM under add/remove sequences (happy-dom's focus() is
  // not reliable enough to assert via document.activeElement).
  ruleAttrRefs,
  // Combobox internals so keyboard-nav tests can assert highlight
  // state without depending on aria-activedescendant string parsing.
  activeComboRuleId,
  comboHighlightIdx,
  // Profile-scope toggle state so tests can assert the suggestion
  // source flips without parsing the DOM.
  showAllAttributes,
  isScopedToProfile,
  suggestionSource,
})
</script>

<template>
  <div>
    <!-- Disclosure trigger. Mirrors the "▸ Advanced" pattern used
         elsewhere on the page so users recognise it. aria-controls links
         the trigger to the panel id for screen readers. -->
    <button
      type="button"
      @click="expanded = !expanded"
      class="text-xs text-gray-500 hover:text-gray-700 cursor-pointer select-none flex items-center gap-1"
      :aria-expanded="expanded"
      :aria-controls="panelId"
    >
      <span class="inline-block transition-transform" :class="expanded ? 'rotate-90' : ''">▸</span>
      Build filter visually
      <span v-if="rules.length > 0" class="text-blue-600 ml-1 normal-case">({{ rules.length }} rule{{ rules.length === 1 ? '' : 's' }})</span>
    </button>

    <div v-if="expanded" :id="panelId" class="mt-2 border border-gray-200 rounded-lg p-3 bg-gray-50/50">
      <!-- Conjunction toggle -->
      <div class="flex items-center gap-3 mb-2">
        <span class="text-xs font-medium text-gray-600">Match</span>
        <label class="flex items-center gap-1 text-xs text-gray-700 cursor-pointer">
          <input type="radio" v-model="conjunction" value="AND" name="ldap-builder-conj" />
          ALL
        </label>
        <label class="flex items-center gap-1 text-xs text-gray-700 cursor-pointer">
          <input type="radio" v-model="conjunction" value="OR" name="ldap-builder-conj" />
          ANY
        </label>
        <span class="text-xs text-gray-500">of these conditions</span>
        <button
          type="button"
          @click="addRule"
          class="ml-auto btn-secondary text-xs"
          aria-label="Add rule"
        >+ Add rule</button>
        <button
          v-if="rules.length"
          type="button"
          @click="clearAll"
          class="text-xs text-gray-500 hover:text-gray-600"
        >Clear</button>
      </div>

      <!-- Suggestion scope hint + toggle. Only shown when the parent
           passed a relevant-attribute list. Pure UX affordance — the
           input still accepts any free-text value either way. -->
      <p v-if="hasRelevantList" class="text-xs text-gray-500 mb-2">
        <template v-if="isScopedToProfile">
          Suggesting profile attributes ({{ suggestionSource.length }}).
          <button
            type="button"
            class="text-blue-600 hover:text-blue-700 ml-1"
            @click="showAllAttributes = true"
          >Show all attributes</button>
        </template>
        <template v-else>
          Showing all directory attributes.
          <button
            type="button"
            class="text-blue-600 hover:text-blue-700 ml-1"
            @click="showAllAttributes = false"
          >Show profile attributes only</button>
        </template>
      </p>

      <!-- Schema status hint. Suppressed when scoped to a profile-supplied
           list — the profile list is a perfectly usable source even if
           the live schema call failed, so a warning would be misleading. -->
      <p
        v-if="schemaState === 'error' && !isScopedToProfile"
        class="text-xs text-amber-600 mb-2"
      >Schema unavailable — type any attribute name.</p>

      <!-- Rule cards -->
      <div v-if="rules.length === 0" class="text-xs text-gray-500 italic py-2">
        No rules yet. Click "+ Add rule" to start building a filter.
      </div>
      <div v-else class="space-y-1.5">
        <div
          v-for="(rule, i) in rules"
          :key="rule.id"
          class="flex items-center gap-2 bg-white border border-gray-200 rounded-md px-2 py-1.5"
        >
          <!-- Custom combobox: input + filtered suggestions listbox.
               Replaces the native <datalist> popup which was
               unstyleable (rendered by OS chrome, ignored page theme).
               The listbox is anchored to this wrapper so it tracks the
               input's width. -->
          <div class="relative flex-1 min-w-[120px]">
            <input
              v-model="rule.attr"
              type="text"
              placeholder="attribute"
              autocomplete="off"
              data-rule-attr
              role="combobox"
              :aria-expanded="isComboOpen(rule.id)"
              :aria-controls="comboListboxId(rule.id)"
              aria-autocomplete="list"
              :aria-activedescendant="isComboOpen(rule.id) && comboHighlightIdx >= 0
                ? comboOptionId(rule.id, comboHighlightIdx)
                : undefined"
              :ref="(el) => setRuleAttrRef(rule.id, el as Element | null)"
              class="input w-full"
              aria-label="Attribute name"
              @focus="openCombo(rule.id)"
              @blur="closeCombo"
              @input="onComboInput(rule)"
              @keydown.down.prevent="onComboNav(rule, 'down')"
              @keydown.up.prevent="onComboNav(rule, 'up')"
              @keydown.enter.prevent="onComboEnter(rule)"
              @keydown.esc="closeCombo"
            />
            <ul
              v-if="isComboOpen(rule.id) && filteredSuggestions(rule).length"
              :id="comboListboxId(rule.id)"
              role="listbox"
              data-combo-listbox
              class="absolute z-20 left-0 right-0 mt-1 max-h-48 overflow-auto rounded-md border border-gray-200 bg-white shadow-md"
            >
              <li
                v-for="(name, suggestionIdx) in filteredSuggestions(rule)"
                :id="comboOptionId(rule.id, suggestionIdx)"
                :key="name"
                role="option"
                data-combo-option
                :aria-selected="comboHighlightIdx === suggestionIdx"
                class="cursor-pointer px-2 py-1 text-sm"
                :class="comboHighlightIdx === suggestionIdx
                  ? 'bg-blue-50 text-blue-700'
                  : 'text-gray-700 hover:bg-gray-50'"
                @mousedown.prevent="selectSuggestion(rule, name)"
              >
                {{ name }}
              </li>
            </ul>
          </div>
          <select
            :value="rule.op"
            @change="setOp(i, ($event.target as HTMLSelectElement).value as FilterOp)"
            class="input shrink-0 w-32"
            aria-label="Operator"
          >
            <option v-for="op in OPERATORS" :key="op.value" :value="op.value">{{ op.label }}</option>
          </select>
          <input
            v-if="operatorHasValue(rule.op)"
            v-model="rule.value"
            type="text"
            :placeholder="rule.op === 'is-one-of' ? 'val1, val2, val3' : 'value'"
            class="input flex-1 min-w-[120px]"
            aria-label="Value"
          />
          <span v-else class="text-xs text-gray-500 italic flex-1">(no value needed)</span>
          <button
            type="button"
            @click="removeRule(i)"
            class="text-gray-500 hover:text-red-500 text-sm shrink-0 px-1"
            :aria-label="`Remove rule ${i + 1}`"
            title="Remove rule"
          >&times;</button>
        </div>
      </div>

      <!-- Live preview of the compiled filter string. Read-only. -->
      <div v-if="compiledPreview" class="mt-3 pt-2 border-t border-gray-200">
        <div class="text-xs text-gray-500 mb-1">Filter:</div>
        <div class="font-mono text-xs text-gray-700 bg-white border border-gray-200 rounded px-2 py-1.5 break-all">
          {{ compiledPreview }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
@reference "tailwindcss";
</style>
