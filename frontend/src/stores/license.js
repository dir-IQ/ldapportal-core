// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getLicenseStatus } from '@/api/license'

/**
 * Single source of truth for the current license status.
 *
 * Fetched once on first access (usually by the license page or by the
 * app-wide ExpirationBanner) and cached in memory until the tab
 * reloads. The license changes only on restart, so there's no value in
 * polling — but callers can {@code refresh()} after a known change
 * (re-install, renewal).
 *
 * Settings-derived licenses report graceState="NO_EXPIRY" and the
 * banner stays hidden. Signed licenses within 30 days of expiry,
 * inside grace, or past grace each surface a distinct banner level.
 */
export const useLicenseStore = defineStore('license', () => {
  const status = ref(null)
  const loading = ref(false)
  const error = ref(null)

  async function fetch() {
    if (loading.value) return
    loading.value = true
    error.value = null
    try {
      const res = await getLicenseStatus()
      status.value = res.data
    } catch (e) {
      error.value = e?.response?.data?.message || e?.message || 'Failed to load license status'
      status.value = null
    } finally {
      loading.value = false
    }
  }

  /** Lazy fetch — no-op if status is already loaded. */
  async function ensureLoaded() {
    if (status.value === null && !loading.value) await fetch()
  }

  /** Force re-fetch after a known state change (e.g. license file replaced). */
  async function refresh() {
    status.value = null
    await fetch()
  }

  // ── derived banner state ─────────────────────────────────────────────────
  //
  // The banner shows only when the license is in a state operators need to
  // see at a glance. VALID and NO_EXPIRY produce no banner.

  const bannerLevel = computed(() => {
    const g = status.value?.graceState
    if (g === 'PAST_GRACE') return 'error'
    if (g === 'EXPIRED_WITHIN_GRACE') return 'warning'
    if (g === 'APPROACHING_EXPIRY') return 'info'
    return null
  })

  const bannerMessage = computed(() => {
    if (!status.value) return null
    const days = status.value.daysRemaining
    switch (status.value.graceState) {
      case 'PAST_GRACE':
        return `License expired ${Math.abs(days)} day${Math.abs(days) === 1 ? '' : 's'} ago and is past the ${status.value.graceDays}-day grace period. Renew now.`
      case 'EXPIRED_WITHIN_GRACE':
        return `License expired ${Math.abs(days)} day${Math.abs(days) === 1 ? '' : 's'} ago. Within the ${status.value.graceDays}-day grace period — renew soon.`
      case 'APPROACHING_EXPIRY':
        return `License expires in ${days} day${days === 1 ? '' : 's'}. Plan to renew.`
      default:
        return null
    }
  })

  return {
    status,
    loading,
    error,
    fetch,
    ensureLoaded,
    refresh,
    bannerLevel,
    bannerMessage,
  }
})
