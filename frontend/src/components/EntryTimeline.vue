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
          <span class="text-sm font-medium" :class="textClass(evt.action)">{{ actionLabel(evt.action) }}</span>
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

<script setup>
import { ref, watch } from 'vue'
import { getEntryTimeline } from '@/api/audit'
import RelativeTime from '@/components/RelativeTime.vue'

const props = defineProps({
  directoryId: { type: String, required: true },
  targetDn:    { type: String, required: true },
})

const loading = ref(false)
const loadingMore = ref(false)
const events = ref([])
const page = ref(0)
const hasMore = ref(false)
const PAGE_SIZE = 20

const ACTION_LABELS = {
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
  'BULK_ATTRIBUTE_UPDATE': 'Bulk attribute update',
  'APPROVAL_SUBMITTED': 'Approval submitted',
  'APPROVAL_APPROVED': 'Approved',
  'APPROVAL_REJECTED': 'Rejected',
  'LDAP_CHANGE': 'LDAP change',
}

function actionLabel(action) {
  return ACTION_LABELS[action] || action
}

function isDelete(action) {
  return action?.includes('DELETE') || action?.includes('REJECTED')
}

function isCreate(action) {
  return action?.includes('CREATE') || action?.includes('APPROVED')
}

function dotClass(action) {
  if (isDelete(action)) return 'bg-red-500'
  if (isCreate(action)) return 'bg-green-500'
  if (action?.includes('DISABLE')) return 'bg-amber-500'
  if (action?.includes('ENABLE')) return 'bg-green-500'
  if (action === 'PASSWORD_RESET') return 'bg-purple-500'
  return 'bg-blue-500'
}

function textClass(action) {
  if (isDelete(action)) return 'text-red-600'
  if (isCreate(action)) return 'text-green-600'
  if (action?.includes('DISABLE')) return 'text-amber-600'
  if (action?.includes('ENABLE')) return 'text-green-600'
  if (action === 'PASSWORD_RESET') return 'text-purple-600'
  return 'text-blue-600'
}

function detailSummary(evt) {
  if (!evt.detail) return null
  // Show attribute names for updates
  const attrs = evt.detail.attributes || evt.detail.modifiedAttributes
  if (attrs && Array.isArray(attrs)) return 'Attributes: ' + attrs.join(', ')
  // Show changes from changelog
  if (evt.detail.changes) return String(evt.detail.changes).substring(0, 200)
  return null
}

async function load() {
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

async function loadMore() {
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
