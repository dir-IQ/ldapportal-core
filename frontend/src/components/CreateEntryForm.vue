<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <div class="flex items-center justify-between mb-2">
      <h2 class="text-lg font-semibold text-gray-900">New Entry</h2>
      <button @click="$emit('cancel')" class="text-sm text-gray-600 hover:text-gray-900 transition-colors">Cancel</button>
    </div>

    <!-- Parent DN (read-only) -->
    <div class="mb-2">
      <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1">Parent DN</p>
      <p class="text-sm font-mono text-gray-900 bg-gray-50 px-3 py-2 rounded-lg break-all">{{ parentDn }}</p>
    </div>

    <!-- Object class picker -->
    <div class="mb-2">
      <label class="block text-sm font-medium text-gray-700 mb-1">Object Classes <span class="text-red-500">*</span></label>
      <div class="flex gap-2 mb-2">
        <select v-model="ocToAdd" class="input flex-1">
          <option value="" disabled>— Add object class —</option>
          <optgroup v-if="suggestedClasses.length" label="Common">
            <option v-for="oc in suggestedClasses" :key="oc" :value="oc" :disabled="selectedOcs.includes(oc)">{{ oc }}</option>
          </optgroup>
          <optgroup label="All">
            <option v-for="oc in allObjectClasses" :key="oc" :value="oc" :disabled="selectedOcs.includes(oc)">{{ oc }}</option>
          </optgroup>
        </select>
        <button @click="addObjectClass" :disabled="!ocToAdd" class="btn btn-secondary">Add</button>
      </div>
      <div v-if="selectedOcs.length" class="flex flex-wrap gap-1">
        <span v-for="oc in selectedOcs" :key="oc"
              class="inline-flex items-center gap-1 bg-blue-100 text-blue-800 text-xs font-medium px-2.5 py-1 rounded-full">
          {{ oc }}
          <button @click="removeObjectClass(oc)" aria-label="Remove object class" class="hover:text-blue-600">&times;</button>
        </span>
      </div>
      <p v-if="loadingOcs" class="mt-1 text-xs text-gray-500">Loading object classes...</p>
    </div>

    <!-- RDN attribute + value -->
    <div v-if="selectedOcs.length" class="mb-2">
      <div class="grid grid-cols-3 gap-2">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">RDN Attribute <span class="text-red-500">*</span></label>
          <select v-model="rdnAttribute" class="input w-full">
            <option value="" disabled>— Select —</option>
            <option v-for="attr in rdnCandidates" :key="attr" :value="attr">{{ attr }}</option>
          </select>
        </div>
        <div class="col-span-2">
          <label class="block text-sm font-medium text-gray-700 mb-1">RDN Value <span class="text-red-500">*</span></label>
          <input v-model="rdnValue" class="input w-full" placeholder="e.g. People, Engineering, jdoe" />
        </div>
      </div>
    </div>

    <!-- Computed DN preview -->
    <div v-if="computedDn" class="mb-2">
      <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1">New DN</p>
      <p class="text-sm font-mono text-gray-900 bg-green-50 border border-green-200 px-3 py-2 rounded-lg break-all">{{ computedDn }}</p>
    </div>

    <!-- Required attributes -->
    <div v-if="visibleRequiredAttrs.length" class="mb-2">
      <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Required Attributes</p>
      <div v-for="attr in visibleRequiredAttrs" :key="attr" class="mb-2">
        <label class="block text-sm font-medium text-gray-700 mb-1">{{ attr }} <span class="text-red-500">*</span></label>
        <input v-model="attrValues[attr]" class="input w-full" :placeholder="attr"
               :disabled="attr === rdnAttribute" />
      </div>
    </div>

    <!-- Optional attributes (collapsible) -->
    <div v-if="optionalAttrs.length" class="mb-2">
      <button @click="showOptional = !showOptional"
              class="flex items-center gap-1 text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
        <svg class="w-3 h-3 transition-transform duration-150" :class="{ 'rotate-90': showOptional }"
             viewBox="0 0 12 12" fill="currentColor"><path d="M4 2l5 4-5 4z"/></svg>
        Optional Attributes ({{ optionalAttrs.length }})
      </button>
      <template v-if="showOptional">
        <!-- Added optional attrs -->
        <div v-for="attr in addedOptionalAttrs" :key="attr" class="mb-2 flex gap-2 items-end">
          <div class="flex-1">
            <label class="block text-sm font-medium text-gray-700 mb-1">{{ attr }}</label>
            <input v-model="attrValues[attr]" class="input w-full" :placeholder="attr" />
          </div>
          <button @click="removeOptionalAttr(attr)" aria-label="Remove attribute" class="text-red-400 hover:text-red-600 mb-2">&times;</button>
        </div>
        <!-- Add optional attr picker -->
        <div class="flex gap-2">
          <select v-model="optAttrToAdd" class="input flex-1">
            <option value="" disabled>— Add optional attribute —</option>
            <option v-for="attr in availableOptionalAttrs" :key="attr" :value="attr">{{ attr }}</option>
          </select>
          <button @click="addOptionalAttr" :disabled="!optAttrToAdd" class="btn btn-secondary">Add</button>
        </div>
      </template>
    </div>

    <p v-if="loadingSchema" class="text-xs text-gray-500 mb-2">Loading schema...</p>

    <!-- Error display -->
    <div v-if="error" class="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{{ error }}</div>

    <!-- Submit -->
    <div class="flex gap-2 pt-2 border-t border-gray-200">
      <button @click="submit" :disabled="!canSubmit || submitting" class="btn btn-primary">
        {{ submitting ? 'Creating...' : 'Create Entry' }}
      </button>
      <button @click="$emit('cancel')" class="btn btn-neutral">Cancel</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { browseObjectClasses, browseObjectClassesBulk, createEntry } from '@/api/browse'

