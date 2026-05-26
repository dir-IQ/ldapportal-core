<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="mb-2">
    <label v-if="label" :for="id" class="block text-sm font-medium text-gray-700 mb-1">
      {{ label }} <span v-if="required" class="text-red-500">*</span>
    </label>
    <slot>
      <input
        v-if="type !== 'textarea' && type !== 'select'"
        :id="id"
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :required="required"
        :autocomplete="effectiveAutocomplete"
        @input="$emit('update:modelValue', $event.target.value)"
        class="input block w-full"
      />
      <textarea
        v-else-if="type === 'textarea'"
        :id="id"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :rows="rows"
        @input="$emit('update:modelValue', $event.target.value)"
        class="input block w-full"
      />
      <select
        v-else
        :id="id"
        :value="modelValue"
        :disabled="disabled"
        @change="$emit('update:modelValue', $event.target.value)"
        class="input block w-full"
      >
        <option v-if="!modelValue" value="" disabled selected>Select…</option>
        <option v-for="opt in options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
      </select>
    </slot>
    <p v-if="hint" class="mt-1 text-xs text-gray-500">{{ hint }}</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  id:          { type: String, default: () => `field-${Math.random().toString(36).slice(2)}` },
  label:       String,
  type:        { type: String, default: 'text' },
  modelValue:  { default: '' },
  placeholder: String,
  hint:        String,
  disabled:    Boolean,
  required:    Boolean,
  rows:        { type: Number, default: 3 },
  options:     { type: Array, default: () => [] }, // [{ value, label }]
  // Pass null (default) to use the type-aware fallback below. Pass a string
  // to override — e.g. 'current-password' on a login form.
  autocomplete: { type: String, default: null },
})
defineEmits(['update:modelValue'])

// Password inputs default to 'new-password'. That's the well-supported trick
// for disabling autofill of the user's saved login password into app config
// forms (LDAP bind secrets, SMTP creds, S3 keys, etc.) — 'off' is routinely
// ignored by browsers for type="password" but 'new-password' is honored.
// Login screens should pass autocomplete="current-password" explicitly.
const effectiveAutocomplete = computed(() => {
  if (props.autocomplete != null) return props.autocomplete
  return props.type === 'password' ? 'new-password' : 'off'
})
</script>
