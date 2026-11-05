<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref, watch } from 'vue'
import FormField from '@/components/FormField.vue'

interface SmtpForm {
  smtpHost: string
  smtpPort: number
  smtpSenderAddress: string
  smtpUsername: string
  smtpPassword: string | null
  smtpUseTls: boolean
}

interface SmtpSettings {
  smtpPasswordConfigured?: boolean
}

interface SmtpTestResult {
  ok: boolean
  message: string
}

const props = withDefaults(defineProps<{
  form: SmtpForm
  settings?: SmtpSettings | null
  // Unsaved SMTP edits: the test sends with the *saved* settings, so it is
  // disabled until they are saved.
  dirty?: boolean
  testing?: boolean
  testResult?: SmtpTestResult | null
  // Pre-fills the test recipient (the signed-in user's email, when known).
  defaultRecipient?: string | null
}>(), {
  settings: null,
  dirty: false,
  testing: false,
  testResult: null,
  defaultRecipient: null,
})
const emit = defineEmits<{ test: [recipient: string] }>()

const testRecipient = ref<string>(props.defaultRecipient ?? '')
watch(() => props.defaultRecipient, (v) => {
  if (!testRecipient.value && v) testRecipient.value = v
})

function sendTest(): void {
  const recipient = testRecipient.value.trim()
  if (recipient) emit('test', recipient)
}
</script>

<template>
  <section class="bg-white border border-gray-200 rounded-xl p-6">
    <h2 class="text-base font-semibold text-gray-900 mb-1">SMTP (Email Delivery)</h2>
    <p class="text-xs text-gray-500 mb-4">
      Host and sender address are required to send email (notifications and scheduled reports).
      Username and password are only needed if the server requires authentication.
      Leave every field blank to disable email.
    </p>
    <div class="grid grid-cols-2 gap-3">
      <FormField label="SMTP Host" v-model="form.smtpHost" placeholder="smtp.example.com" required />
      <FormField label="SMTP Port" v-model.number="form.smtpPort" type="number" placeholder="587"
                 hint="Defaults to 587. Use 465 for implicit TLS." />
      <FormField label="Sender Address" v-model="form.smtpSenderAddress" type="email"
                 placeholder="noreply@example.com" required />
      <FormField label="Username" v-model="form.smtpUsername" placeholder="Optional" autocomplete="off" />
      <div class="col-span-2">
        <FormField
          label="Password"
          :model-value="form.smtpPassword ?? ''"
          @update:model-value="form.smtpPassword = $event"
          type="password"
          autocomplete="new-password"
          :placeholder="settings?.smtpPasswordConfigured ? '●●●●●●●● (leave blank to keep)' : 'Optional'"
        />
        <p class="text-xs text-gray-500 mt-1">
          Leave blank to keep existing{{ settings?.smtpPasswordConfigured ? '' : ' (none set)' }}.
          Enter a space to clear.
        </p>
      </div>
      <div class="col-span-2">
        <div class="flex items-center gap-2">
          <input type="checkbox" id="smtpTls" v-model="form.smtpUseTls" class="rounded" />
          <label for="smtpTls" class="text-sm text-gray-700">Use TLS / STARTTLS</label>
        </div>
        <p class="text-xs text-gray-500 mt-1">
          Encrypts the connection before credentials or mail are sent: STARTTLS, or implicit TLS on port 465.
          If the server does not offer TLS the send fails rather than falling back to cleartext.
        </p>
      </div>
    </div>

    <!-- Test email -->
    <div class="border-t border-gray-200 mt-5 pt-4">
      <h3 class="text-sm font-semibold text-gray-900 mb-2">Send Test Email</h3>
      <div class="flex items-end gap-3">
        <div class="flex-1">
          <FormField label="Recipient" v-model="testRecipient" type="email" placeholder="you@example.com" />
        </div>
        <button type="button" @click="sendTest" :disabled="testing || dirty || !testRecipient.trim()"
                class="btn-secondary text-sm mb-2">
          {{ testing ? 'Sending…' : 'Send Test Email' }}
        </button>
      </div>
      <p v-if="dirty" class="text-xs text-amber-600">Save your changes first — the test uses the saved settings.</p>
      <p v-if="testResult" class="text-sm" role="status"
         :class="testResult.ok ? 'text-green-600' : 'text-red-600'">
        {{ testResult.message }}
      </p>
    </div>
  </section>
</template>
