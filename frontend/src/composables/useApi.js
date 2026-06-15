// SPDX-License-Identifier: Apache-2.0
import { ref } from 'vue'
import { useNotificationStore } from '@/stores/notifications'

/**
 * Wraps an async API call with loading/error state and optional toast notification.
 */
export function useApi() {
  const loading = ref(false)
  const error   = ref(null)

  async function call(fn, { successMsg } = {}) {
    loading.value = true
    error.value   = null
    try {
      const result = await fn()
      if (successMsg) {
        const notif = useNotificationStore()
        notif.success(successMsg)
      }
      return result
    } catch (err) {
      const msg = err.response?.data?.detail
            || err.response?.data?.message
            || err.message
            || 'An unexpected error occurred'
      error.value = msg
      const notif = useNotificationStore()
      notif.error(msg)
      throw err
    } finally {
      loading.value = false
    }
  }

  return { loading, error, call }
}

/** Download a blob response as a file. */
export function downloadBlob(blobData, filename) {
  const url = URL.createObjectURL(blobData)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
