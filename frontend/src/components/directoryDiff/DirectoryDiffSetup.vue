<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <form class="bg-white border border-gray-200 rounded-xl p-5 mb-6" @submit.prevent="submit">
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-5">
        <fieldset v-for="side in sides" :key="side.key" class="border border-gray-200 rounded-lg p-4">
          <legend class="px-1 text-sm font-semibold text-gray-800">
            <span class="badge-blue mr-1">{{ side.key.toUpperCase() }}</span> Side {{ side.key.toUpperCase() }}
          </legend>
          <div class="space-y-3">
            <div>
              <label :for="`dd-${side.key}-dir`" class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
              <select :id="`dd-${side.key}-dir`" v-model="form[side.key].directoryId" class="input w-full" required>
                <option value="" disabled>{{ loadingDirs ? 'Loading…' : 'Choose a directory' }}</option>
                <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
              </select>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">Base DN</label>
              <DnPicker
                v-model="form[side.key].baseDn"
                :directory-id="form[side.key].directoryId || undefined"
                superadmin
                placeholder="The directory's base DN"
              />
            </div>
            <div class="flex gap-4">
              <fieldset>
                <legend class="block text-sm font-medium text-gray-700 mb-1">Scope</legend>
                <div class="flex gap-3 text-sm text-gray-700">
                  <label class="flex items-center gap-1.5">
                    <input v-model="form[side.key].scope" type="radio" value="SUB" /> Subtree
                  </label>
                  <label class="flex items-center gap-1.5">
                    <input v-model="form[side.key].scope" type="radio" value="ONE" /> One level
                  </label>
                </div>
              </fieldset>
            </div>
            <div>
              <label :for="`dd-${side.key}-filter`" class="block text-sm font-medium text-gray-700 mb-1">Filter (optional)</label>
              <input :id="`dd-${side.key}-filter`" v-model="form[side.key].filter" class="input w-full font-mono"
                placeholder="(objectClass=inetOrgPerson)" />
            </div>
          </div>
        </fieldset>
      </div>

      <fieldset class="mb-5">
        <legend class="block text-sm font-semibold text-gray-800 mb-2">Pair entries by</legend>
        <div class="space-y-3">
          <label class="flex items-start gap-2 text-sm text-gray-700">
            <input v-model="form.pairBy" type="radio" value="DN" class="mt-1" />
            <span>
              <span class="font-medium">DN</span>
              <span class="block text-gray-500">An entry pairs with the one at the same place below each base.</span>
              <span v-if="rebaseExample" class="block text-xs text-gray-500 font-mono mt-1" data-testid="rebase-example">
                {{ rebaseExample }}
              </span>
            </span>
          </label>
          <label class="flex items-start gap-2 text-sm text-gray-700">
            <input v-model="form.pairBy" type="radio" value="ATTRIBUTE" class="mt-1" />
            <span class="flex-1">
              <span class="font-medium">An attribute</span>
              <span class="block text-gray-500">For trees laid out differently: entries pair when this attribute has the same value on both sides.</span>
            </span>
          </label>
          <div v-if="form.pairBy === 'ATTRIBUTE'" class="ml-6 max-w-xs">
            <label for="dd-pair-attr" class="sr-only">Pairing attribute</label>
            <input id="dd-pair-attr" v-model="form.pairAttribute" class="input w-full font-mono"
              placeholder="employeeNumber" required />
          </div>
        </div>
      </fieldset>

      <fieldset class="mb-5">
        <legend class="block text-sm font-semibold text-gray-800 mb-2">Attributes to compare</legend>
        <div class="flex gap-4 text-sm text-gray-700 mb-2">
          <label class="flex items-center gap-1.5">
            <input v-model="form.attributeMode" type="radio" value="ALL" /> All user attributes
          </label>
          <label class="flex items-center gap-1.5">
            <input v-model="form.attributeMode" type="radio" value="ONLY" /> Only these…
          </label>
        </div>
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div v-if="form.attributeMode === 'ONLY'">
            <label for="dd-only" class="block text-sm font-medium text-gray-700 mb-1">Compare only</label>
            <input id="dd-only" v-model="form.only" class="input w-full font-mono" placeholder="cn, mail, member"
              required />
          </div>
          <div>
            <label for="dd-ignore" class="block text-sm font-medium text-gray-700 mb-1">Also ignore</label>
            <input id="dd-ignore" v-model="form.ignore" class="input w-full font-mono" placeholder="description" />
          </div>
        </div>
        <p class="text-xs text-gray-500 mt-2">
          Always left out: operational attributes (entryUUID, timestamps, entryCSN…) and passwords
          (userPassword, unicodePwd…), which are never compared or shown.
        </p>
      </fieldset>

      <div class="flex items-center justify-between gap-4">
        <p class="text-xs text-gray-500">
          Runs in the background; you can leave this page and come back to it. Nothing is written to either side.
        </p>
        <button type="submit" class="btn-primary" :disabled="starting || !canSubmit">
          {{ starting ? 'Starting…' : 'Compare' }}
        </button>
      </div>
    </form>

    <section class="bg-white border border-gray-200 rounded-xl p-5">
      <h2 class="text-base font-semibold text-gray-900 mb-3">Recent comparisons</h2>
      <p v-if="loadingRecent" class="text-sm text-gray-500">Loading…</p>
      <p v-else-if="recent.length === 0" class="text-sm text-gray-500">No comparisons yet.</p>
      <div v-else class="overflow-x-auto">
        <table class="w-full text-sm" data-testid="recent-table">
          <thead>
            <tr class="text-left text-gray-500 border-b border-gray-200">
              <th class="py-2 pr-3 font-medium">Started</th>
              <th class="py-2 pr-3 font-medium">A ↔ B</th>
              <th class="py-2 pr-3 font-medium text-right">Same</th>
              <th class="py-2 pr-3 font-medium text-right">Different</th>
              <th class="py-2 pr-3 font-medium text-right">Only in A</th>
              <th class="py-2 pr-3 font-medium text-right">Only in B</th>
              <th class="py-2 pr-3 font-medium">By</th>
              <th class="py-2"><span class="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in recent" :key="r.id" class="border-b border-gray-100 last:border-0">
              <td class="py-2 pr-3 whitespace-nowrap">{{ formatTime(r.startedAt) }}</td>
              <td class="py-2 pr-3">
                {{ pairLabel(r) }}
                <span v-if="r.status !== 'COMPLETE'" :class="statusBadge(r.status)" class="ml-1">
                  {{ statusText(r.status) }}
                </span>
              </td>
              <template v-if="r.status === 'COMPLETE'">
                <td class="py-2 pr-3 text-right tabular-nums">{{ n(r.counts.same) }}</td>
                <td class="py-2 pr-3 text-right tabular-nums">{{ n(r.counts.different) }}</td>
                <td class="py-2 pr-3 text-right tabular-nums">{{ n(r.counts.onlyA) }}</td>
                <td class="py-2 pr-3 text-right tabular-nums">{{ n(r.counts.onlyB) }}</td>
              </template>
              <td v-else colspan="4" class="py-2 pr-3 cell-muted">{{ r.error || '—' }}</td>
              <td class="py-2 pr-3 cell-muted">{{ r.requestedBy || '—' }}</td>
              <td class="py-2 text-right">
                <button type="button" class="btn-secondary btn-sm" @click="emit('open', r.id)">Open</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { listDirectories } from '@/api/directories'
