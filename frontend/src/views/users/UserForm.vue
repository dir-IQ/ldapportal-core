<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <!-- Tabs (shown in both create and edit modes) -->
    <div class="flex border-b border-gray-200 mb-2">
      <button
        @click="activeTab = 'attributes'"
        class="px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors"
        :class="activeTab === 'attributes' ? 'border-blue-600 text-blue-600' : 'border-transparent text-gray-500 hover:text-gray-700'"
      >Attributes</button>
      <button
        @click="activeTab = 'groups'"
        class="px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors"
        :class="activeTab === 'groups' ? 'border-blue-600 text-blue-600' : 'border-transparent text-gray-500 hover:text-gray-700'"
      >Groups</button>
    </div>

    <!-- ═══ Attributes tab ═══ -->
    <div v-show="activeTab === 'attributes'">

      <!-- ── Create mode ── -->
      <div v-if="!isEdit" class="space-y-2">
        <!-- Fallback RDN + DN row when no user form config -->
        <div v-if="!userTemplateConfig" class="grid grid-cols-6 gap-2">
          <FormField label="RDN Attribute" v-model="local.rdnAttribute" placeholder="uid" required />
          <div class="col-span-4">
            <FormField
              label="DN"
              :model-value="computedDn"
              placeholder="uid=jsmith,ou=people,dc=example,dc=com"
              required
              disabled
            />
          </div>
        </div>

        <!-- RDN Value when using fallback (no user form config) -->
        <FormField v-if="!userTemplateConfig" label="RDN Value" v-model="local.rdnValue" placeholder="jsmith" required />

        <!-- Dynamic fields from user form config (all attributes in layout order) -->
        <template v-if="userTemplateConfig?.attributeConfigs?.length">
          <template v-for="(section, sIdx) in createSections" :key="sIdx">
            <fieldset v-if="section.fields.length" class="space-y-2">
              <legend v-if="section.name" class="text-sm font-semibold text-gray-800 pb-1 border-b border-gray-100 w-full mb-2">{{ section.name }}</legend>
              <div class="grid grid-cols-6 gap-2">
                <template
                  v-for="attr in section.fields"
                  :key="attr.id || attr.attributeName"
                >
                  <!-- RDN field -->
                  <div v-if="attr.rdn" :style="{ gridColumn: showDnField ? 'span 2' : `span ${attr.columnSpan || 6}` }">
                    <FormField
                      :label="(attr.customLabel || attr.attributeName) + ' (RDN)'"
                      v-model="local.rdnValue"
                      :type="mapInputType(attr.inputType)"
                      required
                      :placeholder="attr.attributeName"
                    />
                  </div>
                  <!-- Computed DN (shown after RDN when enabled) -->
                  <div v-if="attr.rdn && showDnField" class="col-span-4">
                    <FormField
                      label="DN"
                      :model-value="computedDn"
                      placeholder="uid=jsmith,ou=people,dc=example,dc=com"
                      required
                      disabled
                    />
                  </div>
                  <!-- Password field with generate/show/copy (create mode only) -->
                  <div
                    v-if="!attr.rdn && attr.inputType === 'PASSWORD'"
                    :style="{ gridColumn: `span ${attr.columnSpan || 6}` }"
                  >
                    <label :for="`uf-pw-${attr.attributeName}`" class="block text-sm font-medium text-gray-700 mb-1">
                      {{ attr.customLabel || attr.attributeName }}
                      <span v-if="attr.requiredOnCreate" class="text-red-500">*</span>
                    </label>
                    <div class="flex gap-1">
                      <div class="relative flex-1">
                        <input
                          :id="`uf-pw-${attr.attributeName}`"
                          :type="passwordVisible ? 'text' : 'password'"
                          :value="local.attributes[attr.attributeName]"
                          @input="local.attributes[attr.attributeName] = $event.target.value"
                          :required="attr.requiredOnCreate"
                          :disabled="!attr.editableOnCreate"
                          class="block w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:bg-gray-100 pr-8"
                        />
                        <button v-if="local.attributes[attr.attributeName]" type="button"
                          class="absolute right-2 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
                          @mousedown.prevent="passwordVisible = true"
                          @mouseup.prevent="passwordVisible = false"
                          @mouseleave="passwordVisible = false"
                          @touchstart.prevent="passwordVisible = true"
                          @touchend.prevent="passwordVisible = false"
                          title="Hold to show password">
                          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path v-if="!passwordVisible" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                            <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
                          </svg>
                        </button>
                      </div>
                      <button v-if="profileId" type="button" @click="doGeneratePassword(attr.attributeName)"
                        :disabled="generatingPassword"
                        class="px-2 py-1 text-xs rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50 whitespace-nowrap"
                        title="Generate password">
                        <svg class="w-4 h-4 inline" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z" />
                        </svg>
                      </button>
                      <button v-if="local.attributes[attr.attributeName]" type="button"
                        @click="copyPassword(attr.attributeName)"
                        class="px-2 py-1 text-xs rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 whitespace-nowrap"
                        title="Copy to clipboard">
                        <svg class="w-4 h-4 inline" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 5H6a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2v-1M8 5a2 2 0 002 2h2a2 2 0 002-2M8 5a2 2 0 012-2h2a2 2 0 012 2m0 0h2a2 2 0 012 2v3m2 4H10m0 0l3-3m-3 3l3 3" />
                        </svg>
                      </button>
                    </div>
                  </div>
                  <!-- Regular field -->
                  <div
                    v-else-if="!attr.rdn"
                    :style="{ gridColumn: `span ${attr.columnSpan || 6}` }"
                  >
                    <!-- DN Lookup: use DnPicker instead of text input -->
                    <template v-if="attr.inputType === 'DN_LOOKUP'">
                      <label class="block text-sm font-medium text-gray-700 mb-1">{{ attr.customLabel || attr.attributeName }}</label>
                      <DnPicker
                        :model-value="local.attributes[attr.attributeName]"
                        @update:model-value="v => { local.attributes[attr.attributeName] = v }"
                        :directory-id="dirId"
                        :placeholder="'Select a DN'"
                        :superadmin="false"
                      />
                    </template>
                    <FormField
                      v-else
                      :label="attr.customLabel || attr.attributeName"
                      :model-value="attr.computedExpression ? computedAttrValues[attr.attributeName] : local.attributes[attr.attributeName]"
                      @update:model-value="v => { if (!attr.computedExpression) local.attributes[attr.attributeName] = v }"
                      :type="mapInputType(attr.inputType)"
                      :options="attr.inputType === 'SELECT' ? parseOptions(attr.allowedValues) : undefined"
                      :required="attr.requiredOnCreate"
                      :disabled="!attr.editableOnCreate"
                      :rows="attr.inputType === 'TEXTAREA' || attr.inputType === 'MULTI_VALUE' ? 3 : undefined"
                      :hint="attr.inputType === 'MULTI_VALUE' ? 'One value per line' : undefined"
                    />
                  </div>
                </template>
              </div>
            </fieldset>
          </template>
        </template>

        <!-- Fallback: hardcoded fields when no user form config -->
        <template v-if="!userTemplateConfig">
          <FormField label="cn (Common Name)" v-model="local.attributes.cn" required />
          <FormField label="sn (Surname)" v-model="local.attributes.sn" />
          <FormField label="mail" v-model="local.attributes.mail" />
          <FormField label="userPassword" type="password" v-model="local.attributes.userPassword" />
        </template>
      </div>

      <!-- ── Edit mode ── -->
      <div v-else class="space-y-2">
        <p class="text-xs text-gray-600 mb-2">Editing: <code class="bg-gray-100 px-1 rounded">{{ local.dn }}</code></p>

        <!-- When user form config is available, render structured fields -->
        <template v-if="userTemplateConfig?.attributeConfigs?.length">
          <template v-for="(section, sIdx) in editSections" :key="sIdx">
            <fieldset v-if="section.fields.length" class="space-y-2">
              <legend v-if="section.name" class="text-sm font-semibold text-gray-800 pb-1 border-b border-gray-100 w-full mb-2">{{ section.name }}</legend>
              <div class="grid grid-cols-6 gap-2">
                <template
                  v-for="attr in section.fields"
                  :key="attr.id || attr.attributeName"
                >
                  <!-- RDN field in edit mode -->
                  <div v-if="attr.rdn" :style="{ gridColumn: showDnField ? 'span 2' : `span ${attr.columnSpan || 6}` }">
                    <FormField
                      :label="attr.customLabel || attr.attributeName"
                      v-model="local.attributes[attr.attributeName]"
                      :type="mapInputType(attr.inputType)"
                      :required="attr.requiredOnCreate"
                      disabled
                      :rows="attr.inputType === 'TEXTAREA' || attr.inputType === 'MULTI_VALUE' ? 3 : undefined"
                      :hint="attr.inputType === 'MULTI_VALUE' ? 'One value per line' : undefined"
                    />
                  </div>
                  <!-- DN field (shown after RDN when enabled, edit mode) -->
                  <div v-if="attr.rdn && showDnField" class="col-span-4">
                    <FormField
                      label="DN"
                      :model-value="local.dn"
                      disabled
                    />
                  </div>
                  <!-- Regular field -->
                  <div
                    v-if="!attr.rdn"
                    :style="{ gridColumn: `span ${attr.columnSpan || 6}` }"
                  >
                    <!-- DN Lookup: use DnPicker instead of text input -->
                    <template v-if="attr.inputType === 'DN_LOOKUP'">
                      <label class="block text-sm font-medium text-gray-700 mb-1">{{ attr.customLabel || attr.attributeName }}</label>
                      <DnPicker
                        v-model="local.attributes[attr.attributeName]"
                        :directory-id="dirId"
                        :placeholder="'Select a DN'"
                        :superadmin="false"
                        :disabled="!attr.editableOnUpdate"
                      />
                    </template>
                    <FormField
                      v-else
                      :label="attr.customLabel || attr.attributeName"
                      v-model="local.attributes[attr.attributeName]"
                      :type="mapInputType(attr.inputType)"
                      :options="attr.inputType === 'SELECT' ? parseOptions(attr.allowedValues) : undefined"
                      :required="attr.requiredOnCreate"
                      :disabled="!attr.editableOnUpdate"
                      :rows="attr.inputType === 'TEXTAREA' || attr.inputType === 'MULTI_VALUE' ? 3 : undefined"
                      :hint="attr.inputType === 'MULTI_VALUE' ? 'One value per line' : undefined"
                    />
                  </div>
                </template>
              </div>
            </fieldset>
          </template>

          <!-- Other attributes not in the form config -->
          <div v-if="Object.keys(extraEditAttributes).length">
            <button @click="showExtraAttrs = !showExtraAttrs"
                    class="flex items-center gap-1 text-xs font-medium text-gray-500 hover:text-gray-700 mt-2">
              <svg :class="['w-3 h-3 transition-transform', showExtraAttrs && 'rotate-90']"
                   viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.29a.75.75 0 111.04-1.08l4.5 4.25a.75.75 0 010 1.08l-4.5 4.25a.75.75 0 01-1.06-.02z" clip-rule="evenodd"/>
              </svg>
              Other Attributes ({{ Object.keys(extraEditAttributes).length }})
            </button>
            <div v-if="showExtraAttrs" class="space-y-2 mt-3 pl-3 border-l-2 border-gray-100">
              <template v-for="(_, key) in extraEditAttributes" :key="key">
                <FormField :label="key" v-model="local.attributes[key]" type="textarea" :rows="2" hint="One value per line" />
              </template>
            </div>
          </div>
        </template>

        <!-- Fallback: raw attribute editing when no form config -->
        <template v-else>
          <template v-for="(_, key) in editableAttributes" :key="key">
            <FormField :label="key" v-model="local.attributes[key]" type="textarea" :rows="2" hint="One value per line" />
          </template>
        </template>
      </div>
    </div>

    <!-- ═══ Groups tab ═══ -->
    <div v-show="activeTab === 'groups'">
      <p v-if="isEdit" class="text-xs text-gray-600 mb-2">Manage group memberships for <code class="bg-gray-100 px-1 rounded">{{ local.dn }}</code></p>
      <p v-else class="text-xs text-gray-500 mb-2">Select groups for the new user. Memberships will be created after the user is saved.</p>

      <!-- Current memberships (edit mode only) -->
      <div v-if="isEdit" class="mb-2">
        <h3 class="text-sm font-medium text-gray-700 mb-2">Current Groups</h3>
        <div v-if="loadingGroups" class="text-sm text-gray-500 py-3 text-center">Loading…</div>
        <ul v-else-if="memberGroups.length" class="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden">
          <li v-for="g in memberGroups" :key="g.dn" class="flex items-center justify-between px-3 py-2 text-sm hover:bg-gray-50">
            <div>
              <span class="font-medium text-gray-800">{{ g.cn }}</span>
              <code class="text-xs text-gray-500 ml-2">{{ g.dn }}</code>
            </div>
            <button @click="removeFromGroup(g)" class="text-red-500 hover:text-red-700 text-xs font-medium">Remove</button>
          </li>
        </ul>
        <p v-else class="text-sm text-gray-500 py-3 text-center border border-gray-200 rounded-lg">Not a member of any groups</p>
      </div>

      <!-- Pending groups (create mode only) -->
      <div v-if="!isEdit && pendingGroups.length" class="mb-2">
        <h3 class="text-sm font-medium text-gray-700 mb-2">Groups to Join</h3>
        <ul class="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden">
          <li v-for="g in pendingGroups" :key="g.dn" class="flex items-center justify-between px-3 py-2 text-sm hover:bg-gray-50">
            <div>
              <span class="font-medium text-gray-800">{{ g.cn }}</span>
              <code class="text-xs text-gray-500 ml-2">{{ g.dn }}</code>
            </div>
            <button @click="removePendingGroup(g)" class="text-red-500 hover:text-red-700 text-xs font-medium">Remove</button>
          </li>
        </ul>
      </div>

      <!-- Add to group -->
      <div>
        <h3 class="text-sm font-medium text-gray-700 mb-2">Add to Group</h3>
        <div class="flex gap-2 mb-2">
          <input
            v-model="groupFilter"
            placeholder="Search groups…"
            aria-label="Search groups"
            @keyup.enter="searchAvailableGroups"
            class="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <button @click="searchAvailableGroups" class="btn-primary text-xs">Search</button>
        </div>
        <div v-if="loadingGroups" class="text-sm text-gray-500 py-3 text-center">Loading…</div>
        <p v-else-if="!groupFilter.trim() && !isEdit && availableGroups.length === 0" class="text-xs text-gray-500 py-3 text-center">Type a group name and click Search to find groups.</p>
        <ul v-else-if="availableGroups.length" class="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden max-h-48 overflow-y-auto">
          <li v-for="g in availableGroups" :key="g.dn" class="flex items-center justify-between px-3 py-2 text-sm hover:bg-gray-50">
            <div>
              <span class="font-medium text-gray-800">{{ g.cn }}</span>
              <code class="text-xs text-gray-500 ml-2">{{ g.dn }}</code>
            </div>
            <button @click="addToGroup(g)" class="text-blue-600 hover:text-blue-800 text-xs font-medium">Add</button>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, watch, nextTick, computed, onMounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import FormField from '@/components/FormField.vue'
