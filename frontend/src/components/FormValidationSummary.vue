<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Top-of-form validation summary. Render at the top of a data-entry form and
  feed it the `summary` from useFormErrors(); it announces (role="alert") the
  fields that need fixing so an error on a scrolled-out field is never missed.
-->
<template>
  <div
    v-if="errors.length"
    role="alert"
    aria-live="assertive"
    class="mb-4 rounded-lg border border-red-300 bg-red-50 px-4 py-3"
  >
    <p class="text-sm font-semibold text-red-800">
      Please fix {{ errors.length }} field{{ errors.length === 1 ? '' : 's' }} before saving:
    </p>
    <ul class="mt-1.5 list-disc pl-5 space-y-0.5">
      <li v-for="e in errors" :key="e.key" class="text-sm text-red-700">
        <span class="font-medium">{{ e.label }}</span>: {{ e.message }}
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import type { FormErrorEntry } from '@/composables/useFormErrors'

defineProps<{ errors: FormErrorEntry[] }>()
</script>
