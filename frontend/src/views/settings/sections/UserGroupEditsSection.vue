<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
defineProps({
  form: { type: Object, required: true },
})
</script>

<template>
  <section class="bg-white border border-gray-200 rounded-xl p-6 space-y-5">
    <div>
      <h2 class="text-base font-semibold text-gray-900 mb-1">User/Group Edits</h2>
      <p class="text-xs text-gray-500">
        Controls the inline edit affordance on Directory Search results.
      </p>
    </div>

    <div>
      <div class="flex items-center gap-2">
        <input
          type="checkbox"
          id="directorySearchInlineEditEnabled"
          v-model="form.directorySearchInlineEditEnabled"
          class="rounded"
        />
        <label for="directorySearchInlineEditEnabled" class="text-sm text-gray-700">
          Allow inline editing on Directory Search results
        </label>
      </div>
      <p class="text-xs text-gray-500 mt-1">
        When enabled, the Directory Search results table shows an "Edit
        results" toggle that switches eligible cells into editable
        inputs (saves on row blur). When disabled the affordance is
        hidden; the per-entity edit flows under Users and Groups stay
        unaffected.
      </p>
    </div>
  </section>
</template>
