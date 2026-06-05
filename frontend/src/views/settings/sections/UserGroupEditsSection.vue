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

    <div class="border-t border-gray-100 pt-5">
      <h3 class="text-sm font-semibold text-gray-900 mb-1">Approval workflows</h3>
      <p class="text-xs text-gray-500 mb-3">
        Global master switches. Turning these off overrides — but never changes —
        any per-profile approval settings; they resume if turned back on.
      </p>

      <div>
        <div class="flex items-center gap-2">
          <input
            type="checkbox"
            id="approvalsEnabled"
            v-model="form.approvalsEnabled"
            class="rounded"
          />
          <label for="approvalsEnabled" class="text-sm text-gray-700">
            Require approvals for user &amp; group operations
          </label>
        </div>
        <p class="text-xs text-gray-500 mt-1">
          Governs create, move, delete, bulk import, group membership, and playbook
          execution. When off, these proceed immediately regardless of per-profile
          configuration, and the related approval UI (nav, dashboard, profile
          approval settings) is hidden.
        </p>
      </div>

      <div class="mt-4">
        <div class="flex items-center gap-2">
          <input
            type="checkbox"
            id="selfRegistrationApprovalEnabled"
            v-model="form.selfRegistrationApprovalEnabled"
            class="rounded"
          />
          <label for="selfRegistrationApprovalEnabled" class="text-sm text-gray-700">
            Require approval for self-service registration
          </label>
        </div>
        <p class="text-xs text-gray-500 mt-1">
          When off, verified self-registrations are provisioned without review.
        </p>
      </div>

      <!-- Warn-on-disable: in-flight requests aren't executed or discarded;
           they remain reachable by superadmins until approvals resume. -->
      <p
        v-if="!form.approvalsEnabled || !form.selfRegistrationApprovalEnabled"
        class="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1.5 mt-4"
      >
        Any approval requests already pending are neither approved nor rejected by
        turning approvals off — they stay queued and remain reachable from the
        superadmin Approvals view so they can be drained.
      </p>
    </div>
  </section>
</template>
