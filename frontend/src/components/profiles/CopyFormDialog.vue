<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
/**
 * "Copy form to…": replace other profiles' user form with this profile's.
 * Lists every other profile, grouped by directory, so identical dev / test /
 * prod directories can share one form definition maintained in a single
 * profile. The server copies all-or-nothing.
 */
import { ref, computed, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import { copyProfileForm } from '@/api/profiles'
import { useNotificationStore } from '@/stores/notifications'

export interface CopyFormProfile {
  id: string
  name: string
  directoryId: string
  directoryName?: string
  enabled?: boolean
}

const props = defineProps<{
  modelValue: boolean
  /** The profile whose form is copied. */
  source: CopyFormProfile | null
  /** Every profile; the source is left out of the choices. */
  profiles: CopyFormProfile[]
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  /** After a successful copy, with the ids of the updated profiles. */
  copied: [targetIds: string[]]
}>()

const notif = useNotificationStore()
const selected = ref<Set<string>>(new Set())
const busy = ref<boolean>(false)

// Start each opening with nothing ticked.
watch(() => props.modelValue, (open) => { if (open) selected.value = new Set() })

interface DirectoryGroup { directoryId: string; directoryName: string; profiles: CopyFormProfile[] }

// Other profiles grouped by directory, source's directory first, then by name.
const groups = computed<DirectoryGroup[]>(() => {
  const src = props.source
  if (!src) return []
  const byDir = new Map<string, DirectoryGroup>()
  for (const p of props.profiles) {
    if (p.id === src.id) continue
    let g = byDir.get(p.directoryId)
    if (!g) {
      g = { directoryId: p.directoryId, directoryName: p.directoryName || p.directoryId, profiles: [] }
      byDir.set(p.directoryId, g)
    }
    g.profiles.push(p)
  }
  const list = [...byDir.values()]
  list.forEach(g => g.profiles.sort((a, b) => a.name.localeCompare(b.name)))
  return list.sort((a, b) =>
    Number(b.directoryId === src.directoryId) - Number(a.directoryId === src.directoryId)
    || a.directoryName.localeCompare(b.directoryName))
})

const confirmLabel = computed<string>(() => {
  const n = selected.value.size
  return n ? `Replace form on ${n} profile${n === 1 ? '' : 's'}` : 'Replace form'
})

function toggle(id: string): void {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

function close(): void {
  emit('update:modelValue', false)
}

function errMsg(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string; message?: string } }; message?: string }
  return err.response?.data?.detail || err.response?.data?.message || err.message || 'Copy failed'
}

async function doCopy(): Promise<void> {
  const src = props.source
  if (!src || selected.value.size === 0) return
  const ids = [...selected.value]
  busy.value = true
  try {
    await copyProfileForm(src.directoryId, src.id, ids)
    notif.success(`Form copied to ${ids.length} profile${ids.length === 1 ? '' : 's'}`)
    emit('copied', ids)
    close()
  } catch (e) {
    // All-or-nothing on the server: nothing changed, keep the dialog open.
    notif.error(errMsg(e))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppModal :model-value="modelValue" @update:model-value="emit('update:modelValue', $event)"
            title="Copy Form to Other Profiles" size="md">
    <div class="space-y-3">
      <p class="text-sm text-gray-600">
        Replace the user form of the selected profiles with the form of
        <strong>{{ source?.name }}</strong><span v-if="source?.directoryName"> ({{ source.directoryName }})</span>.
      </p>
      <p class="text-xs text-gray-500">
        Copied: every form field (labels, input types, validation, defaults, computed values) and the admin,
        self-registration and self-service layouts, including where the DN field sits. Not copied: each
        profile's name, target OUs, object classes, DN mode, password policy, groups, approvals and enabled state.
      </p>

      <p v-if="groups.length === 0" class="text-sm text-gray-500">There are no other profiles.</p>
      <div v-else class="max-h-80 overflow-y-auto border border-gray-200 rounded-lg divide-y divide-gray-100">
        <fieldset v-for="g in groups" :key="g.directoryId" class="px-3 py-2">
          <legend class="text-xs font-semibold text-gray-500 uppercase tracking-wide">{{ g.directoryName }}</legend>
          <label v-for="p in g.profiles" :key="p.id"
                 class="flex items-center gap-2 py-1 text-sm text-gray-700 cursor-pointer">
            <input type="checkbox" class="rounded border-gray-300" :checked="selected.has(p.id)"
                   :data-target-id="p.id" @change="toggle(p.id)" />
            <span class="truncate">{{ p.name }}</span>
            <span v-if="p.enabled === false" class="text-xs text-gray-400">(disabled)</span>
          </label>
        </fieldset>
      </div>

      <p v-if="selected.size" class="text-xs text-amber-700">
        {{ selected.size }} profile{{ selected.size === 1 ? '' : 's' }} will lose {{ selected.size === 1 ? 'its' : 'their' }}
        current form. If any can't take this form, none are changed.
      </p>
    </div>
    <template #footer>
      <button type="button" class="btn-neutral" @click="close">Cancel</button>
      <button type="button" class="btn-primary" :disabled="selected.size === 0 || busy" @click="doCopy">
        {{ busy ? 'Copying…' : confirmLabel }}
      </button>
    </template>
  </AppModal>
</template>
