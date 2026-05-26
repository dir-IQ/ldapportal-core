<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import FormField from '@/components/FormField.vue'

defineProps({
  form: { type: Object, required: true },
})
</script>

<template>
  <section class="bg-white border border-gray-200 rounded-xl p-6">
    <h2 class="text-base font-semibold text-gray-900 mb-3">Branding</h2>
    <div class="grid grid-cols-2 gap-3">
      <FormField label="Application Name" v-model="form.appName" required />
      <FormField label="Logo URL" v-model="form.logoUrl" placeholder="https://…/logo.png" />
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">Primary Colour</label>
        <div class="flex items-center gap-2">
          <input type="color" aria-label="Primary colour picker" v-model="form.primaryColour" class="h-9 w-10 rounded border border-gray-300 cursor-pointer p-0.5" />
          <input type="text" aria-label="Primary colour hex value" v-model="form.primaryColour" placeholder="#1e293b" class="input flex-1" />
        </div>
      </div>
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">Secondary Colour</label>
        <div class="flex items-center gap-2">
          <input type="color" aria-label="Secondary colour picker" v-model="form.secondaryColour" class="h-9 w-10 rounded border border-gray-300 cursor-pointer p-0.5" />
          <input type="text" aria-label="Secondary colour hex value" v-model="form.secondaryColour" placeholder="#1e293b" class="input flex-1" />
        </div>
      </div>
    </div>
  </section>
</template>
