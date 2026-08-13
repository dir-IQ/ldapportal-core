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
      <div
        v-if="type !== 'textarea' && type !== 'select'"
        :class="showReveal ? 'relative' : ''"
      >
        <input
          :id="id"
          :data-field="fieldKey || undefined"
          :type="effectiveType"
          :value="modelValue"
          :placeholder="placeholder"
          :disabled="disabled"
          :required="required"
          :aria-invalid="error ? 'true' : undefined"
          :autocomplete="effectiveAutocomplete"
          @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
          :class="['input block w-full', showReveal ? 'pr-10' : '']"
        />
        <!-- Click-to-toggle reveal for password fields (opt-in via `revealable`). -->
        <button
          v-if="showReveal"
          type="button"
          class="absolute inset-y-0 right-0 flex items-center pr-3 text-gray-500 hover:text-gray-700"
          :aria-label="revealed ? 'Hide password' : 'Show password'"
          :aria-pressed="revealed"
          @click="revealed = !revealed"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path v-if="!revealed" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
            <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
          </svg>
        </button>
      </div>
      <textarea
        v-else-if="type === 'textarea'"
        :id="id"
        :data-field="fieldKey || undefined"
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
        :data-field="fieldKey || undefined"
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
import { computed, ref } from 'vue'
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
  // Stable field key emitted as `data-field`, so useFormErrors() can focus and
  // scroll to this control when it fails validation. Defaults to undefined.
  fieldKey?: string | null
  // Opt-in: for a password field, render a click-to-toggle eye that reveals /
  // masks the value. Off by default so secret fields (bind passwords, API
  // keys) stay masked unless a caller opts in.
  revealable?: boolean
}>(), {
  id: () => `field-${Math.random().toString(36).slice(2)}`,
  type: 'text',
  modelValue: '',
  rows: 3,
  options: () => [],
  error: null,
  autocomplete: null,
  revealable: false,
})
defineEmits<{ 'update:modelValue': [value: string] }>()

// Password reveal (only meaningful when `revealable` and type === 'password').
const revealed = ref(false)
const showReveal = computed(() => props.revealable && props.type === 'password')
const effectiveType = computed(() =>
  showReveal.value && revealed.value ? 'text' : props.type)

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
