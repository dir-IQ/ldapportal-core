<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import FormField from '@/components/FormField.vue'

defineProps({
  form:     { type: Object, required: true },
  settings: { type: Object, default: null },
})
</script>

<template>
  <section class="bg-white border border-gray-200 rounded-xl p-6">
    <h2 class="text-base font-semibold text-gray-900 mb-3">SMTP (Email Delivery)</h2>
    <div class="grid grid-cols-2 gap-3">
      <FormField label="SMTP Host" v-model="form.smtpHost" placeholder="smtp.example.com" />
      <FormField label="SMTP Port" v-model.number="form.smtpPort" type="number" placeholder="587" />
      <FormField label="Sender Address" v-model="form.smtpSenderAddress" placeholder="noreply@example.com" />
      <FormField label="Username" v-model="form.smtpUsername" placeholder="username" autocomplete="off" />
      <div class="col-span-2">
        <FormField
          label="Password"
          v-model="form.smtpPassword"
          type="password"
          autocomplete="new-password"
          :placeholder="settings?.smtpPasswordConfigured ? '●●●●●●●● (leave blank to keep)' : 'Set password'"
        />
        <p class="text-xs text-gray-500 mt-1">
          Leave blank to keep existing{{ settings?.smtpPasswordConfigured ? '' : ' (none set)' }}.
          Enter a space to clear.
        </p>
      </div>
      <div class="flex items-center gap-2">
        <input type="checkbox" id="smtpTls" v-model="form.smtpUseTls" class="rounded" />
        <label for="smtpTls" class="text-sm text-gray-700">Use TLS / STARTTLS</label>
      </div>
    </div>
  </section>
</template>