import {
  listDirectoryDiffs, startDirectoryDiff,
  type DirectoryDiffPairing, type DirectoryDiffRequest, type DirectoryDiffRun, type DirectoryDiffScope,
  type DirectoryDiffStatus,
} from '@/api/directoryDiff'
import { useNotificationStore } from '@/stores/notifications'
import DnPicker from '@/components/DnPicker.vue'
import type { components } from '@/api/openapi'
import { errMsg, formatTime, n, pairLabel, parseNames } from './directoryDiffFormat'

type Directory = components['schemas']['DirectorySummaryResponse']

interface SideForm {
  directoryId: string
  baseDn: string
  scope: DirectoryDiffScope
  filter: string
}

interface SetupForm {
  a: SideForm
  b: SideForm
  pairBy: DirectoryDiffPairing
  pairAttribute: string
  attributeMode: 'ALL' | 'ONLY'
  only: string
  ignore: string
}

const props = defineProps<{
  /** A finished run whose settings pre-fill the form ("Run again"). */
  prefill?: DirectoryDiffRun | null
}>()

const emit = defineEmits<{
  started: [run: DirectoryDiffRun]
  open: [id: string]
}>()

const notif = useNotificationStore()

const sides: { key: 'a' | 'b' }[] = [{ key: 'a' }, { key: 'b' }]