import DnPicker from '@/components/DnPicker.vue'
import * as groupsApi from '@/api/groups'
import { generatePassword } from '@/api/profiles'

const props = defineProps({
  data: { type: Object, required: true },
  isEdit: Boolean,
  userTemplateConfig: { type: Object, default: null },
  dirId: { type: String, default: null },
  profileId: { type: String, default: null },
})
const emit = defineEmits(['update'])

const local = reactive({
  ...props.data,
  attributes: { ...(props.data.attributes || {}) }
})

// Ensure SELECT fields have their defaultValue applied even if emptyForm() missed them
if (!props.isEdit && props.userTemplateConfig?.attributeConfigs) {
  for (const attr of props.userTemplateConfig.attributeConfigs) {
    if (attr.inputType === 'SELECT' && attr.defaultValue && !local.attributes[attr.attributeName]) {
      local.attributes[attr.attributeName] = attr.defaultValue
    }
  }
}

// Password generate / show / copy state
const passwordVisible = ref(false)
const generatingPassword = ref(false)

async function doGeneratePassword(attrName) {
  if (!props.profileId) return
  generatingPassword.value = true
  try {
    const { data } = await generatePassword(props.profileId)
    local.attributes[attrName] = data.password
    passwordVisible.value = true
  } catch {
    useNotificationStore().error('Failed to generate password')
  } finally {
    generatingPassword.value = false
  }
}

