// SPDX-License-Identifier: Apache-2.0
import { ref, onUnmounted } from 'vue'

const UNITS = [
  { max: 60, divisor: 1, unit: 'second' },
  { max: 3600, divisor: 60, unit: 'minute' },
  { max: 86400, divisor: 3600, unit: 'hour' },
  { max: 2592000, divisor: 86400, unit: 'day' },
  { max: 31536000, divisor: 2592000, unit: 'month' },
  { max: Infinity, divisor: 31536000, unit: 'year' },
]

/**
 * Format a date value as a relative time string ("2 hours ago", "in 3 days").
 */
export function formatRelativeTime(value) {
  if (!value) return ''
  const date = value instanceof Date ? value : new Date(value)
  if (isNaN(date.getTime())) return ''
  const diffSec = Math.round((date.getTime() - Date.now()) / 1000)
  const absDiff = Math.abs(diffSec)

  for (const { max, divisor, unit } of UNITS) {
    if (absDiff < max) {
      const n = Math.round(absDiff / divisor)
      try {
        return new Intl.RelativeTimeFormat('en', { numeric: 'auto' }).format(
          diffSec < 0 ? -n : n,
          unit,
        )
      } catch {
        return diffSec < 0 ? `${n} ${unit}${n !== 1 ? 's' : ''} ago` : `in ${n} ${unit}${n !== 1 ? 's' : ''}`
      }
    }
  }
  return ''
}

/**
 * Format a date value as a full locale string for tooltips.
 */
export function formatAbsoluteTime(value) {
  if (!value) return ''
  const date = value instanceof Date ? value : new Date(value)
  if (isNaN(date.getTime())) return ''
  return date.toLocaleString()
}

/**
 * Composable that returns a reactive relative time string that auto-updates.
 */
export function useRelativeTime(getValue, intervalMs = 60000) {
  const text = ref(formatRelativeTime(typeof getValue === 'function' ? getValue() : getValue))

  const timer = setInterval(() => {
    text.value = formatRelativeTime(typeof getValue === 'function' ? getValue() : getValue)
  }, intervalMs)

  onUnmounted(() => clearInterval(timer))

  return text
}
