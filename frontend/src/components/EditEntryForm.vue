<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <div class="flex items-center justify-between mb-2">
      <h2 class="text-lg font-semibold text-gray-900">Edit Entry</h2>
      <button @click="$emit('cancel')" class="text-sm text-gray-600 hover:text-gray-900 transition-colors">Cancel</button>
    </div>

    <!-- DN (read-only) -->
    <div class="mb-2">
      <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1">Distinguished Name</p>
      <div class="text-sm font-mono text-gray-900 bg-gray-50 px-3 py-2 rounded-lg break-all flex items-center gap-2">
        <span class="flex-1">{{ dn }}</span>
        <CopyButton :text="dn" />
      </div>
    </div>

    <p v-if="loadingSchema" class="text-xs text-gray-500 mb-2">Loading schema...</p>

    <!-- Attributes -->
    <div class="mb-2">
      <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Attributes</p>

      <div v-for="attr in editableAttributes" :key="attr.name" class="mb-2">
        <label class="block text-sm font-medium text-gray-700 mb-1">
          {{ attr.name }}
          <span v-if="attr.required" class="text-red-500">*</span>
          <span v-if="attr.readonly" class="text-xs text-gray-500 ml-1">(read-only)</span>
        </label>

        <!-- Read-only attributes (objectClass, RDN attr) -->
        <template v-if="attr.readonly">
          <div v-for="(val, i) in attr.values" :key="i"
               class="text-sm font-mono text-gray-600 bg-gray-50 px-3 py-2 rounded-lg mb-1 break-all">
            {{ val }}
          </div>
        </template>

        <!-- Editable attributes -->
        <template v-else>
          <div v-for="(val, i) in attr.values" :key="i" class="flex gap-2 mb-1">
            <input v-model="attr.values[i]" class="input flex-1" :placeholder="attr.name" />
            <button v-if="attr.values.length > 1 || !attr.required"
                    @click="removeValue(attr, i)"
                    class="text-red-400 hover:text-red-600 text-lg px-1">&times;</button>
          </div>
          <button @click="addValue(attr)"
                  class="text-xs text-blue-600 hover:text-blue-800 mt-1">+ Add value</button>
        </template>
      </div>
    </div>

    <!-- Add new attribute -->
    <div v-if="availableNewAttrs.length" class="mb-2">
      <div class="flex gap-2">
        <select v-model="newAttrToAdd" class="input flex-1">
          <option value="" disabled>— Add attribute —</option>
          <option v-for="attr in availableNewAttrs" :key="attr" :value="attr">{{ attr }}</option>
        </select>
        <button @click="addAttribute" :disabled="!newAttrToAdd" class="btn btn-secondary">Add</button>
      </div>
    </div>

    <!-- Error display -->
    <div v-if="error" class="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{{ error }}</div>

    <!-- Submit -->
    <div class="flex gap-3 pt-2 border-t border-gray-200">
      <button @click="submit" :disabled="!hasChanges || submitting" class="btn btn-primary">
        {{ submitting ? 'Saving...' : 'Save Entry' }}
      </button>
      <button @click="$emit('cancel')" class="btn btn-neutral">Cancel</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { updateEntry, browseObjectClassesBulk } from '@/api/browse'
import CopyButton from '@/components/CopyButton.vue'

const props = defineProps({
  directoryId: { type: String, required: true },
  dn:          { type: String, required: true },
  attributes:  { type: Object, required: true },
})

const emit = defineEmits(['updated', 'cancel'])

const loadingSchema    = ref(false)
const schemaOptional   = ref([])
const submitting       = ref(false)
const error            = ref('')
const newAttrToAdd     = ref('')

// The RDN attribute name (first component of the DN)
const rdnAttr = computed(() => {
  const eq = props.dn.indexOf('=')
  return eq > 0 ? props.dn.substring(0, eq) : ''
})

// Deep-clone the original attributes so we can diff later
const originalAttrs = ref({})
const editAttrs     = ref([])

// Attributes that should be read-only
const READONLY_ATTRS = ['objectClass']

function isReadonly(attrName) {
  return READONLY_ATTRS.includes(attrName) ||
         attrName.toLowerCase() === rdnAttr.value.toLowerCase()
}