function copyPassword(attrName) {
  const val = local.attributes[attrName]
  if (val) navigator.clipboard.writeText(val)
}

const activeTab       = ref('attributes')
const loadingGroups   = ref(false)
const memberGroups    = ref([])
const availableGroups = ref([])
const groupFilter     = ref('')
const allGroups       = ref([])
const pendingGroups   = ref([])

const showExtraAttrs = ref(false)

const HIDDEN_EDIT_ATTRS = new Set(['objectclass', 'objectClass', 'userpassword', 'userPassword', 'unicodePwd', 'unicodepwd'])

/** Attributes to show in edit mode (excludes objectClass). */
const editableAttributes = computed(() => {
  const result = {}
  for (const key of Object.keys(local.attributes)) {
    if (!HIDDEN_EDIT_ATTRS.has(key)) {
      result[key] = local.attributes[key]
    }
  }
  return result
})

/** Attributes from the form config to show in edit mode (excludes objectClass, password, and hidden; includes RDN). */
const editFormAttributes = computed(() => {
  if (!props.userTemplateConfig?.attributeConfigs) return []
  const rdnName = props.userTemplateConfig.rdnAttribute
  return props.userTemplateConfig.attributeConfigs
    .filter(a => !a.hidden && !HIDDEN_EDIT_ATTRS.has(a.attributeName) && !HIDDEN_EDIT_ATTRS.has(a.attributeName.toLowerCase()))
    .map(a => ({ ...a, rdn: a.attributeName === rdnName }))
})

