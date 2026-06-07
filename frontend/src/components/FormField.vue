<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="mb-2">
    <div v-if="label" class="flex items-center mb-1">
      <label :for="id" class="block text-sm font-medium text-gray-700">
        {{ label }} <span v-if="required" class="text-red-500">*</span>
      </label>
      <HelpTip v-if="help" :text="help" />
    </div>
    <slot>
      <input
        v-if="type !== 'textarea' && type !== 'select'"
        :id="id"
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :required="required"
        :aria-invalid="error ? 'true' : undefined"
        :autocomplete="effectiveAutocomplete"
        @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
        class="input block w-full"
      />
      <textarea
        v-else-if="type === 'textarea'"
        :id="id"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :rows="rows"
        :aria-invalid="error ? 'true' : undefined"
        @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
        class="input block w-full"
      />
      <select
        v-else
        :id="id"
        :value="modelValue"
        :disabled="disabled"
        :aria-invalid="error ? 'true' : undefined"
        @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
        class="input block w-full"
      >
        <option v-if="!modelValue" value="" disabled selected>Select…</option>
        <option v-for="opt in options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
      </select>
    </slot>
    <p v-if="error" class="mt-1 text-xs text-red-500">{{ error }}</p>
    <p v-else-if="hint" class="mt-1 text-xs text-gray-500">{{ hint }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import HelpTip from '@/components/HelpTip.vue'

interface SelectOption {
  value: string
  label: string
}

const props = withDefaults(defineProps<{
  id?: string
  label?: string
  type?: string
  // String | Number so numeric fields (v-model.number) type-check; the
  // input still round-trips the value as-is at runtime.
  modelValue?: string | number
  placeholder?: string
  hint?: string
  // Optional help text shown via a HelpTip info icon next to the label.
  help?: string
  disabled?: boolean
  required?: boolean
  rows?: number
  options?: SelectOption[]
  // Inline validation error. When set, the control is marked invalid
  // (aria-invalid + red border via the .input state style) and this message
  // replaces the hint.
  error?: string | null
  // Pass null (default) to use the type-aware fallback below. Pass a string
  // to override — e.g. 'current-password' on a login form.
  autocomplete?: string | null
}>(), {
  id: () => `field-${Math.random().toString(36).slice(2)}`,
  type: 'text',
  modelValue: '',
  rows: 3,
  options: () => [],
  error: null,
  autocomplete: null,
})
defineEmits<{ 'update:modelValue': [value: string] }>()

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
