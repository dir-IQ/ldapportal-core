// SPDX-License-Identifier: Apache-2.0
import axios from 'axios'
import { withBase } from '@/utils/basePath'

// The API lives next to the SPA under the same public base path ("/api/v1"
// at the root, "/idm/api/v1" behind a path-prefixed proxy), so the two
// always share an origin and the session cookie's Path.
const client = axios.create({
  baseURL: withBase('api/v1'),
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true, // send the httpOnly JWT cookie on every request
})

// On 401 redirect to the appropriate login page. A request may opt out with
// `{ skipAuthRedirect: true }` in its axios config — used by the session
// probes (/auth/me on boot, the WebSEAL pre-auth probe) where a 401 is an
// expected answer the caller handles itself, not a session expiry. Without
// the opt-out, the router's own redirect (which preserves the deep link in
// `?redirect=`) loses the race to this hard navigation.
// On 402 (license: missing entitlement / limit exceeded) surface the
// global UpgradeModal so users see why their action was rejected.
client.interceptors.response.use(
  res => res,
  err => {
    const status = err.response?.status
    if (status === 401 && !err.config?.skipAuthRedirect) {
      const path = window.location.pathname
      const loginPath = withBase('login')
      const selfServiceLoginPath = withBase('self-service/login')
      // Don't redirect if already on a login page
      if (path !== loginPath && path !== selfServiceLoginPath) {
        // Self-service users go to self-service login; admins go to admin login
        if (path.startsWith(withBase('self-service'))) {
          window.location.href = selfServiceLoginPath
        } else if (!path.startsWith(withBase('register'))) {
          window.location.href = loginPath
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
