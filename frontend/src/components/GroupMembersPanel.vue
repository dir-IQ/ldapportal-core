<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <!--
    Members of a group entry, surfaced above the raw attribute table in the
    Directory Browser. Renders nothing for non-group entries so the browser
    stays unchanged for users, OUs and everything else. Membership is read
    from whichever convention the group uses (member / uniqueMember /
    memberUid) via the same resolver the Groups page relies on.
  -->
  <div v-if="isGroup" class="mb-5 border border-gray-200 rounded-lg px-4 pt-3 pb-3.5" data-testid="group-members-panel">
    <div class="flex items-center justify-between gap-3 flex-wrap">
      <div class="flex items-center gap-2">
        <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider">Members</p>
        <span class="badge-gray" data-testid="member-count">{{ members.length }}</span>
        <span class="font-mono text-xs text-gray-500">{{ memberAttr }}</span>
      </div>
      <div v-if="members.length" class="flex items-center gap-2">
        <label for="group-members-filter" class="sr-only">Filter members</label>
        <input
          id="group-members-filter"
          v-model="filter"
          type="search"
          class="input-sm w-52"
          placeholder="Filter members…"
        />
        <button type="button" class="btn-secondary btn-compact" @click="copyAll">
          {{ copied ? 'Copied' : 'Copy all' }}
        </button>
      </div>
    </div>

    <p v-if="!members.length" class="mt-2 text-sm text-gray-500">This group has no members.</p>

    <template v-else>
      <ul
        class="mt-2.5 space-y-1 overflow-y-auto"
        :style="{ maxHeight: `${listMaxHeight}px` }"
        aria-label="Group members"
        data-testid="member-list"
      >
        <li
          v-for="row in filteredRows"
          :key="row.value"
          class="flex items-center gap-2 bg-gray-50 rounded px-3 py-1.5 font-mono text-[13px] text-gray-700 break-all"
        >
          <template v-if="row.parent">
            <span class="font-medium text-gray-900">{{ row.rdn }}</span>
            <span class="text-gray-500">—</span>
            <span class="flex-1">{{ row.parent }}</span>
          </template>
          <span v-else class="flex-1 font-medium text-gray-900">{{ row.rdn }}</span>
          <button
            v-if="isDnValued"
            type="button"
            class="font-sans text-xs text-blue-600 hover:underline shrink-0"
            :title="`Open ${row.value}`"
            @click="emit('open', row.value)"
          >
            Open
          </button>
        </li>
        <li v-if="!filteredRows.length" class="py-2 text-center text-sm text-gray-500">
          No members match “{{ filter }}”.
        </li>
      </ul>
      <!-- Height grip. Drag (pointer) or ArrowUp/ArrowDown (keyboard) changes
           the list's max-height; the chosen height is remembered in the user's
           preferences so it survives switching entries and browsers. Short
           groups still take only their natural height. -->
      <button
        type="button"
        class="mt-1 w-full h-2.5 flex items-center justify-center cursor-ns-resize rounded text-gray-300 hover:text-gray-500 focus-visible:text-gray-500"
        aria-label="Resize member list"
        title="Drag, or use the arrow keys, to change the list height"
        data-testid="member-list-grip"
        @pointerdown="onResizePointerDown"
        @keydown.up.prevent="nudgeHeight(-RESIZE_STEP)"
        @keydown.down.prevent="nudgeHeight(RESIZE_STEP)"
      >
        <span class="block w-10 h-1 rounded-full bg-current" aria-hidden="true"></span>
      </button>
      <p class="mt-2 text-xs text-gray-500">
        <template v-if="isDnValued">Open selects the member entry in the browser.</template>
        <template v-else>posixGroup members are user IDs, not DNs.</template>
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { usePreferencesStore } from '@/stores/preferences'
import { resolveGroupMembers } from '@/utils/groupMembers'

const props = defineProps<{
  attributes: Record<string, string[]>
}>()

const emit = defineEmits<{
  (e: 'open', dn: string): void
}>()

interface MemberRow {
  /** The raw attribute value (a DN, or a uid for posixGroup). */
  value: string
  /** Leading RDN of a DN value, or the whole value when not a DN. */
  rdn: string
  /** Everything after the first RDN; empty when the value is not a DN. */
  parent: string
}

