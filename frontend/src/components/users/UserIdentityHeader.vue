<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed } from 'vue'
import CopyButton from '@/components/CopyButton.vue'
import type { IsvaAccountStatus } from '@/api/isvaAccount'

// Identity-level header rendered above the UserForm tab strip. Shared
// across the Attributes / Groups / IVIA Account tabs — identity
// context doesn't change when you switch tabs.
//
// Last-login and created-by are intentionally omitted — LdapUser
// doesn't expose either today (OpenLDAP authTimestamp / AD lastLogon
// and createTimestamp need a backend extension). Captured as a P3.5
// follow-up; when those fields land, add them next to email / profile.

interface DemographicAttrs {
  // Lowercased-key map, matches how UserForm.vue stores attributes.
  [k: string]: string | string[] | undefined
}

const props = defineProps<{
  dn: string
  attributes: DemographicAttrs
  profileName?: string | null
  /** Demographic enabled flag — null when not yet loaded / not applicable. */
  enabled?: boolean | null
  /** IVIA-side snapshot. Null when the addon's off or the directory has
   *  no IVIA config — in that case the IVIA badge is hidden. */
  ivia?: IsvaAccountStatus | null
}>()

function firstValue(key: string): string | null {
  const v = props.attributes[key]
  if (Array.isArray(v)) return v.length ? v[0] : null
  return v ?? null
}

const displayName = computed<string>(() => {
  const v = firstValue('displayName')
    ?? firstValue('cn')
    ?? firstValue('uid')
    ?? firstValue('sAMAccountName')
  return v ?? '(unnamed)'
})

const email = computed<string | null>(() => firstValue('mail'))

const initials = computed<string>(() => {
  const name = displayName.value.trim()
  if (!name || name === '(unnamed)') return '?'
  const parts = name.split(/\s+/).filter(Boolean)
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase()
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase()
})

/**
 * Stable gradient per identity — derived from the DN so the same user
 * always gets the same avatar colour across sessions and across the
 * user-list / detail views. Cheap hash; cosmetic only.
 */
const avatarGradient = computed<string>(() => {
  const hues = hashToHues(props.dn)
  return `linear-gradient(135deg, hsl(${hues[0]}, 70%, 55%) 0%, hsl(${hues[1]}, 70%, 55%) 100%)`
})

function hashToHues(s: string): [number, number] {
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) >>> 0
  }
  const a = h % 360
  const b = (a + 60 + ((h >>> 8) % 120)) % 360
  return [a, b]
}

/** "Enabled" / "Disabled" demographic badge. Hidden when enabled
 *  isn't set (e.g. directories where the concept doesn't apply). */
const showEnabledBadge = computed<boolean>(
  () => props.enabled !== null && props.enabled !== undefined,
)

/** "IVIA linked / orphaned / suspended" — only rendered when an IVIA
 *  status snapshot is available (addon + directory IVIA enabled). */
type IviaBadge = { label: string; klass: string; dot: string }
const iviaBadge = computed<IviaBadge | null>(() => {
  const s = props.ivia
  if (!s) return null
  if (s.orphaned) return { label: 'IVIA orphaned', klass: 'badge-yellow', dot: 'bg-yellow-500' }
  if (!s.acctValid) return { label: 'IVIA suspended', klass: 'badge-red', dot: 'bg-red-500' }
  return { label: 'IVIA linked', klass: 'badge-blue', dot: 'bg-blue-500' }
})
</script>

<template>
  <div class="pb-4 mb-2 border-b border-gray-200">
    <div class="flex items-start gap-4">
      <div
        class="w-14 h-14 rounded-full flex items-center justify-center text-white font-bold text-lg flex-shrink-0"
        :style="{ backgroundImage: avatarGradient }"
        aria-hidden="true"
      >{{ initials }}</div>

      <div class="flex-1 min-w-0">
        <div class="flex items-center gap-2 flex-wrap">
          <h2 class="text-xl font-bold tracking-tight text-gray-900 truncate">{{ displayName }}</h2>
          <span v-if="showEnabledBadge" :class="enabled ? 'badge-green' : 'badge-red'">
            <span class="inline-block w-1.5 h-1.5 rounded-full mr-1"
                  :class="enabled ? 'bg-green-500' : 'bg-red-500'"></span>
            {{ enabled ? 'Enabled' : 'Disabled' }}
          </span>
          <span v-if="iviaBadge" :class="iviaBadge.klass">
            <span class="inline-block w-1.5 h-1.5 rounded-full mr-1" :class="iviaBadge.dot"></span>
            {{ iviaBadge.label }}
          </span>
        </div>

        <div class="mt-1 flex items-center gap-1.5 min-w-0">
          <code class="text-xs text-gray-600 truncate" :title="dn">{{ dn }}</code>
          <CopyButton :text="dn" />
        </div>

        <div class="mt-2 flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-gray-600">
          <span v-if="email" class="inline-flex items-center gap-1">
            <span aria-hidden="true">✉</span>
            <span class="truncate">{{ email }}</span>
          </span>
          <span v-if="profileName" class="inline-flex items-center gap-1">
            <span aria-hidden="true">▢</span>
            <span>Profile: <span class="font-medium text-gray-900">{{ profileName }}</span></span>
          </span>
        </div>
      </div>
    </div>
  </div>
</template>