/** Attributes present on the entry but NOT in the form config (edit mode overflow). */
const extraEditAttributes = computed(() => {
  if (!props.userTemplateConfig?.attributeConfigs) return {}
  const configuredNames = new Set(
    props.userTemplateConfig.attributeConfigs.map(a => a.attributeName.toLowerCase())
  )
  const result = {}
  for (const key of Object.keys(local.attributes)) {
    if (!HIDDEN_EDIT_ATTRS.has(key) && !configuredNames.has(key.toLowerCase())) {
      result[key] = local.attributes[key]
    }
  }
  return result
})

const INPUT_TYPE_MAP = {
  TEXT: 'text',
  TEXTAREA: 'textarea',
  PASSWORD: 'password',
  BOOLEAN: 'checkbox',
  DATE: 'date',
  DATETIME: 'datetime-local',
  MULTI_VALUE: 'textarea',
  DN_LOOKUP: 'text',
  SELECT: 'select',
  HIDDEN_FIXED: 'hidden',
}

function mapInputType(inputType) {
  return INPUT_TYPE_MAP[inputType] || 'text'
}

/** Parse the allowedValues JSON string into FormField options. */
function parseOptions(allowedValues) {
  if (!allowedValues) return []
  try {
    const arr = JSON.parse(allowedValues)
    if (!Array.isArray(arr)) return []
    return arr.map(v => ({ value: String(v), label: String(v) }))
  } catch {
    return allowedValues.split(',').map(v => ({ value: v.trim(), label: v.trim() }))
  }
}