const directories = ref<Directory[]>([])
const loadingDirs = ref(false)
const recent = ref<DirectoryDiffRun[]>([])
const loadingRecent = ref(false)
const starting = ref(false)

const form = reactive<SetupForm>({
  a: { directoryId: '', baseDn: '', scope: 'SUB', filter: '' },
  b: { directoryId: '', baseDn: '', scope: 'SUB', filter: '' },
  pairBy: 'DN',
  pairAttribute: '',
  attributeMode: 'ALL',
  only: '',
  ignore: '',
})

const canSubmit = computed(() =>
  !!form.a.directoryId && !!form.b.directoryId
  && (form.pairBy === 'DN' || form.pairAttribute.trim() !== '')
  && (form.attributeMode === 'ALL' || parseNames(form.only).length > 0))

/** How a DN on A maps onto B when the bases differ, e.g. uid=jo,ou=people,… ↔ uid=jo,ou=Users,…. */
const rebaseExample = computed(() => {
  const baseA = form.a.baseDn.trim()
  const baseB = form.b.baseDn.trim()
  if (!baseA || !baseB || baseA.toLowerCase() === baseB.toLowerCase()) return ''
  return `Bases differ, so A's base is swapped for B's: uid=jo,${baseA} ↔ uid=jo,${baseB}`
})

function applyPrefill(run: DirectoryDiffRun) {
  for (const key of ['a', 'b'] as const) {
    form[key].directoryId = run[key].directoryId
    form[key].baseDn = run[key].baseDn
    form[key].scope = run[key].scope
    form[key].filter = run[key].filter === '(objectClass=*)' ? '' : run[key].filter
  }
  form.pairBy = run.pairBy
  form.pairAttribute = run.pairAttribute ?? ''
  form.attributeMode = run.attributes.length ? 'ONLY' : 'ALL'
  form.only = run.attributes.join(', ')
  form.ignore = run.ignore.join(', ')
}

watch(() => props.prefill, run => { if (run) applyPrefill(run) }, { immediate: true })

function statusText(status: DirectoryDiffStatus): string {
  return status === 'RUNNING' ? 'Running' : status === 'CANCELLED' ? 'Cancelled' : status === 'FAILED' ? 'Failed' : ''
}

function statusBadge(status: DirectoryDiffStatus): string {
  return status === 'RUNNING' ? 'badge-blue' : status === 'FAILED' ? 'badge-red' : 'badge-gray'
}

async function loadDirectories() {
  loadingDirs.value = true
  try {
    const { data } = await listDirectories()
    directories.value = data.filter(d => d.directoryType !== 'ENTRA_ID' && d.enabled !== false)
  } catch (e) {
    notif.error(errMsg(e, 'Could not load directories'))
  } finally {
    loadingDirs.value = false
  }
}

async function loadRecent() {
  loadingRecent.value = true
  try {
    const { data } = await listDirectoryDiffs()
    recent.value = data
  } catch (e) {
    notif.error(errMsg(e, 'Could not load recent comparisons'))
  } finally {
    loadingRecent.value = false
  }
}

async function submit() {
  if (!canSubmit.value || starting.value) return
  const req: DirectoryDiffRequest = {
    a: side(form.a),
    b: side(form.b),
    pairBy: form.pairBy,
    pairAttribute: form.pairBy === 'ATTRIBUTE' ? form.pairAttribute.trim() : null,
    attributes: form.attributeMode === 'ONLY' ? parseNames(form.only) : [],
    ignore: parseNames(form.ignore),
  }
  starting.value = true
  try {
    const { data } = await startDirectoryDiff(req)
    notif.success('Comparison started')
    emit('started', data)
  } catch (e) {
    notif.error(errMsg(e, 'Could not start the comparison'))
  } finally {
    starting.value = false
  }
}

function side(s: SideForm) {
  return {
    directoryId: s.directoryId,
    baseDn: s.baseDn.trim() || null,
    scope: s.scope,
    filter: s.filter.trim() || null,
  }
}

onMounted(() => {
  loadDirectories()
  loadRecent()
})
</script>
