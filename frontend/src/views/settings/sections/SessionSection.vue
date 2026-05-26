<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import FormField from '@/components/FormField.vue'

defineProps({
  form: { type: Object, required: true },
})
</script>

<template>
  <section class="bg-white border border-gray-200 rounded-xl p-6">
    <h2 class="text-base font-semibold text-gray-900 mb-3">Session</h2>
    <div class="max-w-xs">
      <FormField label="Session Timeout (minutes)" v-model.number="form.sessionTimeoutMinutes" type="number" required />
    </div>
  </section>
</template>
