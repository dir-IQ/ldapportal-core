<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Directory Sync</h1>
        <p class="text-sm text-gray-500 mt-1">
          Asynchronous replication of app-initiated changes between directories
        </p>
      </div>
      <button @click="openCreate" class="btn-primary">+ New Replication Link</button>
    </div>

    <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
      <div v-if="loading" class="p-8 text-center text-gray-500 text-sm">Loading…</div>
      <EmptyState v-else-if="links.length === 0" icon="folder" title="No replication links configured." />
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-100">
          <tr>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Name</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Source → Target</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Status</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Pending</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Failed</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Dead-lettered</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Last delivered</th>
            <th class="px-4 py-3"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-50">
          <tr v-for="link in links" :key="link.id" class="hover:bg-gray-50">
            <td class="px-4 py-3 font-medium text-gray-900">{{ link.displayName }}</td>
            <td class="px-4 py-3 text-gray-600 text-xs">
              {{ link.sourceDirectoryName }} → {{ link.targetDirectoryName }}
            </td>
            <td class="px-4 py-3">
              <span :class="link.enabled ? 'text-green-600' : 'text-gray-500'" class="text-xs font-medium">
                {{ link.enabled ? 'Enabled' : 'Disabled' }}
              </span>
            </td>
            <td class="px-4 py-3 text-gray-600">{{ link.pendingCount }}</td>
            <td class="px-4 py-3"
                :class="link.failedCount > 0 ? 'text-amber-700 font-medium' : 'text-gray-600'">
              {{ link.failedCount }}
            </td>
            <td class="px-4 py-3"
                :class="link.deadLetteredCount > 0 ? 'text-red-700 font-medium' : 'text-gray-600'">
              {{ link.deadLetteredCount }}
            </td>
            <td class="px-4 py-3 text-gray-600 text-xs">
              <RelativeTime v-if="link.lastDeliveredAt" :value="link.lastDeliveredAt" />
              <span v-else class="text-gray-400">—</span>
            </td>
            <td class="px-4 py-3 text-right whitespace-nowrap">
              <ActionMenu :items="[
                { label: 'View events', onClick: () => openEvents(link) },
                { label: 'Reconcile now', onClick: () => reconcileNow(link) },
                { label: 'Reconciliation history', onClick: () => openRuns(link) },
                { label: 'Delete', onClick: () => confirmDelete(link), danger: true },
              ]">
                <template #primary>
                  <button @click="openEdit(link)" class="btn-secondary btn-compact">Edit</button>
                </template>
              </ActionMenu>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Create / Edit modal ───────────────────────────────────────────── -->
    <AppModal v-model="showForm"
              :title="editing ? 'Edit Replication Link' : 'New Replication Link'"
              size="lg"
              fixed-height="min(640px, 80vh)">
      <form @submit.prevent="save" class="space-y-3">
        <div class="grid grid-cols-2 gap-3">
          <FormField label="Display Name" v-model="form.displayName" required />
          <div></div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Source Directory</label>
            <select v-model="form.sourceDirectoryId" class="input w-full" required>
              <option value="" disabled>Select source…</option>
              <option v-for="d in directoryOptions" :key="d.id" :value="d.id">{{ d.displayName }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Target Directory</label>
            <select v-model="form.targetDirectoryId" class="input w-full" required>
              <option value="" disabled>Select target…</option>
              <option v-for="d in directoryOptions" :key="d.id" :value="d.id"
                      :disabled="d.id === form.sourceDirectoryId">{{ d.displayName }}</option>
            </select>
          </div>
          <FormField label="Source Base DN (optional)" v-model="form.sourceBaseDn"
                     placeholder="leave blank for identity mapping" />
          <FormField label="Target Base DN (optional)" v-model="form.targetBaseDn"
                     placeholder="required if source is set" />
        </div>

        <div class="flex items-center gap-4">
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" v-model="form.enabled" class="rounded" />
            Enabled
          </label>
          <label class="flex items-center gap-2 text-sm text-gray-700"
                 title="When MODIFY targets a missing entry, auto-create from source first.">
            <input type="checkbox" v-model="form.autoCreateOnMissing" class="rounded" />
            Auto-create on missing target
          </label>
        </div>

        <details class="border border-gray-200 rounded-lg">
          <summary class="px-3 py-2 cursor-pointer text-sm text-gray-700 select-none">
            Attribute mappings ({{ form.attributeMappings.length }})
          </summary>
          <div class="p-3 space-y-2">
            <p class="text-xs text-gray-500">
              Leave empty for identity mapping (same attribute names, same values).
              <code>${value}</code> in the template substitutes the source value.
            </p>
            <table v-if="form.attributeMappings.length > 0" class="w-full text-xs">
              <thead class="text-gray-500">
                <tr>
                  <th class="text-left pb-1">Source attr</th>
                  <th class="text-left pb-1">Target attr</th>
                  <th class="text-left pb-1">Value template</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(m, i) in form.attributeMappings" :key="i">
                  <td class="pr-2 py-1"><input v-model="m.sourceAttr" class="input w-full text-xs" /></td>
                  <td class="pr-2 py-1"><input v-model="m.targetAttr" class="input w-full text-xs" /></td>
                  <td class="pr-2 py-1"><input v-model="m.valueTemplate"
                                               placeholder="${value}"
                                               class="input w-full text-xs" /></td>
                  <td class="py-1 text-right">
                    <button type="button" @click="form.attributeMappings.splice(i, 1)"
                            class="text-red-600 hover:text-red-700 text-xs px-1">×</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <button type="button" @click="addMappingRow" class="btn-secondary btn-compact text-xs">
              + Add mapping
            </button>
          </div>
        </details>

        <!-- Reconciliation config (R-P0) ─────────────────────────────────── -->
        <details class="border border-gray-200 rounded-lg">
          <summary class="px-3 py-2 cursor-pointer text-sm text-gray-700 select-none">
            Reconciliation
            <span class="text-xs text-gray-400">
              — {{ form.reconcileEnabled
                    ? `${form.reconcileMode === 'AUTO_CORRECT' ? 'auto-correct' : 'review'}, every ${form.reconcileIntervalValue} ${form.reconcileIntervalUnit}`
                    : 'off' }}
            </span>
          </summary>
          <div class="p-3 space-y-3">
            <p class="text-xs text-gray-500">
              Periodically compares the target against the source and resolves drift the
              live capture path can't see (out-of-band changes, missed writes, initial backfill).
            </p>
            <label class="flex items-center gap-2 text-sm text-gray-700">
              <input type="checkbox" v-model="form.reconcileEnabled" :disabled="!form.enabled" class="rounded" />
              Enable periodic reconciliation
            </label>
            <p v-if="!form.enabled" class="text-xs text-gray-400">Enable the link first to configure reconciliation.</p>

            <div v-if="form.reconcileEnabled" class="space-y-3 pl-6">
              <!-- Mode (missing / drift) -->
              <div>
                <span class="block text-sm font-medium text-gray-700 mb-1">Missing entries &amp; attribute drift</span>
                <label class="flex items-center gap-2 text-sm text-gray-700">
                  <input type="radio" value="REVIEW" v-model="form.reconcileMode" /> Review before applying
                </label>
                <label class="flex items-center gap-2 text-sm text-gray-700">
                  <input type="radio" value="AUTO_CORRECT" v-model="form.reconcileMode" /> Correct automatically
                </label>
              </div>

              <!-- Schedule -->
              <div class="grid grid-cols-2 gap-3">
                <div>
                  <label class="block text-sm font-medium text-gray-700 mb-1">First run</label>
                  <input type="datetime-local" v-model="form.reconcileFirstRunAt"
                         class="input w-full" :required="form.reconcileEnabled" />
                </div>
                <div>
                  <label class="block text-sm font-medium text-gray-700 mb-1">Repeat every</label>
                  <div class="flex gap-2">
                    <input type="number" min="1" v-model.number="form.reconcileIntervalValue"
                           class="input w-24" :required="form.reconcileEnabled" />
                    <select v-model="form.reconcileIntervalUnit" class="input">
                      <option value="hours">hours</option>
                      <option value="days">days</option>
                    </select>
                  </div>
                  <p class="text-xs text-gray-400 mt-1">Minimum 1 hour.</p>
                </div>
              </div>

              <!-- Extra-on-target (delete) action -->
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">
                  Extra entries on the target (no source match)
                </label>
                <select v-model="form.reconcileDeleteAction" @change="onDeleteActionChange" class="input w-full">
                  <option value="IGNORE">Leave alone</option>
                  <option value="REVIEW">Review before deleting</option>
                  <option value="AUTO">Delete automatically</option>
                </select>
                <p v-if="form.reconcileDeleteAction === 'AUTO'" class="text-xs text-red-600 mt-1">
                  ⚠ Entries on the target with no source counterpart will be deleted automatically.
                </p>
              </div>
            </div>
          </div>
        </details>
      </form>

      <template #footer>
        <button @click="showForm = false" class="btn-secondary">Cancel</button>
        <button @click="save" :disabled="saving" class="btn-primary">
          {{ saving ? 'Saving…' : 'Save' }}
        </button>
      </template>
    </AppModal>

    <!-- Event log modal ────────────────────────────────────────────────── -->
    <AppModal v-model="showEvents" :title="`Events — ${eventsLink?.displayName ?? ''}`"
              size="xl" fixed-height="min(720px, 85vh)">
      <div class="space-y-3">
        <div class="flex gap-3 items-center text-sm">
          <label class="text-gray-700">Status:</label>
          <select v-model="eventStatusFilter" @change="loadEvents()" class="input">
            <option value="">All</option>
            <option value="PENDING">Pending</option>
            <option value="IN_FLIGHT">In flight</option>
            <option value="DELIVERED">Delivered</option>
            <option value="FAILED">Failed</option>
            <option value="DEAD_LETTERED">Dead-lettered</option>
            <option value="SKIPPED">Skipped</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
          </select>
          <button @click="loadEvents()" class="btn-secondary btn-compact text-xs">Refresh</button>
        </div>
        <div v-if="loadingEvents" class="text-center text-gray-500 py-4 text-sm">Loading…</div>
        <div v-else-if="events.length === 0" class="text-center text-gray-500 py-4 text-sm">
          No events match the filter.
        </div>
        <table v-else class="w-full text-xs">
          <thead class="bg-gray-50 text-gray-500">
            <tr>
              <th class="text-left px-2 py-1">When</th>
              <th class="text-left px-2 py-1">Op</th>
              <th class="text-left px-2 py-1">Target DN</th>
              <th class="text-left px-2 py-1">Status</th>
              <th class="text-left px-2 py-1">Attempts</th>
              <th class="text-left px-2 py-1">Last error</th>
              <th class="px-2 py-1"></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            <tr v-for="e in events" :key="e.id">
              <td class="px-2 py-1"><RelativeTime :value="e.enqueuedAt" /></td>
              <td class="px-2 py-1 font-mono text-[10px]">{{ e.operation }}</td>
              <td class="px-2 py-1 font-mono text-[10px] truncate max-w-xs" :title="e.targetDn">
                {{ e.targetDn }}
              </td>
              <td class="px-2 py-1">
                <span :class="statusClass(e.status)" class="px-1.5 py-0.5 rounded text-[10px] font-medium">
                  {{ e.status }}
                </span>
              </td>
              <td class="px-2 py-1">{{ e.attempts }}</td>
              <td class="px-2 py-1 text-[10px] text-gray-600 truncate max-w-xs" :title="e.lastError">
                {{ e.lastError || '—' }}
              </td>
              <td class="px-2 py-1 text-right whitespace-nowrap">
                <button v-if="e.correlationId" @click="traceCorrelation(e)"
                        title="Show every audit row from this event's originating operation"
                        class="text-indigo-600 hover:text-indigo-700 text-[10px] px-1">trace</button>
                <button v-if="canRetry(e.status)" @click="doEventAction(e, 'retry')"
                        class="text-blue-600 hover:text-blue-700 text-[10px] px-1">retry</button>
                <button v-if="canSkip(e.status)" @click="doEventAction(e, 'skip')"
                        class="text-gray-600 hover:text-gray-700 text-[10px] px-1">skip</button>
                <button v-if="e.status === 'DEAD_LETTERED'" @click="doEventAction(e, 'ack')"
                        class="text-gray-600 hover:text-gray-700 text-[10px] px-1">ack</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="eventsTotalPages > 1" class="flex justify-between items-center text-xs text-gray-500">
          <button :disabled="eventsPage === 0" @click="eventsPage--; loadEvents()" class="btn-secondary btn-compact">← Prev</button>
          <span>Page {{ eventsPage + 1 }} of {{ eventsTotalPages }}</span>
          <button :disabled="eventsPage >= eventsTotalPages - 1" @click="eventsPage++; loadEvents()" class="btn-secondary btn-compact">Next →</button>
        </div>
      </div>
    </AppModal>

    <!-- Reconciliation runs modal ──────────────────────────────────────── -->
    <AppModal v-model="showRuns" :title="`Reconciliation — ${runsLink?.displayName ?? ''}`"
              size="xl" fixed-height="min(640px, 85vh)">
      <div class="space-y-3">
        <div class="flex items-center gap-3 text-sm">
          <button v-if="runsLink" @click="reconcileNow(runsLink)" class="btn-secondary btn-compact text-xs">Reconcile now</button>
          <button @click="loadRuns" class="btn-secondary btn-compact text-xs">Refresh</button>
        </div>
        <div v-if="loadingRuns" class="text-center text-gray-500 py-4 text-sm">Loading…</div>
        <EmptyState v-else-if="runs.length === 0" icon="clipboard" title="No reconciliation runs yet." />
        <table v-else class="w-full text-xs">
          <thead class="bg-gray-50 text-gray-500">
            <tr>
              <th class="text-left px-2 py-1">Started</th>
              <th class="text-left px-2 py-1">Trigger</th>
              <th class="text-left px-2 py-1">Mode</th>
              <th class="text-left px-2 py-1">Status</th>
              <th class="text-right px-2 py-1">Missing</th>
              <th class="text-right px-2 py-1">Drift</th>
              <th class="text-right px-2 py-1">Extra</th>
              <th class="text-right px-2 py-1">Suppressed</th>
              <th class="text-right px-2 py-1">Applied</th>
              <th class="px-2 py-1"></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            <tr v-for="r in runs" :key="r.id" class="hover:bg-gray-50">
              <td class="px-2 py-1"><RelativeTime :value="r.startedAt" /></td>
              <td class="px-2 py-1">{{ r.trigger }}</td>
              <td class="px-2 py-1">{{ r.mode }}</td>
              <td class="px-2 py-1">
                <span class="badge" :class="{ 'badge-green': r.status === 'COMPLETED', 'badge-blue': r.status === 'RUNNING', 'badge-red': r.status === 'FAILED', 'badge-gray': r.status === 'CANCELLED' }"
                      :title="r.error || ''">{{ r.status }}</span>
              </td>
              <td class="px-2 py-1 text-right">{{ r.missingCount }}</td>
              <td class="px-2 py-1 text-right">{{ r.driftCount }}</td>
              <td class="px-2 py-1 text-right">{{ r.extraCount }}</td>
              <td class="px-2 py-1 text-right text-gray-500">{{ r.suppressedCount }}</td>
              <td class="px-2 py-1 text-right">{{ r.appliedCount }}</td>
              <td class="px-2 py-1 text-right">
                <button v-if="runHasFindings(r)" @click="openFindings(r)" class="text-indigo-600 hover:text-indigo-700 font-medium">Review findings</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </AppModal>

    <!-- Findings review modal ──────────────────────────────────────────── -->
    <AppModal v-model="showFindings" :title="`Findings — ${runsLink?.displayName ?? ''}`"
              size="xl" fixed-height="min(720px, 88vh)">
      <div class="space-y-3">
        <!-- Run summary chips -->
        <div v-if="findingsRun" class="flex flex-wrap items-center gap-2 text-xs">
          <span class="badge badge-gray">Source {{ findingsRun.sourceEntryCount ?? '—' }}</span>
          <span class="badge badge-gray">Target {{ findingsRun.targetEntryCount ?? '—' }}</span>
          <span class="badge badge-blue">Missing {{ findingsRun.missingCount }}</span>
          <span class="badge badge-amber">Drift {{ findingsRun.driftCount }}</span>
          <span class="badge badge-red">Extra {{ findingsRun.extraCount }}</span>
          <span class="badge badge-gray">Suppressed {{ findingsRun.suppressedCount }}</span>
        </div>
        <!-- Toolbar -->
        <div class="flex items-center gap-2 text-sm">
          <label class="text-gray-700">Status</label>
          <select v-model="findingStatusFilter" @change="loadFindings" class="input">
            <option value="PROPOSED">Proposed</option>
            <option value="">All</option>
            <option value="AUTO_APPLIED">Auto-applied</option>
            <option value="APPLIED">Applied</option>
            <option value="DISMISSED">Dismissed</option>
          </select>
          <label class="text-gray-700">Type</label>
          <select v-model="findingTypeFilter" @change="loadFindings" class="input">
            <option value="">All</option>
            <option value="MISSING_IN_TARGET">Missing</option>
            <option value="ATTRIBUTE_DRIFT">Drift</option>
            <option value="EXTRA_IN_TARGET">Extra</option>
          </select>
          <button @click="loadFindings" class="btn-secondary btn-compact text-xs">Refresh</button>
          <div class="flex-1"></div>
          <span class="text-xs text-gray-500">{{ selectedFindings.size }} selected</span>
          <button @click="dismissSelectedFindings" :disabled="applyingFindings || selectedFindings.size === 0"
                  class="btn-secondary btn-compact text-xs">Dismiss selected</button>
          <button @click="applySelectedFindings" :disabled="applyingFindings || selectedFindings.size === 0"
                  class="btn-primary btn-compact text-xs">Apply selected</button>
        </div>

        <div v-if="loadingFindings" class="text-center text-gray-500 py-4 text-sm">Loading…</div>
        <EmptyState v-else-if="findings.length === 0" icon="shield" title="No findings for this filter." />
        <table v-else class="w-full text-xs">
          <thead class="bg-gray-50 text-gray-500">
            <tr>
              <th class="px-2 py-1 w-8">
                <input type="checkbox" :checked="allProposedSelected" @change="toggleAllFindings"
                       :disabled="proposedFindings.length === 0" aria-label="Select all proposed" />
              </th>
              <th class="text-left px-2 py-1">Type</th>
              <th class="text-left px-2 py-1">Source DN</th>
              <th class="text-left px-2 py-1">Target DN</th>
              <th class="text-left px-2 py-1">Action</th>
              <th class="px-2 py-1"></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            <template v-for="f in findings" :key="f.id">
              <tr class="hover:bg-gray-50" :class="{ 'bg-blue-50': selectedFindings.has(f.id) }">
                <td class="px-2 py-1">
                  <input type="checkbox" :checked="selectedFindings.has(f.id)" @change="toggleFinding(f.id)"
                         :disabled="f.status !== 'PROPOSED'" :aria-label="`Select ${f.targetDn}`" />
                </td>
                <td class="px-2 py-1">
                  <span class="badge" :class="{ 'badge-blue': f.findingType === 'MISSING_IN_TARGET', 'badge-amber': f.findingType === 'ATTRIBUTE_DRIFT', 'badge-red': f.findingType === 'EXTRA_IN_TARGET' }">
                    {{ f.findingType === 'MISSING_IN_TARGET' ? 'Missing' : f.findingType === 'ATTRIBUTE_DRIFT' ? 'Drift' : 'Extra' }}
                  </span>
                </td>
                <td class="px-2 py-1 font-mono text-[10px] truncate max-w-xs" :title="f.sourceDn || ''">{{ f.sourceDn || '—' }}</td>
                <td class="px-2 py-1 font-mono text-[10px] truncate max-w-xs" :title="f.targetDn">{{ f.targetDn }}</td>
                <td class="px-2 py-1">
                  <span class="badge badge-gray">{{ f.suggestedOp }}</span>
                  <span v-if="f.status !== 'PROPOSED'" class="ml-1 text-[10px] text-gray-400">{{ f.status }}</span>
                </td>
                <td class="px-2 py-1 text-right">
                  <button @click="toggleExpand(f.id)" class="text-gray-500 hover:text-gray-700">{{ expandedFindings.has(f.id) ? '▾' : '▸' }}</button>
                </td>
              </tr>
              <tr v-if="expandedFindings.has(f.id)" :key="f.id + '-d'">
                <td colspan="6" class="px-2 py-2 bg-gray-50">
                  <ul class="pl-8 space-y-0.5">
                    <li v-for="(line, i) in findingChanges(f)" :key="i" class="font-mono text-[11px] text-gray-600">{{ line }}</li>
                  </ul>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </AppModal>

    <ConfirmDialog v-if="deleteTarget"
                   v-model="confirmDeleteOpen"
                   title="Delete replication link?"
                   :message="`Delete '${deleteTarget.displayName}'? Pending and dead-lettered events will be removed too.`"
                   confirm-label="Delete"
                   danger
                   @confirm="doDelete"
                   @cancel="deleteTarget = null" />
  </PageContainer>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import {
  listReplicationLinks, createReplicationLink, updateReplicationLink, deleteReplicationLink,
  listReplicationEvents, retryReplicationEvent, skipReplicationEvent, acknowledgeReplicationEvent,
  reconcileNow as apiReconcileNow,
  listReconciliationRuns, getReconciliationFindings,
  applyReconciliationFindings, dismissReconciliationFindings,
} from '@/api/replication'
import { listDirectories } from '@/api/directories'
import PageContainer from '@/components/PageContainer.vue'
import AppModal from '@/components/AppModal.vue'
import FormField from '@/components/FormField.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import { useConfirm } from '@/composables/useConfirm'
import type { components } from '@/api/openapi'

type Directory = components['schemas']['DirectoryConnectionResponse']

interface AttributeMapping {
  sourceAttr: string
  targetAttr: string
  valueTemplate: string
}

type ReconcileMode = 'AUTO_CORRECT' | 'REVIEW'
type ReconcileDeleteAction = 'IGNORE' | 'REVIEW' | 'AUTO'
type IntervalUnit = 'hours' | 'days'

interface ReplicationForm {
  displayName: string
  sourceDirectoryId: string
  targetDirectoryId: string
  sourceBaseDn: string
  targetBaseDn: string
  enabled: boolean
  autoCreateOnMissing: boolean
  attributeMappings: AttributeMapping[]
  // Reconciliation config (R-P0). interval is held as value+unit in the
  // form and serialized to reconcileIntervalSecs on save.
  reconcileEnabled: boolean
  reconcileMode: ReconcileMode
  reconcileDeleteAction: ReconcileDeleteAction
  reconcileFirstRunAt: string          // datetime-local string ('' when unset)
  reconcileIntervalValue: number
  reconcileIntervalUnit: IntervalUnit
}

// Row shapes from the (untyped) replication API; only the fields this
// view reads are modelled.
interface ReplicationLink {
  id: string
  displayName: string
  sourceDirectoryId: string
  targetDirectoryId: string
  sourceDirectoryName?: string
  targetDirectoryName?: string
  sourceBaseDn?: string | null
  targetBaseDn?: string | null
  enabled: boolean
  autoCreateOnMissing: boolean
  pendingCount: number
  failedCount: number
  deadLetteredCount: number
  lastDeliveredAt?: string | null
  attributeMappings?: AttributeMapping[]
  reconcileEnabled?: boolean
  reconcileMode?: ReconcileMode
  reconcileDeleteAction?: ReconcileDeleteAction
  reconcileFirstRunAt?: string | null
  reconcileIntervalSecs?: number | null
  reconcileNextRunAt?: string | null
  reconcileLastRunAt?: string | null
}

interface ReplicationEvent {
  id: string
  enqueuedAt: string
  operation: string
  targetDn: string
  status: string
  attempts: number
  lastError?: string
  correlationId?: string
}

type EventAction = 'retry' | 'skip' | 'ack'

// Reconciliation run + finding rows (untyped API; only read fields modelled).
interface ReconRun {
  id: string
  trigger: string
  mode: string
  status: string
  startedAt: string
  finishedAt?: string | null
  sourceEntryCount?: number | null
  targetEntryCount?: number | null
  missingCount: number
  driftCount: number
  extraCount: number
  suppressedCount: number
  appliedCount: number
  error?: string | null
}

interface ReconFinding {
  id: string
  findingType: 'MISSING_IN_TARGET' | 'ATTRIBUTE_DRIFT' | 'EXTRA_IN_TARGET'
  suggestedOp: string
  sourceDn?: string | null
  targetDn: string
  detail: Record<string, unknown>
  status: string
  eventId?: string | null
}

// Repo-standard axios/native error narrowing (see docs/frontend-conventions.md).
function errMsg(e: unknown, fallback = 'Something went wrong'): string {
  const err = e as { response?: { data?: { detail?: string } }; message?: string }
  return err.response?.data?.detail || err.message || fallback
}

const notif = useNotificationStore()
const router = useRouter()
const confirm = useConfirm()

// Choosing "Delete automatically" is destructive — make the operator
// confirm, and revert to Review if they decline.
async function onDeleteActionChange() {
  if (form.value.reconcileDeleteAction !== 'AUTO') return
  const ok = await confirm({
    title: 'Delete extra target entries automatically?',
    message: 'Reconciliation will permanently DELETE entries on the target that '
      + 'have no counterpart on the source, without further review. This cannot be undone.',
    confirmLabel: 'Yes, delete automatically',
  })
  if (!ok) form.value.reconcileDeleteAction = 'REVIEW'
}

const links     = ref<ReplicationLink[]>([])
const directoryOptions = ref<Directory[]>([])
const loading   = ref(false)

const showForm  = ref(false)
const editing   = ref<ReplicationLink | null>(null)
const saving    = ref(false)
const form      = ref<ReplicationForm>(emptyForm())

const deleteTarget      = ref<ReplicationLink | null>(null)
const confirmDeleteOpen = computed({
  get: () => !!deleteTarget.value,
  set: (v: boolean) => { if (!v) deleteTarget.value = null },
})

// Event log state
const showEvents       = ref(false)
const eventsLink       = ref<ReplicationLink | null>(null)
const events           = ref<ReplicationEvent[]>([])
const eventStatusFilter = ref('')
const eventsPage       = ref(0)
const eventsTotalPages = ref(1)
const loadingEvents    = ref(false)

function emptyForm(): ReplicationForm {
  return {
    displayName: '',
    sourceDirectoryId: '',
    targetDirectoryId: '',
    sourceBaseDn: '',
    targetBaseDn: '',
    enabled: true,
    autoCreateOnMissing: false,
    attributeMappings: [],
    reconcileEnabled: false,
    reconcileMode: 'REVIEW',
    reconcileDeleteAction: 'REVIEW',
    reconcileFirstRunAt: '',
    reconcileIntervalValue: 1,
    reconcileIntervalUnit: 'days',
  }
}

function addMappingRow() {
  form.value.attributeMappings.push({ sourceAttr: '', targetAttr: '', valueTemplate: '' })
}

// ── reconciliation interval / datetime helpers ───────────────────────────────
// Interval is stored in seconds on the backend; the form holds value+unit.
function intervalToSecs(value: number, unit: IntervalUnit): number | null {
  if (!value || value < 1) return null
  return Math.round(value) * (unit === 'days' ? 86400 : 3600)
}
function secsToInterval(secs: number | null | undefined): { value: number; unit: IntervalUnit } {
  if (!secs || secs < 3600) return { value: 1, unit: 'days' }
  // Prefer days when the cadence is a whole number of days.
  if (secs % 86400 === 0) return { value: secs / 86400, unit: 'days' }
  return { value: Math.round(secs / 3600), unit: 'hours' }
}
// ISO ⇄ <input type="datetime-local"> ('YYYY-MM-DDTHH:mm' in local time).
function toDateTimeLocal(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
function fromDateTimeLocal(s: string): string | null {
  if (!s) return null
  const d = new Date(s)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

async function load() {
  loading.value = true
  try {
    const [linksRes, dirsRes] = await Promise.all([listReplicationLinks(), listDirectories()])
    links.value = linksRes.data
    directoryOptions.value = dirsRes.data
  } catch (e) {
    notif.error(`Failed to load: ${errMsg(e)}`)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  showForm.value = true
}

function openEdit(link: ReplicationLink) {
  editing.value = link
  const interval = secsToInterval(link.reconcileIntervalSecs)
  form.value = {
    displayName: link.displayName,
    sourceDirectoryId: link.sourceDirectoryId,
    targetDirectoryId: link.targetDirectoryId,
    sourceBaseDn: link.sourceBaseDn ?? '',
    targetBaseDn: link.targetBaseDn ?? '',
    enabled: link.enabled,
    autoCreateOnMissing: link.autoCreateOnMissing,
    attributeMappings: (link.attributeMappings ?? []).map(m => ({
      sourceAttr: m.sourceAttr, targetAttr: m.targetAttr, valueTemplate: m.valueTemplate ?? '',
    })),
    reconcileEnabled: link.reconcileEnabled ?? false,
    reconcileMode: link.reconcileMode ?? 'REVIEW',
    reconcileDeleteAction: link.reconcileDeleteAction ?? 'REVIEW',
    reconcileFirstRunAt: toDateTimeLocal(link.reconcileFirstRunAt),
    reconcileIntervalValue: interval.value,
    reconcileIntervalUnit: interval.unit,
  }
  showForm.value = true
}

async function save() {
  saving.value = true
  try {
    const f = form.value
    const payload = {
      displayName: f.displayName,
      sourceDirectoryId: f.sourceDirectoryId,
      targetDirectoryId: f.targetDirectoryId,
      enabled: f.enabled,
      autoCreateOnMissing: f.autoCreateOnMissing,
      sourceBaseDn: f.sourceBaseDn || null,
      targetBaseDn: f.targetBaseDn || null,
      attributeMappings: f.attributeMappings
        .filter(m => m.sourceAttr && m.targetAttr)
        .map(m => ({ ...m, valueTemplate: m.valueTemplate || null })),
      // Reconciliation config. The backend ignores the schedule fields
      // when reconcileEnabled is false, but we still round-trip them.
      reconcileEnabled: f.reconcileEnabled,
      reconcileMode: f.reconcileMode,
      reconcileDeleteAction: f.reconcileDeleteAction,
      reconcileFirstRunAt: fromDateTimeLocal(f.reconcileFirstRunAt),
      reconcileIntervalSecs: intervalToSecs(f.reconcileIntervalValue, f.reconcileIntervalUnit),
    }
    if (editing.value) {
      await updateReplicationLink(editing.value.id, payload)
      notif.success('Replication link updated')
    } else {
      await createReplicationLink(payload)
      notif.success('Replication link created')
    }
    showForm.value = false
    await load()
  } catch (e) {
    notif.error(`Save failed: ${errMsg(e)}`)
  } finally {
    saving.value = false
  }
}

async function reconcileNow(link: ReplicationLink) {
  try {
    await apiReconcileNow(link.id)
    notif.success(`Reconciliation started for ${link.displayName}`)
    // Refresh the runs modal if it's open for this link.
    if (showRuns.value && runsLink.value?.id === link.id) await loadRuns()
  } catch (e) {
    notif.error(`Could not start reconciliation: ${errMsg(e)}`)
  }
}

// ── Reconciliation runs modal ────────────────────────────────────────────────
const showRuns    = ref(false)
const runsLink    = ref<ReplicationLink | null>(null)
const runs        = ref<ReconRun[]>([])
const loadingRuns = ref(false)

async function openRuns(link: ReplicationLink) {
  runsLink.value = link
  showRuns.value = true
  await loadRuns()
}

async function loadRuns() {
  if (!runsLink.value) return
  loadingRuns.value = true
  try {
    const { data } = await listReconciliationRuns(runsLink.value.id, { page: 0, size: 20 })
    runs.value = data.content || []
  } catch (e) {
    notif.error(`Failed to load runs: ${errMsg(e)}`)
  } finally {
    loadingRuns.value = false
  }
}

function runHasFindings(r: ReconRun): boolean {
  return r.missingCount + r.driftCount + r.extraCount > 0
}

// ── Findings review modal ────────────────────────────────────────────────────
const showFindings    = ref(false)
const findingsRun     = ref<ReconRun | null>(null)
const findings        = ref<ReconFinding[]>([])
const loadingFindings = ref(false)
const findingStatusFilter = ref('PROPOSED')
const findingTypeFilter   = ref('')
const selectedFindings = ref<Set<string>>(new Set())
const expandedFindings = ref<Set<string>>(new Set())
const applyingFindings = ref(false)

async function openFindings(run: ReconRun) {
  findingsRun.value = run
  findingStatusFilter.value = 'PROPOSED'
  findingTypeFilter.value = ''
  selectedFindings.value = new Set()
  expandedFindings.value = new Set()
  showFindings.value = true
  await loadFindings()
}

async function loadFindings() {
  if (!findingsRun.value) return
  loadingFindings.value = true
  try {
    const params: { page: number; size: number; status?: string; type?: string } = { page: 0, size: 200 }
    if (findingStatusFilter.value) params.status = findingStatusFilter.value
    if (findingTypeFilter.value) params.type = findingTypeFilter.value
    const { data } = await getReconciliationFindings(findingsRun.value.id, params)
    findings.value = data.content || []
    // Drop selections no longer present.
    const present = new Set(findings.value.map(f => f.id))
    selectedFindings.value = new Set([...selectedFindings.value].filter(id => present.has(id)))
  } catch (e) {
    notif.error(`Failed to load findings: ${errMsg(e)}`)
  } finally {
    loadingFindings.value = false
  }
}

const proposedFindings = computed(() => findings.value.filter(f => f.status === 'PROPOSED'))
const allProposedSelected = computed(() =>
  proposedFindings.value.length > 0 && proposedFindings.value.every(f => selectedFindings.value.has(f.id)))

function toggleFinding(id: string) {
  const next = new Set(selectedFindings.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  selectedFindings.value = next
}
function toggleAllFindings() {
  selectedFindings.value = allProposedSelected.value
    ? new Set()
    : new Set(proposedFindings.value.map(f => f.id))
}
function toggleExpand(id: string) {
  const next = new Set(expandedFindings.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  expandedFindings.value = next
}

async function applySelectedFindings() {
  if (!findingsRun.value || selectedFindings.value.size === 0) return
  applyingFindings.value = true
  try {
    const { data } = await applyReconciliationFindings(findingsRun.value.id,
      { findingIds: [...selectedFindings.value] })
    notif.success(`Applied ${data.applied} finding(s) — corrections queued`)
    await loadFindings()
  } catch (e) {
    notif.error(`Apply failed: ${errMsg(e)}`)
  } finally {
    applyingFindings.value = false
  }
}

async function dismissSelectedFindings() {
  if (!findingsRun.value || selectedFindings.value.size === 0) return
  applyingFindings.value = true
  try {
    const { data } = await dismissReconciliationFindings(findingsRun.value.id,
      { findingIds: [...selectedFindings.value] })
    notif.success(`Dismissed ${data.dismissed} finding(s)`)
    await loadFindings()
  } catch (e) {
    notif.error(`Dismiss failed: ${errMsg(e)}`)
  } finally {
    applyingFindings.value = false
  }
}

/** Compact human description of a finding's diff for the expanded row. */
function findingChanges(f: ReconFinding): string[] {
  if (f.findingType === 'MISSING_IN_TARGET') {
    const attrs = (f.detail.attributes ?? {}) as Record<string, string[]>
    return Object.keys(attrs).map(k => `+ ${k}`)
  }
  if (f.findingType === 'ATTRIBUTE_DRIFT') {
    const mods = (f.detail.modifications ?? []) as Array<{ name: string; values: string[] }>
    const before = (f.detail.before ?? {}) as Record<string, string[]>
    return mods.map(m => `${m.name}: ${(before[m.name] ?? []).join(', ') || '—'} → ${m.values.join(', ')}`)
  }
  const cur = (f.detail.currentTarget ?? {}) as Record<string, string[]>
  return Object.keys(cur).map(k => `${k}: ${cur[k].join(', ')}`)
}

function confirmDelete(link: ReplicationLink) { deleteTarget.value = link }

async function doDelete() {
  if (!deleteTarget.value) return
  try {
    await deleteReplicationLink(deleteTarget.value.id)
    notif.success('Replication link deleted')
    deleteTarget.value = null
    await load()
  } catch (e) {
    notif.error(`Delete failed: ${errMsg(e)}`)
  }
}

async function openEvents(link: ReplicationLink) {
  eventsLink.value = link
  eventStatusFilter.value = ''
  eventsPage.value = 0
  showEvents.value = true
  await loadEvents()
}

async function loadEvents() {
  if (!eventsLink.value) return
  loadingEvents.value = true
  try {
    const params: { page: number; size: number; status?: string } = { page: eventsPage.value, size: 50 }
    if (eventStatusFilter.value) params.status = eventStatusFilter.value
    const { data } = await listReplicationEvents(eventsLink.value.id, params)
    events.value = data.content || []
    eventsTotalPages.value = data.totalPages || 1
  } catch (e) {
    notif.error(`Failed to load events: ${errMsg(e)}`)
  } finally {
    loadingEvents.value = false
  }
}

async function doEventAction(event: ReplicationEvent, kind: EventAction) {
  try {
    if (kind === 'retry') await retryReplicationEvent(event.id)
    else if (kind === 'skip') await skipReplicationEvent(event.id)
    else if (kind === 'ack')  await acknowledgeReplicationEvent(event.id)
    notif.success(`Event ${kind} succeeded`)
    await loadEvents()
    await load()  // refresh link health counts
  } catch (e) {
    notif.error(`Event ${kind} failed: ${errMsg(e)}`)
  }
}

function traceCorrelation(e: ReplicationEvent) {
  // Pivot to the audit log filtered by this event's source-side
  // correlation id — every row emitted while handling the originating
  // operation (the source write, its audit row, and any dispatch-side
  // dead-letter row) shares this id.
  router.push({ name: 'superadminAuditLog', query: { correlationId: e.correlationId } })
}

function canRetry(status: string) {
  return ['FAILED', 'DEAD_LETTERED', 'SKIPPED', 'ACKNOWLEDGED'].includes(status)
}
function canSkip(status: string) {
  return ['PENDING', 'FAILED', 'DEAD_LETTERED'].includes(status)
}
function statusClass(status: string) {
  switch (status) {
    case 'DELIVERED':     return 'bg-green-50 text-green-700'
    case 'PENDING':       return 'bg-blue-50 text-blue-700'
    case 'IN_FLIGHT':     return 'bg-blue-50 text-blue-700'
    case 'FAILED':        return 'bg-amber-50 text-amber-700'
    case 'DEAD_LETTERED': return 'bg-red-50 text-red-700'
    case 'SKIPPED':       return 'bg-gray-100 text-gray-600'
    case 'ACKNOWLEDGED':  return 'bg-gray-100 text-gray-600'
    default:              return 'bg-gray-100 text-gray-600'
  }
}

onMounted(load)
</script>

<style scoped>
@reference "tailwindcss";
</style>
