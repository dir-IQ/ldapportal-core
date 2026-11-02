<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <nav class="text-xs text-gray-500 mb-1" aria-label="Breadcrumb">
      Report ›
      <RouterLink v-if="runId" to="/superadmin/directory-diff" class="hover:underline">Directory diff</RouterLink>
      <span v-else>Directory diff</span>
    </nav>
    <h1 class="text-2xl font-bold text-gray-900">{{ run ? pairLabel(run) : 'Directory diff' }}</h1>
    <p v-if="!runId" class="text-sm text-gray-500 mt-1 mb-6">
      Compare two directories, or two branches of one, entry by entry. Read-only: nothing is written to either side.
    </p>
    <p v-else-if="run" class="text-sm text-gray-500 mt-1 mb-6">
      {{ pairingLabel(run) }} · started {{ formatTime(run.startedAt) }}<template v-if="run.requestedBy"> by {{ run.requestedBy }}</template>
    </p>

    <DirectoryDiffSetup v-if="!runId" :prefill="prefill" :verify-set-id="verifySetId" @started="onStarted" @open="openRun" />

    <template v-else>
      <p v-if="loading && !run" class="text-sm text-gray-500">Loading…</p>
      <div v-else-if="loadError" class="bg-white border border-gray-200 rounded-xl p-5 text-sm">
        <p class="text-red-600 mb-3">{{ loadError }}</p>
        <RouterLink to="/superadmin/directory-diff" class="btn-secondary btn-compact">Back to Directory diff</RouterLink>
      </div>
      <template v-else-if="run">
        <DirectoryDiffProgress v-if="run.status === 'RUNNING'" :run="run" :cancelling="cancelling || cancelRequested" @cancel="cancel" />
        <DirectoryDiffResults v-else-if="run.status === 'COMPLETE'" :key="run.id" :run="run" @run-again="runAgain" />
        <div v-else class="bg-white border border-gray-200 rounded-xl p-5 text-sm" data-testid="run-ended">
          <p class="font-medium text-gray-900 mb-1">
            {{ run.status === 'CANCELLED' ? 'This comparison was cancelled.' : 'This comparison failed.' }}
          </p>
          <p v-if="run.error" class="text-gray-600 mb-4">{{ run.error }}</p>
          <div class="flex gap-2">
            <button type="button" class="btn-primary btn-compact" @click="runAgain">Run again</button>
            <RouterLink to="/superadmin/directory-diff" class="btn-secondary btn-compact">Back to Directory diff</RouterLink>
          </div>
        </div>
      </template>
    </template>
  </PageContainer>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { cancelDirectoryDiff, getDirectoryDiff, type DirectoryDiffRun } from '@/api/directoryDiff'
import { useNotificationStore } from '@/stores/notifications'
import PageContainer from '@/components/PageContainer.vue'
import DirectoryDiffSetup from '@/components/directoryDiff/DirectoryDiffSetup.vue'
import DirectoryDiffProgress from '@/components/directoryDiff/DirectoryDiffProgress.vue'
import DirectoryDiffResults from '@/components/directoryDiff/DirectoryDiffResults.vue'
import { errMsg, formatTime, pairLabel, pairingLabel } from '@/components/directoryDiff/directoryDiffFormat'

/** How often a running comparison is re-read. */
const POLL_MS = 2000

const route = useRoute()
const router = useRouter()
const notif = useNotificationStore()

const run = ref<DirectoryDiffRun | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const cancelling = ref(false)
/** A cancel was accepted for the open run; it stops at its next progress write. */
const cancelRequested = ref(false)
/** The run "Run again" pre-fills the form from. */
const prefill = ref<DirectoryDiffRun | null>(null)

let pollTimer: ReturnType<typeof setTimeout> | null = null

const runId = computed<string | null>(() => typeof route.query.run === 'string' ? route.query.run : null)
/** `?verifySet=<id>` opens the form filled in from that sync set (the sync set's "Compare in Directory Diff"). */
const verifySetId = computed<string | null>(() =>
  typeof route.query.verifySet === 'string' ? route.query.verifySet : null)

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function load(id: string) {
  stopPolling()
  loading.value = true
  try {
    const { data } = await getDirectoryDiff(id)
    if (runId.value !== id) return
    run.value = data
    loadError.value = null
    if (data.status === 'RUNNING') {
      pollTimer = setTimeout(() => load(id), POLL_MS)
    }
  } catch (e) {
    if (runId.value !== id) return
    // Keep showing the last progress through a passing error; only a first load fails the page.
    if (run.value?.id === id && run.value.status === 'RUNNING') {
      pollTimer = setTimeout(() => load(id), POLL_MS)
    } else {
      loadError.value = errMsg(e, 'Could not load this comparison')
    }
  } finally {
    loading.value = false
  }
}

function openRun(id: string) {
  router.push({ path: '/superadmin/directory-diff', query: { run: id } })
}

function onStarted(started: DirectoryDiffRun) {
  prefill.value = null
  run.value = started
  openRun(started.id)
}

function runAgain() {
  prefill.value = run.value
  router.push({ path: '/superadmin/directory-diff' })
}

async function cancel() {
  if (!run.value) return
  cancelling.value = true
  try {
    const { data } = await cancelDirectoryDiff(run.value.id)
    run.value = data
    cancelRequested.value = true
    notif.success('Cancelling the comparison')
  } catch (e) {
    notif.error(errMsg(e, 'Could not cancel the comparison'))
  } finally {
    cancelling.value = false
  }
}

watch(runId, id => {
  stopPolling()
  cancelRequested.value = false
  loadError.value = null
  if (!id) {
    run.value = null
    return
  }
  if (run.value?.id !== id) run.value = null
  load(id)
}, { immediate: true })

onBeforeUnmount(stopPolling)
</script>