/** The attribute marked as RDN in the user form config. */
const rdnAttr = computed(() => {
  if (!props.userTemplateConfig?.attributeConfigs) return null
  const rdnName = props.userTemplateConfig.rdnAttribute
  return props.userTemplateConfig.attributeConfigs.find(a => a.attributeName === rdnName) || null
})

/** Computed full DN based on RDN attribute, RDN value, and parent DN. */
const computedDn = computed(() => {
  const attr = rdnAttr.value?.attributeName || local.rdnAttribute || ''
  const val = local.rdnValue || ''
  const base = local.parentDn || ''
  if (!attr || !val || !base) return ''
  return `${attr}=${val},${base}`
})

/** Whether to show the DN field alongside the RDN. */
const showDnField = computed(() => props.userTemplateConfig?.showDnField !== false)

/** All non-hidden attributes (including RDN), preserving the order defined in the user form config. */
const allVisibleAttributes = computed(() => {
  if (!props.userTemplateConfig?.attributeConfigs) return []
  const rdnName = props.userTemplateConfig.rdnAttribute
  return props.userTemplateConfig.attributeConfigs
    .filter(a => !a.hidden && a.attributeName.toLowerCase() !== 'objectclass')
    .map(a => ({ ...a, rdn: a.attributeName === rdnName }))
})

