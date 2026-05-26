<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <!--
    flex-1 min-h-0 (not h-full) so the page takes the remaining column
    space inside <main>'s flex layout instead of evaluating to 100vh
    and pushing the inner two-panel layout below the viewport. The
    paired AppLayout change makes <main> a flex column.
  -->
  <div class="p-6 flex-1 min-h-0 flex flex-col">
    <h1 class="text-2xl font-bold text-gray-900 mb-4">Directory Browser</h1>
    <p class="text-sm text-gray-500 mt-1">Browse and manage the LDAP directory tree</p>

    <!-- Directory picker -->
    <div class="mb-4">
      <label for="dbv-directory" class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select id="dbv-directory" v-model="selectedDirId" class="input w-64">
        <option value="" disabled>{{ loadingDirs ? 'Loading…' : '— Select directory —' }}</option>
        <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
      </select>
    </div>

    <!-- Two-panel browser -->
    <div class="flex gap-4 flex-1 min-h-0">
      <!-- Left panel: DIT tree (1/3) -->
      <div class="w-1/3 shrink-0 bg-white border border-gray-200 rounded-xl overflow-y-auto p-3">
        <div v-if="!selectedDirId" class="text-sm text-gray-500 text-center mt-8">
          Select a directory to browse.
        </div>
        <div v-else-if="treeLoading" class="text-sm text-gray-500 text-center mt-8">Loading…</div>
        <div v-else-if="rootNodes.length === 0" class="text-sm text-gray-500 text-center mt-8">
          No entries found.
        </div>
        <!--
          :key bound to the root DN forces a fresh DnTree mount when
          the directory changes (or when the root finishes loading
          after a slow first browse). Without this, the v-if/v-else-if
          chain swaps from "Loading…" to <DnTree> while the browser
          may still be painting the stale subtree state, and the
          expand-widget on the root flickers absent until the user
          reloads. Keying on the root DN guarantees a clean mount.
        -->
        <DnTree
          v-else
          :key="rootNodes[0]?.dn || ''"
          ref="treeRef"
          :nodes="rootNodes"
          :selected-dn="selectedDn"
          :load-children="loadChildren"
          @select="selectEntry"
        />
      </div>

      <!-- Right panel: Entry details or create form (2/3) -->
      <div class="w-2/3 bg-white border border-gray-200 rounded-xl overflow-y-auto p-5">
        <!-- Create form mode -->
        <CreateEntryForm
          v-if="creatingEntry && selectedDirId && selectedDn"
          :directory-id="selectedDirId"
          :parent-dn="selectedDn"
          @created="onEntryCreated"
          @cancel="creatingEntry = false"
        />

        <!-- Edit form mode -->
        <EditEntryForm
          v-else-if="editingEntry && selectedDirId && entryDetail"
          :directory-id="selectedDirId"
          :dn="entryDetail.dn"
          :attributes="entryDetail.attributes"
          @updated="onEntryUpdated"
          @cancel="editingEntry = false"
        />

        <!-- Normal browse mode -->
        <template v-else>
          <div v-if="!selectedDn" class="text-sm text-gray-500 text-center mt-8">
            Select an entry from the tree to view its attributes.
          </div>
          <div v-else-if="detailLoading" class="text-sm text-gray-500 text-center mt-8">Loading…</div>
          <template v-else-if="entryDetail">
            <div class="flex items-end justify-between mb-4">
              <div class="flex-1 min-w-0">
                <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1">Distinguished Name</p>
                <div class="text-sm font-mono text-gray-900 bg-gray-50 px-3 py-2 rounded-lg break-all flex items-center gap-2">
                  <span class="flex-1">{{ entryDetail.dn }}</span>
                  <CopyButton :text="entryDetail.dn" />
                </div>
              </div>
              <div class="ml-3 shrink-0 relative" ref="menuRef">
                <button @click="showActionsMenu = !showActionsMenu"
                        class="btn-primary inline-flex items-center gap-1">
                  Actions
                  <span data-v-26fa19c8="" class="text-xs">▼</span>
                </button>
                <div v-if="showActionsMenu"
                     class="absolute right-0 mt-1 w-56 bg-white border border-gray-200 rounded-lg shadow-lg z-10 py-1">
                  <button @click="creatingEntry = true; showActionsMenu = false"
                          class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                    New Entry
                  </button>
                  <button @click="editingEntry = true; showActionsMenu = false"
                          class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                    Edit Entry
                  </button>
                  <button @click="openRenameModal(); showActionsMenu = false"
                          class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                    Rename Entry
                  </button>
                  <button @click="openMoveModal(); showActionsMenu = false"
                          class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                    Move Entry
                  </button>
                  <div class="border-t border-gray-100 my-1"></div>
                  <button @click="doExportLdif('base'); showActionsMenu = false"
                          class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                    Export LDIF — This Entry
                  </button>
                  <button @click="doExportLdif('one'); showActionsMenu = false"
                          class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                    Export LDIF — Direct Children
                  </button>
                  <button @click="doExportLdif('sub'); showActionsMenu = false"
                          class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                    Export LDIF — Entire Subtree
                  </button>
                  <button @click="showImportModal = true; showActionsMenu = false"
                          class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                    Import LDIF
                  </button>
                  <div class="border-t border-gray-100 my-1"></div>
                  <button @click="showDeleteConfirm = true; deleteRecursive = false; showActionsMenu = false"
                          class="w-full text-left px-4 py-2 text-sm text-red-600 hover:bg-red-50">
                    Delete Entry
                  </button>
                </div>
              </div>
            </div>

            <div v-if="Object.keys(entryDetail.attributes).length === 0" class="text-sm text-gray-500">
              No attributes returned for this entry.
            </div>
            <table v-else class="w-full text-sm">
              <thead>
                <tr class="border-b border-gray-200">
                  <th class="text-left py-2 pr-4 text-xs font-semibold text-gray-500 uppercase tracking-wider w-48">Attribute</th>
                  <th class="text-left py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Value</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-gray-50">
                <tr v-for="[attr, values] in sortedAttributes" :key="attr">
                  <td class="py-2 pr-4 text-gray-600 font-mono text-xs align-top">{{ attr }}</td>
                  <td class="py-2 text-gray-900 font-mono text-xs">
                    <div v-for="(val, i) in values" :key="i" class="break-all">{{ formatValue(val) }}</div>
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
        </template>
      </div>
    </div>

    <!-- Delete confirmation dialog -->
    <Teleport to="body">
      <div v-if="showDeleteConfirm" class="fixed inset-0 z-40 flex items-center justify-center bg-black/40">
        <div v-dialog-a11y role="dialog" aria-modal="true" aria-labelledby="browser-delete-title"
             @keydown.escape="showDeleteConfirm = false; deleteError = ''"
             class="bg-white rounded-xl shadow-xl max-w-md w-full mx-4 p-6">
          <h3 id="browser-delete-title" class="text-lg font-semibold text-gray-900 mb-2">Delete Entry</h3>
          <p class="text-sm text-gray-600 mb-3">Are you sure you want to delete this entry?</p>
          <p class="text-sm font-mono text-gray-900 bg-gray-50 px-3 py-2 rounded-lg break-all mb-3">{{ selectedDn }}</p>
          <label class="flex items-center gap-2 text-sm text-gray-700 mb-4">
            <input type="checkbox" v-model="deleteRecursive" class="rounded border-gray-300" />
            Delete recursively (include all children)
          </label>
          <div v-if="deleteError" class="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{{ deleteError }}</div>
          <div class="flex justify-end gap-3">
            <button @click="showDeleteConfirm = false; deleteError = ''"
                    :disabled="deleting"
                    class="btn-neutral">Cancel</button>
            <button @click="onDeleteConfirmed"
                    :disabled="deleting"
                    class="btn-danger">
              {{ deleting ? 'Deleting…' : 'Delete' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Move entry modal -->
    <Teleport to="body">
      <div v-if="showMoveModal" class="fixed inset-0 z-40 flex items-center justify-center bg-black/40">
        <div v-dialog-a11y role="dialog" aria-modal="true" aria-labelledby="browser-move-title"
             @keydown.escape="showMoveModal = false"
             class="bg-white rounded-xl shadow-xl max-w-md w-full mx-4 p-6">
          <h3 id="browser-move-title" class="text-lg font-semibold text-gray-900 mb-2">Move Entry</h3>
          <p class="text-sm text-gray-600 mb-1">Moving:</p>
          <p class="text-sm font-mono text-gray-900 bg-gray-50 px-3 py-2 rounded-lg break-all mb-3">{{ selectedDn }}</p>
          <label class="block text-sm font-medium text-gray-700 mb-1">New Parent DN</label>
          <div class="mb-4">
            <DnPicker v-model="moveTargetDn" :directory-id="selectedDirId" placeholder="ou=People,dc=example,dc=com" />
          </div>
          <div v-if="moveError" class="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{{ moveError }}</div>
          <div class="flex justify-end gap-3">
            <button @click="showMoveModal = false"
                    :disabled="moving"
                    class="btn-neutral">Cancel</button>
            <button @click="onMoveConfirmed"
                    :disabled="moving || !moveTargetDn.trim()"
                    class="btn-primary">
              {{ moving ? 'Moving…' : 'Move' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Rename entry modal -->
    <Teleport to="body">
      <div v-if="showRenameModal" class="fixed inset-0 z-40 flex items-center justify-center bg-black/40">
        <div v-dialog-a11y role="dialog" aria-modal="true" aria-labelledby="browser-rename-title"
             @keydown.escape="showRenameModal = false"
             class="bg-white rounded-xl shadow-xl max-w-md w-full mx-4 p-6">
          <h3 id="browser-rename-title" class="text-lg font-semibold text-gray-900 mb-2">Rename Entry</h3>
          <p class="text-sm text-gray-600 mb-1">Renaming:</p>
          <p class="text-sm font-mono text-gray-900 bg-gray-50 px-3 py-2 rounded-lg break-all mb-3">{{ selectedDn }}</p>
          <label for="dbv-new-rdn" class="block text-sm font-medium text-gray-700 mb-1">New RDN</label>
          <input id="dbv-new-rdn" v-model="renameNewRdn" type="text"
                 class="input w-full font-mono mb-4"
                 placeholder="cn=NewName" />
          <div v-if="renameError" class="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{{ renameError }}</div>
          <div class="flex justify-end gap-3">
            <button @click="showRenameModal = false"
                    :disabled="renaming"
                    class="btn-neutral">Cancel</button>
            <button @click="onRenameConfirmed"
                    :disabled="renaming || !renameNewRdn.trim()"
                    class="btn-primary">
              {{ renaming ? 'Renaming…' : 'Rename' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
    <!-- LDIF Import modal -->
    <LdifImportModal
      v-if="selectedDirId"
      v-model="showImportModal"
      :directory-id="selectedDirId"
      @imported="onLdifImported"
    />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { listDirectories } from '@/api/directories'
import { browse, deleteEntry, moveEntry, renameEntry, exportLdif, importLdif } from '@/api/browse'
import DnTree from '@/components/DnTree.vue'
import CreateEntryForm from '@/components/CreateEntryForm.vue'
import EditEntryForm from '@/components/EditEntryForm.vue'
import LdifImportModal from '@/components/LdifImportModal.vue'
import DnPicker from '@/components/DnPicker.vue'
import CopyButton from '@/components/CopyButton.vue'

const notif = useNotificationStore()

const directories   = ref([])
const loadingDirs   = ref(false)
const selectedDirId = ref('')

const treeLoading   = ref(false)
const rootNodes     = ref([])
const selectedDn    = ref('')
const detailLoading = ref(false)
const entryDetail   = ref(null)
const creatingEntry   = ref(false)
const editingEntry    = ref(false)
const treeRef         = ref(null)
const showActionsMenu   = ref(false)
const menuRef           = ref(null)
const showDeleteConfirm = ref(false)
const deleteRecursive   = ref(false)
const deleting          = ref(false)
const deleteError       = ref('')

const showMoveModal     = ref(false)
const moveTargetDn      = ref('')
const moving            = ref(false)
const moveError         = ref('')

const showRenameModal   = ref(false)
const renameNewRdn      = ref('')
const renaming          = ref(false)
const renameError       = ref('')

const showImportModal   = ref(false)

function onClickOutside(e) {
  if (menuRef.value && !menuRef.value.contains(e.target)) {
    showActionsMenu.value = false
  }
}

onMounted(() => document.addEventListener('click', onClickOutside))
onUnmounted(() => document.removeEventListener('click', onClickOutside))

const sortedAttributes = computed(() => {
  if (!entryDetail.value?.attributes) return []
  return Object.entries(entryDetail.value.attributes)
    .sort(([a], [b]) => a.localeCompare(b, undefined, { sensitivity: 'base' }))
})

// Load the tree root when directory changes
watch(selectedDirId, async (dirId) => {
  rootNodes.value = []
  selectedDn.value = ''
  entryDetail.value = null
  if (!dirId) return

  treeLoading.value = true
  try {
    const { data } = await browse(dirId)
    // Root node is the directory base DN itself
    rootNodes.value = [{
      dn: data.dn,
      rdn: data.dn,
      hasChildren: data.children.length > 0,
      _preloaded: data.children,
    }]
    // Auto-select root
    entryDetail.value = data
    selectedDn.value = data.dn
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    treeLoading.value = false
  }
})

async function loadChildren(dn) {
  // If children were preloaded (for root node), use them
  const rootNode = rootNodes.value.find(n => n.dn === dn)
  if (rootNode?._preloaded) {
    const children = rootNode._preloaded
    delete rootNode._preloaded
    return children
  }

  const { data } = await browse(selectedDirId.value, dn)
  return data.children
}

async function selectEntry(dn) {
  selectedDn.value = dn
  detailLoading.value = true
  try {
    const { data } = await browse(selectedDirId.value, dn)
    entryDetail.value = { dn: data.dn, attributes: data.attributes }
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
    entryDetail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function onEntryUpdated(browseResult) {
  editingEntry.value = false
  // Refresh entry detail from the returned browse result
  entryDetail.value = { dn: browseResult.dn, attributes: browseResult.attributes }
  notif.success('Entry updated successfully')
}

async function onDeleteConfirmed() {
  deleteError.value = ''
  deleting.value = true
  try {
    const { data: parentBrowse } = await deleteEntry(selectedDirId.value, selectedDn.value, deleteRecursive.value)
    showDeleteConfirm.value = false
    // Compute parent DN to refresh tree
    const parentDn = parentBrowse.dn
    if (treeRef.value) {
      treeRef.value.refreshNode(parentDn, parentBrowse.children)
    }
    // Select the parent after deletion
    selectedDn.value = parentDn
    entryDetail.value = { dn: parentBrowse.dn, attributes: parentBrowse.attributes }
    notif.success('Entry deleted successfully')
  } catch (e) {
    deleteError.value = e.response?.data?.detail || e.response?.data?.message || e.message
  } finally {
    deleting.value = false
  }
}

function extractParentDn(dn) {
  const idx = dn.indexOf(',')
  return idx > 0 ? dn.substring(idx + 1) : dn
}

function extractRdn(dn) {
  const idx = dn.indexOf(',')
  return idx > 0 ? dn.substring(0, idx) : dn
}

function openMoveModal() {
  moveTargetDn.value = extractParentDn(selectedDn.value)
  moveError.value = ''
  showMoveModal.value = true
}

function openRenameModal() {
  renameNewRdn.value = extractRdn(selectedDn.value)
  renameError.value = ''
  showRenameModal.value = true
}

async function onMoveConfirmed() {
  moveError.value = ''
  moving.value = true
  try {
    const { data: newParentBrowse } = await moveEntry(selectedDirId.value, selectedDn.value, moveTargetDn.value)
    showMoveModal.value = false
    // Refresh the old parent's tree node (remove the moved entry)
    const oldParentDn = extractParentDn(selectedDn.value)
    const { data: oldParentBrowse } = await browse(selectedDirId.value, oldParentDn)
    if (treeRef.value) {
      treeRef.value.refreshNode(oldParentDn, oldParentBrowse.children)
      treeRef.value.refreshNode(moveTargetDn.value, newParentBrowse.children)
    }
    // Select the entry at its new location
    const rdn = extractRdn(selectedDn.value)
    const newDn = rdn + ',' + moveTargetDn.value
    await selectEntry(newDn)
    notif.success('Entry moved successfully')
  } catch (e) {
    moveError.value = e.response?.data?.detail || e.response?.data?.message || e.message
  } finally {
    moving.value = false
  }
}

async function onRenameConfirmed() {
  renameError.value = ''
  renaming.value = true
  try {
    const { data: parentBrowse } = await renameEntry(selectedDirId.value, selectedDn.value, renameNewRdn.value)
    showRenameModal.value = false
    const parentDn = extractParentDn(selectedDn.value)
    if (treeRef.value) {
      treeRef.value.refreshNode(parentDn, parentBrowse.children)
    }
    // Select the entry at its new DN
    const newDn = renameNewRdn.value + ',' + parentDn
    await selectEntry(newDn)
    notif.success('Entry renamed successfully')
  } catch (e) {
    renameError.value = e.response?.data?.detail || e.response?.data?.message || e.message
  } finally {
    renaming.value = false
  }
}

async function onEntryCreated(browseResult) {
  creatingEntry.value = false
  // The server returned the parent's browse result — use it to refresh the tree
  if (treeRef.value) {
    treeRef.value.refreshNode(selectedDn.value, browseResult.children)
  }
  // Reload the current entry detail
  await selectEntry(selectedDn.value)
  notif.success('Entry created successfully')
}

async function doExportLdif(scope) {
  try {
    const { data } = await exportLdif(selectedDirId.value, selectedDn.value, scope)
    const url = URL.createObjectURL(data)
    const a = document.createElement('a')
    a.href = url
    a.download = 'export.ldif'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  }
}

async function onLdifImported() {
  // Refresh tree from root
  if (selectedDirId.value) {
    treeLoading.value = true
    try {
      const { data } = await browse(selectedDirId.value)
      rootNodes.value = [{
        dn: data.dn,
        rdn: data.dn,
        hasChildren: data.children.length > 0,
        _preloaded: data.children,
      }]
      entryDetail.value = data
      selectedDn.value = data.dn
    } catch (e) {
      notif.error(e.response?.data?.detail || e.message)
    } finally {
      treeLoading.value = false
    }
  }
  notif.success('LDIF import completed')
}

function formatValue(val) {
  // Detect likely binary data (contains non-printable characters)
  if (val && /[\x00-\x08\x0E-\x1F]/.test(val)) {
    return `[binary data, ${val.length} bytes]`
  }
  return val
}

onMounted(async () => {
  loadingDirs.value = true
  try {
    const { data } = await listDirectories()
    directories.value = data.filter(d => d.directoryType !== 'ENTRA_ID')
    if (directories.value.length) selectedDirId.value = directories.value[0].id
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    loadingDirs.value = false
  }
})
</script>

<style scoped>
@reference "tailwindcss";
</style>
