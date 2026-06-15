// SPDX-License-Identifier: Apache-2.0
/**
 * Deployment-skew detector.
 *
 * On first call, fetches the backend's running build identifier from
 * {@code GET /api/v1/version} and compares it to the bundle's own
 * {@code __BUILD_SHA__} (injected at build time by `vite.config.js`).
 * Exposes a reactive {@code skewDetected} boolean that AppLayout uses
 * to render the orange "client/server out of sync" banner.
 *
 * The fetch is shared across all callers via module-scoped state so the
 * skew check happens exactly once per page load, not per component
 * mount. The endpoint is permitall on the backend so this works even
 * before login (banner shows on the login page too — a user sitting on
 * a stale tab from yesterday should know it's stale before they try to
 * sign in).
 *
 * Failure modes are intentionally non-blocking:
 *  - 404 (old backend without the endpoint) → flagged as skew so the
 *    user knows something is off, with a "server doesn't expose its
 *    build identifier" hint.
 *  - 5xx / network error → silently ignored. We don't want a flaky
 *    health check to scare users; real downtime is surfaced by other
 *    interactions.
 */
import { ref } from 'vue'
import client from '@/api/client'

const clientSha = __BUILD_SHA__
const serverSha = ref<string | null>(null)
const skewDetected = ref(false)
const checkAttempted = ref(false)

async function performCheck(): Promise<void> {
  if (checkAttempted.value) return
  checkAttempted.value = true
  try {
    const { data } = await client.get<{ sha?: string }>('/version')
    const sha = data?.sha ?? 'unknown'
    serverSha.value = sha
    // Treat the dev-build sentinel as a non-event — running vite dev
    // against a non-packaged backend is the normal local workflow,
    // banner would just be noise.
    if (clientSha === 'dev' || sha === 'dev') return
    if (sha !== clientSha) skewDetected.value = true
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status
    if (status === 404) {
      // Backend predates the version endpoint → skewed by definition.
      serverSha.value = 'unavailable'
      skewDetected.value = true
    }
    // Network / 5xx → swallow. Real downtime surfaces elsewhere.
  }
}

export function useVersionCheck() {
  // Fire-and-forget on first invocation; subsequent calls reuse cached state.
  performCheck()
  return { clientSha, serverSha, skewDetected }
}