const props = defineProps({
  directoryId: { type: String, required: true },
  parentDn:    { type: String, required: true },
})

const emit = defineEmits(['created', 'cancel'])

// ── Object class state ─────────────────────────────────────────────────────
const allObjectClasses = ref([])
const loadingOcs       = ref(false)
const selectedOcs      = ref([])
const ocToAdd          = ref('')

const COMMON_CLASSES = [
  'organizationalUnit', 'inetOrgPerson', 'groupOfUniqueNames',
  'groupOfNames', 'container', 'organization', 'person',
  'organizationalPerson', 'posixAccount', 'posixGroup',
]

const suggestedClasses = computed(() =>
  COMMON_CLASSES.filter(c => allObjectClasses.value.includes(c))
)

// Smart RDN defaults per objectClass
const RDN_DEFAULTS = {
  organizationalUnit: 'ou',
  inetOrgPerson: 'uid',
  groupOfUniqueNames: 'cn',
  groupOfNames: 'cn',
  container: 'cn',
  organization: 'o',
  person: 'cn',
  organizationalPerson: 'cn',
  posixAccount: 'uid',
  posixGroup: 'cn',
  dcObject: 'dc',
  domain: 'dc',
  country: 'c',
  locality: 'l',
}

function addObjectClass() {
  if (ocToAdd.value && !selectedOcs.value.includes(ocToAdd.value)) {
    selectedOcs.value.push(ocToAdd.value)
    // Auto-include 'top' if not present
    if (!selectedOcs.value.includes('top') && allObjectClasses.value.includes('top')) {
      selectedOcs.value.push('top')
    }
  }
  ocToAdd.value = ''
}

function removeObjectClass(oc) {
  selectedOcs.value = selectedOcs.value.filter(c => c !== oc)
}

// ── Schema-driven attributes ───────────────────────────────────────────────
const requiredAttrs  = ref([])
const optionalAttrs  = ref([])
const loadingSchema  = ref(false)
const rdnAttribute   = ref('')
const rdnValue       = ref('')
const attrValues     = ref({})
const showOptional   = ref(false)
const addedOptional  = ref([])
const optAttrToAdd   = ref('')

// Attributes to hide from the form (managed automatically)
const HIDDEN_ATTRS = ['objectClass']

const visibleRequiredAttrs = computed(() =>
  requiredAttrs.value.filter(a => !HIDDEN_ATTRS.includes(a))
)

const addedOptionalAttrs = computed(() => addedOptional.value)

const availableOptionalAttrs = computed(() =>
  optionalAttrs.value.filter(a => !addedOptional.value.includes(a) && !HIDDEN_ATTRS.includes(a))
)

