<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { computed, onMounted } from 'vue'
import { useLicenseStore } from '@/stores/license'

const license = useLicenseStore()
const s = computed(() => license.status)

onMounted(() => license.ensureLoaded())

function refresh() {
  license.refresh()
}

function formatDate(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' })
}

const daysRemainingLabel = computed(() => {
  const n = s.value?.daysRemaining
  if (n == null) return ''
  if (n < 0) return `${Math.abs(n)} day${Math.abs(n) === 1 ? '' : 's'} ago`
  if (n === 0) return 'today'
  return `in ${n} day${n === 1 ? '' : 's'}`
})
</script>

<template>
  <section class="space-y-4">
    <div v-if="license.loading && !license.status" class="text-sm text-gray-500">
      Loading license status…
    </div>

    <div
      v-else-if="license.error"
      class="rounded bg-red-50 border border-red-200 p-4"
    >
      <p class="font-medium text-red-800">Failed to load license status</p>
      <p class="text-sm text-red-700 mt-1">{{ license.error }}</p>
      <button @click="refresh" class="mt-3 btn-secondary btn-compact">Retry</button>
    </div>

    <template v-else-if="s">
      <!-- Grace-state banner -->
      <div
        v-if="license.bannerLevel"
        :class="[
          'rounded border p-4',
          license.bannerLevel === 'error'
            ? 'bg-red-50 border-red-200 text-red-800'
            : license.bannerLevel === 'warning'
            ? 'bg-amber-50 border-amber-200 text-amber-800'
            : 'bg-blue-50 border-blue-200 text-blue-800',
        ]"
      >
        <p class="font-medium">{{ license.bannerMessage }}</p>
      </div>

      <!-- No-license callout -->
      <div
        v-if="!s.signed"
        class="rounded bg-gray-50 border border-gray-200 p-4 text-sm text-gray-700"
      >
        <p class="font-medium text-gray-900">No signed license file installed.</p>
        <p class="mt-1">
          Running with community baseline entitlements. To unlock licensed
          features, set
          <code class="text-xs bg-gray-100 px-1 py-0.5 rounded">ldapportal.license.path</code>
          to the path of a signed JWT and restart.
        </p>
      </div>

      <!-- Summary -->
      <div class="bg-white border border-gray-200 rounded-xl p-6">
        <div class="flex items-start justify-between mb-5">
          <h2 class="text-base font-semibold text-gray-900">License summary</h2>
          <button
            @click="refresh"
            :disabled="license.loading"
            class="btn-secondary btn-compact"
          >
            {{ license.loading ? 'Refreshing…' : 'Refresh' }}
          </button>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-3 gap-5">
          <div>
            <p class="text-xs uppercase text-gray-500 tracking-wide">Edition</p>
            <p class="text-lg font-semibold text-gray-900 mt-1">{{ s.edition }}</p>
          </div>

          <div>
            <p class="text-xs uppercase text-gray-500 tracking-wide">Customer ID</p>
            <p class="text-sm font-mono text-gray-800 mt-1 break-all">
              {{ s.customerId || '—' }}
            </p>
          </div>

          <div>
            <p class="text-xs uppercase text-gray-500 tracking-wide">Source</p>
            <p class="text-sm text-gray-800 mt-1 break-all">{{ s.source }}</p>
          </div>

          <div v-if="s.issuedAt">
            <p class="text-xs uppercase text-gray-500 tracking-wide">Issued</p>
            <p class="text-sm text-gray-800 mt-1">{{ formatDate(s.issuedAt) }}</p>
          </div>

          <div v-if="s.expiresAt">
            <p class="text-xs uppercase text-gray-500 tracking-wide">Expires</p>
            <p class="text-sm text-gray-800 mt-1">
              {{ formatDate(s.expiresAt) }}
              <span class="text-xs text-gray-500 ml-1">({{ daysRemainingLabel }})</span>
            </p>
          </div>

          <div>
            <p class="text-xs uppercase text-gray-500 tracking-wide">Signed</p>
            <p class="text-sm mt-1">
              <span :class="s.signed ? 'badge-green' : 'badge-gray'">
                {{ s.signed ? 'Yes' : 'No (community baseline)' }}
              </span>
            </p>
          </div>
        </div>
      </div>

      <!-- Entitlements -->
      <div class="bg-white border border-gray-200 rounded-xl p-6">
        <h2 class="text-base font-semibold text-gray-900 mb-4">Entitlements</h2>

        <div>
          <p class="text-xs uppercase text-gray-500 tracking-wide mb-2">Granted</p>
          <div v-if="s.grantedEntitlements.length === 0" class="text-sm text-gray-500">
            None
          </div>
          <ul v-else class="space-y-1">
            <li
              v-for="e in s.grantedEntitlements"
              :key="e"
              class="text-sm text-gray-800 flex items-center"
            >
              <span class="text-green-600 mr-2">✓</span>{{ e }}
              <span
                v-if="s.addOns.includes(e)"
                class="ml-2 text-xs text-blue-600"
                title="Add-on entitlement (not included in edition baseline)"
              >
                add-on
              </span>
            </li>
          </ul>
        </div>
      </div>

      <!-- Limits -->
      <div class="bg-white border border-gray-200 rounded-xl p-6">
        <h2 class="text-base font-semibold text-gray-900 mb-4">Resource limits</h2>
        <div v-if="Object.keys(s.limits).length === 0" class="text-sm text-gray-500">
          No limits — every resource type is unlimited under this license.
        </div>
        <table v-else class="text-sm w-full max-w-md">
          <tbody>
            <tr
              v-for="(value, name) in s.limits"
              :key="name"
              class="border-b last:border-b-0"
            >
              <td class="py-2 pr-4 text-gray-600">{{ name }}</td>
              <td class="py-2 font-mono text-gray-900 text-right">{{ value.toLocaleString() }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>
