// SPDX-License-Identifier: Apache-2.0
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * Composable for checking feature-level permissions in Vue components.
 * Uses the features array returned by /auth/me and stored in the auth store.
 */
export function usePermissions() {
  const auth = useAuthStore()

  const hasFeature = (featureDbValue) => auth.hasFeature(featureDbValue)

  return {
    hasFeature,
    canManageAccessReviews: computed(() => auth.hasFeature('access_review.manage')),
    canReviewAccess:        computed(() => auth.hasFeature('access_review.review')),
    canManageAuditorLinks:  computed(() => auth.hasFeature('auditor.manage')),
    canRunReports:          computed(() => auth.hasFeature('reports.run')),
    canManageSod:           computed(() => auth.hasFeature('sod.manage')),
  }
}
