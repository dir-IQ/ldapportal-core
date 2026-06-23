<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <div v-if="loading" class="text-sm text-gray-500 text-center py-8">Loading history...</div>
    <div v-else-if="events.length === 0" class="text-sm text-gray-500 text-center py-8">No activity recorded for this entry.</div>
    <ol v-else class="relative border-l border-gray-200 ml-3 space-y-0">
      <li v-for="evt in events" :key="evt.id" class="ml-6 pb-4">
        <span class="absolute -left-[9px] w-[18px] h-[18px] rounded-full border-2 border-white flex items-center justify-center"
          :class="dotClass(evt.action)">
          <svg v-if="isDelete(evt.action)" class="w-2.5 h-2.5 text-white" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 3l6 6M9 3l-6 6"/></svg>
          <svg v-else-if="isCreate(evt.action)" class="w-2.5 h-2.5 text-white" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 2v8M2 6h8"/></svg>
          <span v-else class="w-2 h-2 rounded-full bg-white"></span>
        </span>
        <div class="flex items-baseline gap-2">
          <span class="text-sm font-medium" :class="textClass(evt.action)">{{ actionLabel(evt) }}</span>
          <span class="text-xs text-gray-500">
            <RelativeTime :value="evt.occurredAt" />
          </span>
        </div>
        <p class="text-xs text-gray-500 mt-0.5">
          by <span class="font-medium">{{ evt.actorUsername || 'system' }}</span>
          <span v-if="evt.directoryName" class="text-gray-500"> in {{ evt.directoryName }}</span>
        </p>
        <div v-if="detailSummary(evt)" class="mt-1 text-xs text-gray-500 bg-gray-50 rounded px-2 py-1 font-mono break-all">
          {{ detailSummary(evt) }}
        </div>
      </li>
    </ol>
    <div v-if="hasMore" class="text-center pt-2">
      <button @click="loadMore" :disabled="loadingMore" class="text-xs text-blue-600 hover:underline">
        {{ loadingMore ? 'Loading...' : 'Load more' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { getEntryTimeline } from '@/api/audit'
import { iviaOpLabel } from '@/constants/productNames'
import RelativeTime from '@/components/RelativeTime.vue'

interface TimelineEvent {
  id: string
  action: string
  occurredAt: string
  actorUsername?: string | null
  directoryName?: string | null
  detail?: Record<string, unknown> | null
}

const props = defineProps<{
  directoryId: string
  targetDn: string
}>()

const loading = ref(false)
const loadingMore = ref(false)
const events = ref<TimelineEvent[]>([])
const page = ref(0)
const hasMore = ref(false)
const PAGE_SIZE = 20

const ACTION_LABELS: Record<string, string> = {
  'USER_CREATE': 'Created',
  'USER_UPDATE': 'Updated',
  'USER_DELETE': 'Deleted',
  'USER_ENABLE': 'Enabled',
  'USER_DISABLE': 'Disabled',
  'USER_MOVE': 'Moved',
  'PASSWORD_RESET': 'Password reset',
  'GROUP_CREATE': 'Created',
  'GROUP_UPDATE': 'Updated',
  'GROUP_DELETE': 'Deleted',
  'GROUP_MEMBER_ADD': 'Member added',
  'GROUP_MEMBER_REMOVE': 'Member removed',
  'GROUP_BULK_IMPORT': 'Bulk imported',
  'ENTRY_CREATE': 'Created',
  'ENTRY_UPDATE': 'Updated',
  'ENTRY_DELETE': 'Deleted',
  'ENTRY_MOVE': 'Moved',
  'ENTRY_RENAME': 'Renamed',
  'LDIF_IMPORT': 'LDIF imported',
  'BULK_ATTRIBUTE_UPDATE': 'Bulk attribute update',
  'APPROVAL_SUBMITTED': 'Approval submitted',
  'APPROVAL_APPROVED': 'Approved',
  'APPROVAL_AUTO_APPROVED': 'Auto-approved',
  'APPROVAL_REJECTED': 'Rejected',
  'APPROVAL_REQUEST_EDITED': 'Request edited',
  'PLAYBOOK_EXECUTED': 'Playbook executed',
  'PLAYBOOK_ROLLED_BACK': 'Playbook rolled back',
  'ACCOUNT_CREATE': 'Account created',
  'ACCOUNT_UPDATE': 'Account updated',
  'ACCOUNT_DELETE': 'Account deleted',
  'ACCOUNT_PERMISSION_CHANGED': 'Account permissions changed',
  'LDAP_CHANGE': 'LDAP change',
}

// Fallback humanizer so an audit action without an explicit short label above
// renders as "Playbook executed" rather than the raw PLAYBOOK_EXECUTED token —
// keeps the entry timeline readable when a new AuditAction enum value lands on
// the backend before this map is updated.
function humanize(action: string): string {
  if (!action || typeof action !== 'string') return action
  const lower = action.toLowerCase().replace(/_/g, ' ')
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}

// IVIA account ops are recorded under a generic AuditAction (USER_UPDATE for a
// grant, USER_DELETE for a hard revoke, …) with the real verb in
// detail.ivia_op — prefer that specific label when present.
function actionLabel(evt: TimelineEvent): string {
  return iviaOpLabel(evt.detail) ?? ACTION_LABELS[evt.action] ?? humanize(evt.action)
}

function isDelete(action: string): boolean {
  return action.includes('DELETE') || action.includes('REJECTED')
}

function isCreate(action: string): boolean {
  return action.includes('CREATE') || action.includes('APPROVED')
}

function dotClass(action: string): string {
  if (isDelete(action)) return 'bg-red-500'
  if (isCreate(action)) return 'bg-green-500'
  if (action.includes('DISABLE')) return 'bg-amber-500'
  if (action.includes('ENABLE')) return 'bg-green-500'
  if (action === 'PASSWORD_RESET') return 'bg-purple-500'
  return 'bg-blue-500'
}

function textClass(action: string): string {
  if (isDelete(action)) return 'text-red-600'
  if (isCreate(action)) return 'text-green-600'
  if (action.includes('DISABLE')) return 'text-amber-600'
  if (action.includes('ENABLE')) return 'text-green-600'
  if (action === 'PASSWORD_RESET') return 'text-purple-600'
  return 'text-blue-600'
}

function detailSummary(evt: TimelineEvent): string | null {
  const d = evt.detail
  if (!d) return null
  // Show attribute names for updates
  const attrs = d.attributes ?? d.modifiedAttributes
  if (Array.isArray(attrs)) return 'Attributes: ' + attrs.join(', ')
  // Show changes from changelog
  if (d.changes) return String(d.changes).substring(0, 200)
  return null
}

async function load(): Promise<void> {
  loading.value = true
  page.value = 0
  try {
    const { data } = await getEntryTimeline(props.directoryId, props.targetDn, { size: PAGE_SIZE, page: 0 })
    events.value = data.content || []
    hasMore.value = !data.last
  } catch (e) {
    console.warn('Failed to load timeline:', e)
    events.value = []
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  loadingMore.value = true
  page.value++
  try {
    const { data } = await getEntryTimeline(props.directoryId, props.targetDn, { size: PAGE_SIZE, page: page.value })
    events.value.push(...(data.content || []))
    hasMore.value = !data.last
  } catch (e) {
    console.warn('Failed to load more:', e)
  } finally {
    loadingMore.value = false
  }
}

watch(() => [props.directoryId, props.targetDn], load, { immediate: true })
</script>