/** Group all visible attributes into sections for create mode. */
const createSections = computed(() => groupIntoSections(allVisibleAttributes.value))

/** Group edit-mode attributes into sections. */
const editSections = computed(() => groupIntoSections(editFormAttributes.value))

function groupIntoSections(attrs) {
  const map = new Map()
  for (const attr of attrs) {
    const key = attr.sectionName || ''
    if (!map.has(key)) {
      map.set(key, { name: key, fields: [] })
    }
    map.get(key).fields.push(attr)
  }
  const result = Array.from(map.values())
  return result.length ? result : [{ name: '', fields: attrs }]
}

/**
 * Parse and evaluate a computed expression by tokenizing into variable
 * references (${attr}), quoted string literals, concatenation operators (+),
 * and literal text.  No regex used for the concatenation handling.
 */
function evaluateExpression(expr) {
  const parts = []
  let i = 0
  while (i < expr.length) {
    if (expr[i] === '$' && expr[i + 1] === '{') {
      // Variable reference: ${attrName}
      const end = expr.indexOf('}', i + 2)
      if (end === -1) break
      const name = expr.substring(i + 2, end)
      if (name === local.rdnAttribute) {
        parts.push(local.rdnValue || '')
      } else {
        parts.push(local.attributes[name] || '')
      }
      i = end + 1
    } else if (expr[i] === '+') {
      // Concatenation operator — skip it
      i++
    } else if (expr[i] === '"' || expr[i] === "'") {
      // Quoted string literal
      const quote = expr[i]
      const end = expr.indexOf(quote, i + 1)
      if (end === -1) break
      parts.push(expr.substring(i + 1, end))
      i = end + 1
    } else {
      // Literal text (e.g. dots, @domain, etc.)
      let j = i
      while (j < expr.length && expr[j] !== '$' && expr[j] !== '+' && expr[j] !== '"' && expr[j] !== "'") {
        j++
      }
      parts.push(expr.substring(i, j))
      i = j
    }
  }
  return parts.join('')
}

/**
 * Computed map of all attribute values derived from computed expressions.
 * Vue tracks which reactive properties are read (e.g. local.attributes.givenName),
 * so this recomputes only when a referenced source attribute changes —
 * no manual watcher, no reentrancy flag, no per-keystroke issues.
 */
const computedAttrValues = computed(() => {
  const result = {}
  if (!props.userTemplateConfig?.attributeConfigs || props.isEdit) return result
  for (const attr of props.userTemplateConfig.attributeConfigs) {
    if (!attr.computedExpression) continue
    try {
      result[attr.attributeName] = evaluateExpression(String(attr.computedExpression))
    } catch {
      // Skip failed expression evaluation
    }
  }
  return result
})

let syncing = false
watch(local, v => {
  if (syncing) return
  const data = JSON.parse(JSON.stringify(v))
  // Merge computed attribute values into the emitted data
  const cv = computedAttrValues.value
  for (const key in cv) {
    data.attributes[key] = cv[key]
    if (key === local.rdnAttribute) {
      data.rdnValue = cv[key]
    }
  }
  emit('update', data)
}, { deep: true })
watch(() => props.data, v => {
  syncing = true
  Object.assign(local, v)
  Object.assign(local.attributes, v.attributes || {})
  nextTick(() => { syncing = false })
}, { deep: true })

// ── Group membership management ──────────────────────────────────────────────

