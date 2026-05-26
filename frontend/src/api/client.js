// SPDX-License-Identifier: Apache-2.0
import axios from 'axios'

const client = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true, // send the httpOnly JWT cookie on every request
})

// On 401 redirect to the appropriate login page.
// On 402 (license: missing entitlement / limit exceeded) surface the
// global UpgradeModal so users see why their action was rejected.
client.interceptors.response.use(
  res => res,
  err => {
    const status = err.response?.status
    if (status === 401) {
      const path = window.location.pathname
      // Don't redirect if already on a login page
      if (path !== '/login' && path !== '/self-service/login') {
        // Self-service users go to self-service login; admins go to admin login
        if (path.startsWith('/self-service')) {
          window.location.href = '/self-service/login'
        } else if (!path.startsWith('/register')) {
          window.location.href = '/login'
        }
      }
    } else if (status === 402) {
      // Lazy import to avoid a pinia ↔ axios circular dep at module load
      // and to keep this interceptor a pure function of the response.
      // The store is only needed when we actually see a 402.
      const body = err.response.data || {}
      const code = body.code
      if (code === 'LIMIT_EXCEEDED' || code === 'ENTITLEMENT_MISSING') {
        import('@/stores/upgradeModal').then(({ useUpgradeModalStore }) => {
          try {
            useUpgradeModalStore().show(body)
          } catch (_e) {
            // Pinia not yet initialized — nothing we can do; the caller
            // still gets the rejected promise and can surface a toast.
          }
        })
      }
    }
    return Promise.reject(err)
  }
)

export default client