function addOptionalAttr() {
  if (optAttrToAdd.value && !addedOptional.value.includes(optAttrToAdd.value)) {
    addedOptional.value.push(optAttrToAdd.value)
  }
  optAttrToAdd.value = ''
}

function removeOptionalAttr(attr) {
  addedOptional.value = addedOptional.value.filter(a => a !== attr)
  delete attrValues.value[attr]
}

// RDN candidates = required attrs (good defaults for RDN)
const rdnCandidates = computed(() => {
  const candidates = visibleRequiredAttrs.value.length
    ? [...visibleRequiredAttrs.value]
    : []
  // Also include common RDN attrs from optional if not already present
  for (const a of optionalAttrs.value) {
    if (['cn', 'uid', 'ou', 'o', 'dc', 'c', 'l'].includes(a) && !candidates.includes(a)) {
      candidates.push(a)
    }
  }
  return candidates
})

const computedDn = computed(() => {
  if (!rdnAttribute.value || !rdnValue.value) return ''
  return `${rdnAttribute.value}=${rdnValue.value},${props.parentDn}`
})

// Sync RDN value into attribute values
watch(rdnValue, (val) => {
  if (rdnAttribute.value) {
    attrValues.value[rdnAttribute.value] = val
  }
})

watch(rdnAttribute, (attr, oldAttr) => {
  // Clear old RDN attr value if it was set from RDN
  if (oldAttr && attrValues.value[oldAttr] === rdnValue.value) {
    attrValues.value[oldAttr] = ''
  }
  if (attr && rdnValue.value) {
    attrValues.value[attr] = rdnValue.value
  }
})

// Fetch schema when objectClasses change
watch(selectedOcs, async (ocs) => {
  if (!ocs.length) {
    requiredAttrs.value = []
    optionalAttrs.value = []
    rdnAttribute.value = ''
    return
  }

  loadingSchema.value = true
  try {
    const { data } = await browseObjectClassesBulk(props.directoryId, ocs)
    requiredAttrs.value = [...(data.required || [])]
    optionalAttrs.value = [...(data.optional || [])]

    // Auto-pick RDN attribute based on the first selected objectClass
    if (!rdnAttribute.value) {
      for (const oc of ocs) {
        if (RDN_DEFAULTS[oc]) {
          rdnAttribute.value = RDN_DEFAULTS[oc]
          break
        }
      }
    }
  } catch (e) {
    console.warn('Failed to load schema for object classes:', e)
    requiredAttrs.value = []
    optionalAttrs.value = []
  } finally {
    loadingSchema.value = false
  }
}, { deep: true })

// ── Submit ─────────────────────────────────────────────────────────────────
const submitting = ref(false)
const error      = ref('')

const canSubmit = computed(() =>
  selectedOcs.value.length > 0 &&
  rdnAttribute.value &&
  rdnValue.value.trim() &&
  !submitting.value
)

async function submit() {
  error.value = ''
  submitting.value = true

  const attributes = {}

  // objectClass
  attributes.objectClass = [...selectedOcs.value]

  // RDN attribute
  attributes[rdnAttribute.value] = [rdnValue.value.trim()]

  // Required + optional attribute values
  for (const [key, val] of Object.entries(attrValues.value)) {
    if (val && key !== rdnAttribute.value) {
      attributes[key] = [val]
    }
  }

  try {
    const { data } = await createEntry(props.directoryId, {
      dn: computedDn.value,
      attributes,
    })
    emit('created', data)
  } catch (e) {
    error.value = e.response?.data?.detail || e.response?.data?.message || e.message
  } finally {
    submitting.value = false
  }
}

// ── Init ───────────────────────────────────────────────────────────────────
onMounted(async () => {
  loadingOcs.value = true
  try {
    const { data } = await browseObjectClasses(props.directoryId)
    allObjectClasses.value = data
  } catch (e) {
    console.warn('Failed to load object classes:', e)
    allObjectClasses.value = []
  } finally {
    loadingOcs.value = false
  }
})
</script>

<style scoped>
@reference "tailwindcss";
.btn { @apply px-4 py-2 rounded-lg text-sm font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed; }
</style>