async function loadGroups() {
  if (!props.dirId) return
  // In create mode, only load groups if user has typed a search query
  // to avoid fetching every group in the directory
  if (!props.isEdit && !groupFilter.value.trim()) {
    allGroups.value = []
    availableGroups.value = []
    return
  }
  loadingGroups.value = true
  try {
    const params = {}
    if (groupFilter.value.trim()) {
      params.filter = `(cn=*${groupFilter.value.trim()}*)`
    }
    const { data } = await groupsApi.searchGroups(props.dirId, params)
    const entries = Array.isArray(data) ? data : (data?.entries || [])
    allGroups.value = entries.map(e => ({
      dn: e.dn,
      cn: e.attributes?.cn?.[0] || '—',
      members: e.attributes?.member || e.attributes?.uniqueMember || e.attributes?.memberUid || [],
      memberAttr: e.attributes?.member ? 'member'
        : e.attributes?.uniqueMember ? 'uniqueMember'
        : e.attributes?.memberUid ? 'memberUid'
        : 'member',
    }))
    refreshMemberships()
  } catch (e) { console.warn('Failed to load groups:', e) }
  finally { loadingGroups.value = false }
}

function refreshMemberships() {
  if (props.isEdit) {
    const userDn = local.dn
    memberGroups.value = allGroups.value.filter(g =>
      g.members.some(m => m.toLowerCase() === userDn.toLowerCase())
    )
  }
  filterAvailableGroups()
}

function filterAvailableGroups() {
  const excludedDnSet = new Set()
  // Exclude groups user is already a member of (edit mode)
  for (const g of memberGroups.value) excludedDnSet.add(g.dn.toLowerCase())
  // Exclude groups already pending (create mode)
  for (const g of pendingGroups.value) excludedDnSet.add(g.dn.toLowerCase())

  const q = groupFilter.value.toLowerCase()
  availableGroups.value = allGroups.value.filter(g =>
    !excludedDnSet.has(g.dn.toLowerCase()) &&
    (!q || g.cn.toLowerCase().includes(q) || g.dn.toLowerCase().includes(q))
  )
}

function searchAvailableGroups() {
  filterAvailableGroups()
}

async function addToGroup(group) {
  if (props.isEdit) {
    // Edit mode: immediately persist the membership via API
    try {
      const res = await groupsApi.addGroupMember(props.dirId, group.dn, {
        memberAttribute: group.memberAttr,
        memberValue: local.dn,
      })
      if (res.status === 202) {
        const notif = useNotificationStore()
        notif.success('Group member addition submitted for approval')
      } else {
        group.members.push(local.dn)
      }
      refreshMemberships()
    } catch (e) {
      // silent
    }
  } else {
    // Create mode: queue for after save
    pendingGroups.value.push(group)
    filterAvailableGroups()
    emitPendingGroups()
  }
}

async function removeFromGroup(group) {
  try {
    await groupsApi.removeGroupMember(props.dirId, group.dn, {
      memberAttribute: group.memberAttr,
      memberValue: local.dn,
    })
    group.members = group.members.filter(m => m.toLowerCase() !== local.dn.toLowerCase())
    refreshMemberships()
  } catch (e) {
    // silent
  }
}

function removePendingGroup(group) {
  pendingGroups.value = pendingGroups.value.filter(g => g.dn !== group.dn)
  filterAvailableGroups()
  emitPendingGroups()
}

function emitPendingGroups() {
  emit('update', {
    ...JSON.parse(JSON.stringify(local)),
    _pendingGroups: pendingGroups.value.map(g => ({ dn: g.dn, memberAttr: g.memberAttr })),
  })
}

onMounted(() => {
  // Initialize pending groups from profile group assignments passed via data
  if (props.data?._pendingGroups?.length) {
    pendingGroups.value = props.data._pendingGroups.map(g => ({
      dn: g.dn,
      cn: g.dn.split(',')[0] || g.dn,
      memberAttr: g.memberAttr,
      members: [],
    }))
  }
  if (props.dirId) {
    loadGroups()
  }
})

// Reload groups when switching to edit mode with a new user
watch(() => props.data?.dn, () => {
  if (props.dirId) {
    activeTab.value = 'attributes'
    pendingGroups.value = []
    loadGroups()
  }
})
</script>
