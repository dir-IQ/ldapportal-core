<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import FormField from '@/components/FormField.vue'

defineProps({
  form:       { type: Object,  required: true },
  settings:   { type: Object,  default: null },
  testing:    { type: Boolean, default: false },
  testResult: { type: Object,  default: null },
})
defineEmits(['test'])
</script>

<template>
  <section class="bg-white border border-gray-200 rounded-xl p-6">
    <h2 class="text-base font-semibold text-gray-900 mb-3">SIEM / Syslog Export</h2>
    <p class="text-xs text-gray-500 mb-4">
      Forward audit events in real-time to a SIEM, syslog collector, or webhook endpoint.
    </p>

    <div class="flex items-center gap-2 mb-4">
      <input type="checkbox" id="siemEnabled" v-model="form.siemEnabled" class="rounded" />
      <label for="siemEnabled" class="text-sm text-gray-700">Enable SIEM export</label>
    </div>

    <div v-if="form.siemEnabled" class="space-y-4">
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label for="set-siem-protocol" class="block text-sm font-medium text-gray-700 mb-1">Protocol</label>
          <select id="set-siem-protocol" v-model="form.siemProtocol" class="input w-full">
            <option value="">-- select --</option>
            <option value="SYSLOG_UDP">Syslog (UDP)</option>
            <option value="SYSLOG_TCP">Syslog (TCP)</option>
            <option value="SYSLOG_TLS">Syslog (TLS / RFC 5425)</option>
            <option value="WEBHOOK">Webhook (HTTPS)</option>
          </select>
        </div>
        <div>
          <label for="set-siem-format" class="block text-sm font-medium text-gray-700 mb-1">Format</label>
          <select id="set-siem-format" v-model="form.siemFormat" class="input w-full">
            <option value="">-- select --</option>
            <option value="RFC5424">RFC 5424 (Syslog)</option>
            <option value="CEF">CEF (Common Event Format)</option>
            <option value="LEEF">LEEF (IBM QRadar)</option>
            <option value="JSON">JSON</option>
          </select>
        </div>
      </div>

      <!-- Syslog host/port (shown for SYSLOG_UDP, SYSLOG_TCP, SYSLOG_TLS) -->
      <div v-if="form.siemProtocol === 'SYSLOG_UDP' || form.siemProtocol === 'SYSLOG_TCP' || form.siemProtocol === 'SYSLOG_TLS'"
           class="grid grid-cols-2 gap-3">
        <FormField label="Host" v-model="form.siemHost" placeholder="siem.example.com" />
        <FormField label="Port" v-model.number="form.siemPort" type="number"
                   :placeholder="form.siemProtocol === 'SYSLOG_TLS' ? '6514' : '514'" />
        <p v-if="form.siemProtocol === 'SYSLOG_TLS'" class="col-span-2 text-xs text-gray-500">
          TLS syslog (RFC 5425) uses the JVM's default trust store.
          To trust custom CAs, add them to the JVM cacerts.
        </p>
      </div>

      <!-- Webhook URL (shown for WEBHOOK) -->
      <div v-if="form.siemProtocol === 'WEBHOOK'" class="grid grid-cols-2 gap-3">
        <div class="col-span-2">
          <FormField label="Webhook URL" v-model="form.webhookUrl" placeholder="https://hooks.example.com/audit" />
        </div>
        <div class="col-span-2">
          <FormField label="Authorization Header" v-model="form.webhookAuthHeader"
                     type="password" autocomplete="new-password"
                     :placeholder="settings?.webhookAuthHeaderConfigured ? '●●●●●●●● (leave blank to keep)' : 'e.g. Bearer your-token'" />
          <p class="text-xs text-gray-500 mt-1">Sent as the Authorization header on each webhook request.</p>
        </div>
      </div>

      <!-- Test button -->
      <div class="flex items-center gap-3 pt-2">
        <button type="button" @click="$emit('test')" :disabled="testing" class="btn-secondary text-sm">
          {{ testing ? 'Testing…' : 'Test Connection' }}
        </button>
        <span v-if="testResult" class="text-sm" :class="testResult.ok ? 'text-green-600' : 'text-red-600'">
          {{ testResult.message }}
        </span>
      </div>
    </div>
  </section>
</template>
