// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Controls the global UpgradeModal — the dialog shown when a backend
 * 402 response tells the user they've hit a license limit (Phase 7)
 * or need a higher tier to access a feature (entitlement-missing, a
 * future router-guard hook in Phase 8).
 *
 * Triggered by the axios 402 interceptor; shown by AppLayout's mounted
 * UpgradeModal component.
 *
 * Shape of {@code params} aligns with the ProblemDetail emitted by
 * {@code GlobalExceptionHandler}:
 *
 *   code: 'LIMIT_EXCEEDED'       — limit hit
 *     limitType, currentCount, maximum, currentEdition
 *   code: 'ENTITLEMENT_MISSING'  — tier doesn't include the feature
 *     entitlement, currentEdition
 */
export const useUpgradeModalStore = defineStore('upgradeModal', () => {
  const visible = ref(false)
  const params = ref(null)

  function show(newParams) {
    params.value = newParams
    visible.value = true
  }

  function hide() {
    visible.value = false
    // Keep `params` populated briefly so the closing animation can
    // still read them. Next show() replaces them anyway.
  }

  return { visible, params, show, hide }
})
