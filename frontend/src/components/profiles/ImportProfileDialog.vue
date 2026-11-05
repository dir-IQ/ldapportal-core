<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
/**
 * Import a provisioning-profile export file (from this or another install):
 * as a new, disabled profile, or as the user form of an existing profile.
 * "Preview" runs the import as a dry run so the operator sees which DN values
 * the base-DN rewrite changes, and any warnings, before anything is saved.
 */
import { ref, computed, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import { importProfile } from '@/api/profiles'
import { useNotificationStore } from '@/stores/notifications'

export interface ImportDirectory { id: string; displayName: string }
export interface ImportProfileRow { id: string; name: string; directoryId: string }

interface ExportFile {
  formatVersion: number
  kind: string
  source?: { directoryName?: string; baseDn?: string; profileName?: string } | null
  profile: {
    name: string
    attributeConfigs?: unknown[] | null
    groupAssignments?: unknown[] | null
  }
}

interface DnChange { field: string; from: string; to: string }
interface ImportResult { profile: { id: string; name: string } | null; dnChanges: DnChange[]; warnings: string[] }

type Mode = 'CREATE' | 'REPLACE_FORM'

const KIND = 'ldapportal.provisioning-profile'
const FORMAT_VERSION = 1

const props = defineProps<{
  modelValue: boolean
  directories: ImportDirectory[]
  profiles: ImportProfileRow[]
  /**
   * Open on "replace the user form" of this profile (the profile editor's
   * Other tab). The operator can still change any choice.
   */
  preset?: { directoryId: string; targetProfileId: string } | null
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  /** After a successful import. */
  imported: []
}>()

const notif = useNotificationStore()

const doc = ref<ExportFile | null>(null)
const fileError = ref<string>('')
const directoryId = ref<string>('')
const mode = ref<Mode>('CREATE')
const name = ref<string>('')
const targetProfileId = ref<string>('')
const rewriteBaseDn = ref<boolean>(true)
const baseDnFrom = ref<string>('')
const baseDnTo = ref<string>('')
const force = ref<boolean>(false)
const preview = ref<ImportResult | null>(null)
const busy = ref<boolean>(false)

watch(() => props.modelValue, (open) => {
  if (!open) return
  doc.value = null
  fileError.value = ''
  directoryId.value = ''
  mode.value = 'CREATE'
  name.value = ''
  targetProfileId.value = ''
  rewriteBaseDn.value = true
  baseDnFrom.value = ''
  baseDnTo.value = ''
  force.value = false
  preview.value = null
  if (props.preset) {
    directoryId.value = props.preset.directoryId
    mode.value = 'REPLACE_FORM'
    targetProfileId.value = props.preset.targetProfileId
  }
})

// Any change to the inputs makes an earlier preview stale.
watch([directoryId, mode, name, targetProfileId, rewriteBaseDn, baseDnFrom, baseDnTo, force],
  () => { preview.value = null })

const directoryProfiles = computed<ImportProfileRow[]>(() =>
  props.profiles.filter(p => p.directoryId === directoryId.value)
    .slice().sort((a, b) => a.name.localeCompare(b.name)))

const canSubmit = computed<boolean>(() => {
  if (!doc.value || !directoryId.value || busy.value) return false
  return mode.value === 'CREATE' ? !!name.value.trim() : !!targetProfileId.value
})

async function onFile(e: Event): Promise<void> {
  doc.value = null
  fileError.value = ''
  preview.value = null
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    const parsed = JSON.parse(await file.text()) as ExportFile
    if (parsed?.kind !== KIND || !parsed.profile) {
      fileError.value = "This isn't a provisioning profile export file."
      return
    }
    if (parsed.formatVersion > FORMAT_VERSION) {
      fileError.value = `This file uses profile format version ${parsed.formatVersion}, newer than this portal reads.`
      return
    }
    doc.value = parsed
    name.value = parsed.profile.name ?? ''
    baseDnFrom.value = parsed.source?.baseDn ?? ''
    rewriteBaseDn.value = !!parsed.source?.baseDn
  } catch {
    fileError.value = "The file isn't valid JSON."
  }
}

function body(dryRun: boolean) {
  const create = mode.value === 'CREATE'
  return {
    document: doc.value,
    mode: mode.value,
    name: create ? name.value.trim() : undefined,
    targetProfileId: create ? undefined : targetProfileId.value,
    rewriteBaseDn: create && rewriteBaseDn.value,
    baseDnFrom: create && rewriteBaseDn.value ? (baseDnFrom.value.trim() || undefined) : undefined,
    baseDnTo: create && rewriteBaseDn.value ? (baseDnTo.value.trim() || undefined) : undefined,
    force: create && force.value,
    dryRun,
  }
}

function errMsg(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string; message?: string } }; message?: string }
  return err.response?.data?.detail || err.response?.data?.message || err.message || 'Import failed'
}

