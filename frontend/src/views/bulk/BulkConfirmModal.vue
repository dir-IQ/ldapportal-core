<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Themed confirmation step for a bulk import/delete. The modal header fills with
  the active profile's theme colour so the operator can't miss which environment
  they're committing against, and the body restates the operation + target OU.
  For irreversible actions (delete) the operator additionally types the profile
  name to arm the confirm button; reversible ones (import) just confirm.
-->
<template>
  <AppModal :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)"
            :title="title" :header-color="themeColor || ''" size="md"
            :movable="false" :resizable="false">
    <div class="space-y-3">
      <!-- Restated target. The profile name chip carries the theme colour again
           (as text) so the environment reads even for colour-blind operators. -->
      <p class="text-sm text-gray-700">
        {{ summary }}
        <span class="font-semibold" :style="themeColor ? { color: themeColor } : {}">{{ profileName }}</span>
        <template v-if="targetDn">
          · <span class="font-mono text-gray-600">{{ targetDn }}</span>
        </template>
      </p>

      <div v-if="requireTyped">
        <p class="text-sm text-gray-700 mb-1">
          This can't be undone. Type the profile name
          <code class="font-mono bg-gray-100 px-1 rounded">{{ profileName }}</code>
          to confirm.
        </p>
        <input v-model="typed" class="input w-full" :placeholder="profileName"
               aria-label="Type the profile name to confirm" @keyup.enter="onEnter" />
      </div>
    </div>

    <template #footer="{ close }">
      <button @click="close()" class="btn-neutral">Cancel</button>
      <button @click="$emit('confirm')" :disabled="busy || (requireTyped && !armed)"
              :class="danger ? 'btn-danger' : 'btn-primary'">
        {{ busy ? 'Working…' : confirmLabel }}
      </button>
    </template>
  </AppModal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  profileName: string
  themeColor?: string | null
  title: string
  /** Lead-in sentence; the profile name + target DN are appended by the template. */
  summary: string
  targetDn?: string | null
  confirmLabel: string
  /** Irreversible actions require typing the profile name to arm Confirm. */
  requireTyped?: boolean
  danger?: boolean
  busy?: boolean
}>(), {
  themeColor: '', targetDn: '', requireTyped: false, danger: false, busy: false,
})
const emit = defineEmits<{ 'update:modelValue': [boolean], confirm: [] }>()

const typed = ref('')
const armed = computed(() =>
  typed.value.trim().toLowerCase() === props.profileName.trim().toLowerCase(),
)
// Clear the typed value whenever the modal closes so a re-open starts disarmed.
watch(() => props.modelValue, open => { if (!open) typed.value = '' })

function onEnter() {
  if (!props.busy && (!props.requireTyped || armed.value)) emit('confirm')
}
</script>