// Build the editable attribute list from props
function initAttributes() {
  const clone = {}
  const attrs = []
  for (const [name, values] of Object.entries(props.attributes)) {
    clone[name] = [...values]
    attrs.push({
      name,
      values: [...values],
      readonly: isReadonly(name),
      required: false, // updated after schema loads
      isNew: false,
    })
  }
  originalAttrs.value = clone
  // Sort: objectClass first, then RDN attr, then alphabetical
  attrs.sort((a, b) => {
    if (a.name === 'objectClass') return -1
    if (b.name === 'objectClass') return 1
    if (a.name.toLowerCase() === rdnAttr.value.toLowerCase()) return -1
    if (b.name.toLowerCase() === rdnAttr.value.toLowerCase()) return 1
    return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
  })
  editAttrs.value = attrs
}

const editableAttributes = computed(() => editAttrs.value)

// Attributes from schema that aren't currently on the entry
const availableNewAttrs = computed(() => {
  const current = new Set(editAttrs.value.map(a => a.name))
  return schemaOptional.value.filter(a => !current.has(a) && !READONLY_ATTRS.includes(a))
})

function addValue(attr) {
  attr.values.push('')
}

function removeValue(attr, index) {
  attr.values.splice(index, 1)
}

function addAttribute() {
  if (!newAttrToAdd.value) return
  editAttrs.value.push({
    name: newAttrToAdd.value,
    values: [''],
    readonly: false,
    required: false,
    isNew: true,
  })
  newAttrToAdd.value = ''
}

// Compute diff between original and edited
const hasChanges = computed(() => {
  return computeModifications().length > 0
})

function computeModifications() {
  const mods = []

  // Check existing attributes for changes
  for (const attr of editAttrs.value) {
    if (attr.readonly) continue

    const orig = originalAttrs.value[attr.name]
    const curr = attr.values.filter(v => v.trim() !== '')

    if (!orig) {
      // New attribute added
      if (curr.length > 0) {
        mods.push({ operation: 'ADD', attribute: attr.name, values: curr })
      }
    } else if (curr.length === 0) {
      // All values removed — delete the attribute
      mods.push({ operation: 'DELETE', attribute: attr.name, values: [] })
    } else {
      // Compare values
      const origSorted = [...orig].sort()
      const currSorted = [...curr].sort()
      if (JSON.stringify(origSorted) !== JSON.stringify(currSorted)) {
        mods.push({ operation: 'REPLACE', attribute: attr.name, values: curr })
      }
    }
  }

  // Check for completely removed attributes (were in original, no longer in editAttrs)
  const currentNames = new Set(editAttrs.value.map(a => a.name))
  for (const name of Object.keys(originalAttrs.value)) {
    if (!currentNames.has(name) && !isReadonly(name)) {
      mods.push({ operation: 'DELETE', attribute: name, values: [] })
    }
  }

  return mods
}

async function submit() {
  error.value = ''
  const mods = computeModifications()
  if (mods.length === 0) return

  submitting.value = true
  try {
    const { data } = await updateEntry(props.directoryId, props.dn, { modifications: mods })
    emit('updated', data)
  } catch (e) {
    error.value = e.response?.data?.detail || e.response?.data?.message || e.message
  } finally {
    submitting.value = false
  }
}

// Load schema to know which optional attrs can be added
onMounted(async () => {
  initAttributes()

  const objectClasses = props.attributes.objectClass || props.attributes.objectclass
  if (!objectClasses?.length) return

  loadingSchema.value = true
  try {
    const { data } = await browseObjectClassesBulk(props.directoryId, objectClasses)
    // Mark required attributes
    const reqSet = new Set(data.required || [])
    for (const attr of editAttrs.value) {
      attr.required = reqSet.has(attr.name)
    }
    // Collect optional attrs for "Add attribute" picker
    schemaOptional.value = [...(data.required || []), ...(data.optional || [])]
  } catch (e) {
    console.warn('Schema loading failed (non-fatal):', e)
  } finally {
    loadingSchema.value = false
  }
})
</script>

<style scoped>
@reference "tailwindcss";
.btn { @apply px-4 py-2 rounded-lg text-sm font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed; }
</style>