async function run(dryRun: boolean): Promise<void> {
  if (!canSubmit.value) return
  busy.value = true
  try {
    const { data } = await importProfile(directoryId.value, body(dryRun)) as { data: ImportResult }
    if (dryRun) {
      preview.value = data
      return
    }
    notif.success(mode.value === 'CREATE'
      ? `Profile "${data.profile?.name ?? name.value}" imported (disabled)`
      : 'Form replaced from the file')
    for (const w of data.warnings ?? []) notif.warning(w)
    emit('imported')
    emit('update:modelValue', false)
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppModal :model-value="modelValue" @update:model-value="emit('update:modelValue', $event)"
            title="Import Profile" size="lg">
    <div class="space-y-4">
      <div>
        <label for="ip-file" class="block text-sm font-medium text-gray-700 mb-1">Profile export file (.json)</label>
        <input id="ip-file" type="file" accept=".json,application/json" class="block text-sm" @change="onFile" />
        <p v-if="fileError" role="alert" class="mt-1 text-sm text-red-600">{{ fileError }}</p>
        <p v-else-if="doc" class="mt-1 text-sm text-gray-600" data-testid="ip-summary">
          <strong>{{ doc.profile.name }}</strong>
          <span v-if="doc.source?.directoryName"> from {{ doc.source.directoryName }}</span>:
          {{ doc.profile.attributeConfigs?.length ?? 0 }} form fields,
          {{ doc.profile.groupAssignments?.length ?? 0 }} group assignments.
        </p>
      </div>

      <template v-if="doc">
        <div>
          <label for="ip-directory" class="block text-sm font-medium text-gray-700 mb-1">Import into directory</label>
          <select id="ip-directory" v-model="directoryId" class="input w-full">
            <option value="" disabled>Choose a directory</option>
            <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
          </select>
        </div>

        <fieldset class="space-y-1">
          <legend class="block text-sm font-medium text-gray-700 mb-1">Import as</legend>
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input v-model="mode" type="radio" value="CREATE" name="ip-mode" /> A new profile (created disabled)
          </label>
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input v-model="mode" type="radio" value="REPLACE_FORM" name="ip-mode" />
            The form of an existing profile (only its fields and form layout change)
          </label>
        </fieldset>

        <template v-if="mode === 'CREATE'">
          <div>
            <label for="ip-name" class="block text-sm font-medium text-gray-700 mb-1">Profile name</label>
            <input id="ip-name" v-model="name" class="input w-full" />
          </div>
          <div class="space-y-2">
            <label class="flex items-center gap-2 text-sm text-gray-700">
              <input id="ip-rewrite" v-model="rewriteBaseDn" type="checkbox" class="rounded border-gray-300" />
              Move DN settings to another base DN
            </label>
            <div v-if="rewriteBaseDn" class="grid grid-cols-2 gap-2 pl-6">
              <div>
                <label for="ip-from" class="block text-xs text-gray-500 mb-1">From</label>
                <input id="ip-from" v-model="baseDnFrom" class="input w-full" placeholder="The file's base DN" />
              </div>
              <div>
                <label for="ip-to" class="block text-xs text-gray-500 mb-1">To</label>
                <input id="ip-to" v-model="baseDnTo" class="input w-full" placeholder="This directory's base DN" />
              </div>
            </div>
          </div>
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input id="ip-force" v-model="force" type="checkbox" class="rounded border-gray-300" />
            Create even if the target OU or groups don't exist yet
          </label>
        </template>

        <div v-else>
          <label for="ip-target" class="block text-sm font-medium text-gray-700 mb-1">Profile to update</label>
          <select id="ip-target" v-model="targetProfileId" class="input w-full" :disabled="!directoryId">
            <option value="" disabled>{{ directoryId ? 'Choose a profile' : 'Choose a directory first' }}</option>
            <option v-for="p in directoryProfiles" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <p class="mt-1 text-xs text-amber-700">Its current form is replaced. Everything else about it stays.</p>
        </div>

        <div v-if="preview" class="border border-gray-200 rounded-lg p-3 space-y-2" data-testid="ip-preview">
          <p v-if="!preview.dnChanges.length" class="text-sm text-gray-600">No DN values change.</p>
          <table v-else class="w-full text-xs">
            <thead>
              <tr class="text-left text-gray-500">
                <th class="py-1 pr-2 font-medium">Setting</th>
                <th class="py-1 pr-2 font-medium">From</th>
                <th class="py-1 font-medium">To</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(c, i) in preview.dnChanges" :key="i" class="align-top">
                <td class="py-1 pr-2 text-gray-700">{{ c.field }}</td>
                <td class="py-1 pr-2 font-mono text-gray-500 break-all">{{ c.from }}</td>
                <td class="py-1 font-mono text-gray-900 break-all">{{ c.to }}</td>
              </tr>
            </tbody>
          </table>
          <ul v-if="preview.warnings.length" class="list-disc pl-5 text-xs text-amber-700">
            <li v-for="(w, i) in preview.warnings" :key="i">{{ w }}</li>
          </ul>
        </div>
      </template>
    </div>
    <template #footer>
      <button type="button" class="btn-neutral" @click="emit('update:modelValue', false)">Cancel</button>
      <button type="button" class="btn-secondary" :disabled="!canSubmit" @click="run(true)">Preview</button>
      <button type="button" class="btn-primary" :disabled="!canSubmit" @click="run(false)">
        {{ busy ? 'Importing…' : 'Import' }}
      </button>
    </template>
  </AppModal>
</template>
