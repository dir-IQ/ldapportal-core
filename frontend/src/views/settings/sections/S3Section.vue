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
    <h2 class="text-base font-semibold text-gray-900 mb-3">S3 (Report Storage)</h2>
    <div class="grid grid-cols-2 gap-3">
      <FormField label="Endpoint URL" v-model="form.s3EndpointUrl" placeholder="https://s3.amazonaws.com" />
      <FormField label="Bucket Name" v-model="form.s3BucketName" placeholder="my-reports-bucket" />
      <FormField label="Access Key" v-model="form.s3AccessKey" placeholder="AKIAIOSFODNN7EXAMPLE" />
      <div>
        <FormField
          label="Secret Key"
          v-model="form.s3SecretKey"
          type="password"
          autocomplete="new-password"
          :placeholder="settings?.s3SecretKeyConfigured ? '●●●●●●●● (leave blank to keep)' : 'Set secret key'"
        />
        <p class="text-xs text-gray-500 mt-1">Leave blank to keep existing. Enter a space to clear.</p>
      </div>
      <FormField label="Region" v-model="form.s3Region" placeholder="us-east-1" />
      <FormField label="Pre-signed URL TTL (hours)" v-model.number="form.s3PresignedUrlTtlHours" type="number" placeholder="24" />
    </div>
  </section>
</template>