/** Object classes (lower-cased) whose entries carry a membership attribute. */
const GROUP_CLASSES = new Set([
  'groupofnames',
  'groupofuniquenames',
  'groupofentries',
  'posixgroup',
  'group', // Active Directory
])

const notif = useNotificationStore()
const prefs = usePreferencesStore()

// ── List height ─────────────────────────────────────────────────────────────
// The member list scrolls past a max-height the user can drag. Persisted under
// the `panels` preference namespace so it follows the user across devices.
const HEIGHT_PREF_KEY = 'browser-group-members'
const DEFAULT_MAX_HEIGHT = 240   // matches the former max-h-60
const MIN_MAX_HEIGHT = 96
const MAX_MAX_HEIGHT = 1200
const RESIZE_STEP = 24

function clampHeight(h: number): number {
  return Math.min(MAX_MAX_HEIGHT, Math.max(MIN_MAX_HEIGHT, Math.round(h)))
}

function readStoredHeight(): number {
  const stored = prefs.read<unknown>('panels', HEIGHT_PREF_KEY, null)
  return typeof stored === 'number' && Number.isFinite(stored)
    ? clampHeight(stored)
    : DEFAULT_MAX_HEIGHT
}

const listMaxHeight = ref<number>(readStoredHeight())

let dragStartY = 0
let dragStartHeight = 0

function onResizePointerMove(e: PointerEvent): void {
  listMaxHeight.value = clampHeight(dragStartHeight + (e.clientY - dragStartY))
}

function onResizePointerUp(): void {
  document.body.style.removeProperty('user-select')
  window.removeEventListener('pointermove', onResizePointerMove)
  window.removeEventListener('pointerup', onResizePointerUp)
  persistHeight()
}

function onResizePointerDown(e: PointerEvent): void {
  if (e.button !== 0) return
  dragStartY = e.clientY
  dragStartHeight = listMaxHeight.value
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', onResizePointerMove)
  window.addEventListener('pointerup', onResizePointerUp)
  e.preventDefault()
}

function nudgeHeight(delta: number): void {
  listMaxHeight.value = clampHeight(listMaxHeight.value + delta)
  persistHeight()
}

function persistHeight(): void {
  prefs.write('panels', HEIGHT_PREF_KEY, listMaxHeight.value)
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onResizePointerMove)
  window.removeEventListener('pointerup', onResizePointerUp)
})

const filter = ref('')
const copied = ref(false)
let copiedTimer: ReturnType<typeof setTimeout> | null = null

const objectClasses = computed<string[]>(() => {
  const key = Object.keys(props.attributes ?? {}).find(k => k.toLowerCase() === 'objectclass')
  return key ? (props.attributes[key] ?? []).map(v => v.toLowerCase()) : []
})

const isGroup = computed(() => objectClasses.value.some(oc => GROUP_CLASSES.has(oc)))

const resolved = computed(() => resolveGroupMembers(props.attributes))
const members = computed(() => resolved.value.members)
const memberAttr = computed(() => resolved.value.memberAttr)
const isDnValued = computed(() => memberAttr.value !== 'memberUid')

const rows = computed<MemberRow[]>(() =>
  members.value.map(value => (isDnValued.value ? splitDn(value) : { value, rdn: value, parent: '' })),
)

const filteredRows = computed(() => {
  const q = filter.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter(r => r.value.toLowerCase().includes(q))
})

// A new entry means a new member list; a stale filter would hide it.
watch(() => props.attributes, () => { filter.value = '' })

/**
 * Split a DN at its first unescaped comma. LDAP allows `\,` inside an RDN
 * value ("cn=Doe\, Jane,ou=People,…"), so a plain indexOf(',') would cut
 * the RDN short for those entries.
 */
function splitDn(dn: string): MemberRow {
  for (let i = 0; i < dn.length; i++) {
    if (dn[i] === '\\') { i++; continue }
    if (dn[i] === ',') {
      return { value: dn, rdn: dn.slice(0, i), parent: dn.slice(i + 1).trim() }
    }
  }
  return { value: dn, rdn: dn, parent: '' }
}

async function copyAll(): Promise<void> {
  try {
    await navigator.clipboard.writeText(members.value.join('\n'))
    copied.value = true
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => { copied.value = false }, 1500)
  } catch (e) {
    const err = e as { message?: string }
    notif.error(err.message || 'Could not copy to clipboard')
  }
}
</script>
