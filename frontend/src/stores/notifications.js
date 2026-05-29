// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref } from 'vue'

let nextId = 1

const MAX_VISIBLE = 5

export const useNotificationStore = defineStore('notifications', () => {
  const items = ref([])

  /**
   * Push a toast.
   *
   * @param {string} type - 'success' | 'error' | 'info' | 'warning'
   * @param {string} message
   * @param {object} [opts]
   * @param {number} [opts.duration]  Auto-dismiss after N ms. Pass 0 to make
   *                                  the toast sticky — close button only.
   *                                  Errors default to 0 (must-acknowledge).
   * @param {string|null} [opts.detail]
   * @param {{label: string, fn: Function}|null} [opts.action]
   */
  function push(type, message, { duration = 4000, detail = null, action = null } = {}) {
    const id = nextId++
    const item = { id, type, message, detail, action, duration, remaining: duration }
    items.value.push(item)

    // Countdown for the visible progress bar — only for auto-dismissing toasts.
    // duration=0 means sticky: no timer, no countdown, dismissed only by the
    // close button or an action click.
    if (duration > 0) {
      const interval = 50
      const timer = setInterval(() => {
        const idx = items.value.findIndex(n => n.id === id)
        if (idx === -1) { clearInterval(timer); return }
        items.value[idx].remaining = Math.max(0, items.value[idx].remaining - interval)
        if (items.value[idx].remaining <= 0) {
          clearInterval(timer)
          remove(id)
        }
      }, interval)
    }

    // Cap at MAX_VISIBLE. When over-cap, drop the oldest NON-ERROR first so
    // unacknowledged errors aren't silently scrolled off by a flurry of
    // success/info toasts. Only fall back to dropping the oldest error if
    // every visible toast is an error.
    while (items.value.length > MAX_VISIBLE) {
      const dropIdx = items.value.findIndex(n => n.type !== 'error')
      items.value.splice(dropIdx === -1 ? 0 : dropIdx, 1)
    }

    return id
  }

  // Errors default to a 2-minute auto-dismiss — long enough that the
  // operator has time to read and react (much longer than the 4s
  // default for success/info), short enough that a stale error doesn't
  // pile up across a session. Caller can still opt into other
  // behaviour: pass {duration: 0} for sticky (close-button only), or
  // a custom duration in ms for transient errors.
  const ERROR_TOAST_MS = 2 * 60 * 1000  // 2 minutes
  const success = (msg, opts) => push('success', msg, typeof opts === 'object' ? opts : undefined)
  const error   = (msg, opts) => push('error',   msg, { duration: ERROR_TOAST_MS, ...(typeof opts === 'object' ? opts : {}) })
  const info    = (msg, opts) => push('info',    msg, typeof opts === 'object' ? opts : undefined)
  const warning = (msg, opts) => push('warning', msg, typeof opts === 'object' ? opts : undefined)

  function remove(id) {
    items.value = items.value.filter(n => n.id !== id)
  }

  return { items, success, error, info, warning, remove }
})
