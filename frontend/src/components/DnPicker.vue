<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="relative">
    <!-- Input with browse button -->
    <div class="flex gap-1">
      <input
        :value="modelValue"
        @input="$emit('update:modelValue', $event.target.value)"
        type="text"
        class="input flex-1 font-mono"
        :placeholder="resolvedPlaceholder"
        :aria-label="resolvedPlaceholder"
      />
      <button
        @click="openPicker"
        :disabled="!directoryId"
        type="button"
        class="btn-neutral shrink-0"
        :title="scope === 'group' ? 'Browse groups' : 'Browse directory tree'"
      >
        <svg class="w-4 h-4 text-gray-500" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 4h5l2 2h7a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"/>
        </svg>
      </button>
    </div>

    <!-- Tree picker modal -->
    <Teleport to="body">
      <div v-if="showPicker" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40" @mousedown.self="showPicker = false">
        <div v-dialog-a11y role="dialog" aria-modal="true" aria-labelledby="dnpicker-title"
             @keydown.escape="showPicker = false"
             class="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 flex flex-col" style="max-height: 70vh;">
          <div class="px-5 py-3 border-b border-gray-200 flex items-center justify-between shrink-0">
            <h3 id="dnpicker-title" class="text-sm font-semibold text-gray-900">{{ resolvedTitle }}</h3>
            <button @click="showPicker = false" aria-label="Close" class="text-gray-500 hover:text-gray-600">
              <svg class="w-5 h-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clip-rule="evenodd"/></svg>
            </button>
          </div>

          <div class="flex-1 overflow-y-auto p-3 min-h-0">
            <div v-if="treeLoading" class="text-sm text-gray-500 text-center py-8">
              {{ scope === 'group' ? 'Loading groups...' : 'Loading...' }}
            </div>
            <div v-else-if="treeNodes.length === 0" class="text-sm text-gray-500 text-center py-8">
              {{ scope === 'group' ? 'No groups found.' : 'No entries found.' }}
            </div>
            <!-- Subtree mode: lazy-loading DnTree -->
            <DnTree
              v-else-if="scope !== 'group'"
              :nodes="treeNodes"
              :selected-dn="pickerSelectedDn"
              :load-children="loadChildren"
              @select="onNodeSelect"
            />
            <!-- Group mode: pre-built GroupTree -->
            <GroupTree
              v-else
              :nodes="treeNodes"
              :selected-dn="pickerSelectedDn"
              @select="onNodeSelect"
            />
          </div>

          <div class="px-5 py-3 border-t border-gray-200 shrink-0">
            <div v-if="pickerSelectedDn" class="text-xs font-mono text-gray-600 mb-2 break-all">{{ pickerSelectedDn }}</div>
            <div class="flex justify-end gap-2">
              <button @click="showPicker = false" class="btn-neutral">Cancel</button>
              <button
                @click="confirmSelection"
                :disabled="!pickerSelectedDn"
                class="btn-primary"
              >Select</button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { browse, directoryBrowse } from '@/api/browse'
import { searchGroups } from '@/api/groups'
import { listEntraGroups } from '@/api/entra'
import DnTree from '@/components/DnTree.vue'
import GroupTree from '@/components/GroupTree.vue'

const props = defineProps({
  modelValue:    { type: String, default: '' },
  directoryId:   { type: String, default: '' },
  scope:         { type: String, default: 'subtree' },  // 'subtree' | 'group'
  title:         { type: String, default: null },
  placeholder:   { type: String, default: null },
  // subtree-only props
  superadmin:    { type: Boolean, default: true },
  /**
   * Caller-supplied list of DNs to use as the picker's roots, in
   * place of the directory's default base DN. Use case: restrict an
   * admin's DN picker to the OUs they're authorized for (one per
   * profile), so the tree doesn't expose branches the operator
   * can't act on anyway. When empty / null, the picker browses
   * from the directory base DN as before.
   */
  authorizedRoots: { type: Array, default: () => [] },
  // group-only props
  directoryType: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue'])

const showPicker       = ref(false)
const treeLoading      = ref(false)
const treeNodes        = ref([])
const pickerSelectedDn = ref('')

const resolvedTitle = computed(() => {
  if (props.title) return props.title
  return props.scope === 'group' ? 'Select Group' : 'Select DN'
})

const resolvedPlaceholder = computed(() => {
  // Use `!= null` (not truthy-check) so callers can pass an explicit
  // empty string to suppress the placeholder entirely.
  if (props.placeholder != null) return props.placeholder
  return props.scope === 'group' ? 'cn=engineers,ou=groups,dc=corp' : 'dc=example,dc=com'
})

// ── Subtree helpers ──────────────────────────────────────────────────────────

async function openSubtreePicker() {
  const browseFn = props.superadmin ? browse : directoryBrowse

  // When the caller has supplied authorizedRoots (e.g. the admin
  // bulk-import view passes the union of their profile target OUs),
  // browse each authorized OU directly and present them as the
  // picker's root nodes. Without this branch the picker would start
  // at the directory base DN — exposing the existence of every OU
  // the operator can't act on and inviting clicks that 403.
  if (props.authorizedRoots && props.authorizedRoots.length > 0) {
    const roots = []
    for (const rootDn of props.authorizedRoots) {
      try {
        const { data } = await browseFn(props.directoryId, rootDn)
        roots.push({
          dn: data.dn,
          rdn: data.dn,
          hasChildren: (data.children || []).length > 0,
          _preloaded: data.children || [],
        })
      } catch (e) {
        console.warn('Failed to load authorized root', rootDn, e)
      }
    }
    treeNodes.value = roots
    return
  }

  try {
    const { data } = await browseFn(props.directoryId)
    treeNodes.value = [{
      dn: data.dn,
      rdn: data.dn,
      hasChildren: data.children.length > 0,
      _preloaded: data.children,
    }]
  } catch (e) {
    console.warn('Failed to load directory tree:', e)
    treeNodes.value = []
  }
}

async function loadChildren(dn) {
  const rootNode = treeNodes.value.find(n => n.dn === dn)
  if (rootNode?._preloaded) {
    const children = rootNode._preloaded
    delete rootNode._preloaded
    return children
  }
  const browseFn = props.superadmin ? browse : directoryBrowse
  try {
    const { data } = await browseFn(props.directoryId, dn)
    return data.children || []
  } catch (e) {
    console.warn('Failed to load children for', dn, e)
    return []
  }
}

// ── Group helpers ────────────────────────────────────────────────────────────

/**
 * Build a tree from a flat list of group DNs.
 * Each DN is decomposed into its RDN components so parent containers
 * (OUs, DCs) appear as non-selectable structural nodes.
 */
function buildTree(groupDns) {
  const nodeMap = new Map()  // dn -> { dn, rdn, children, isGroup }

  for (const dn of groupDns) {
    // Walk from the full DN up to root, ensuring every ancestor exists
    const parts = parseDnComponents(dn)
    for (let i = 0; i < parts.length; i++) {
      const currentDn = parts.slice(i).join(',')
      if (nodeMap.has(currentDn)) continue
      nodeMap.set(currentDn, {
        dn: currentDn,
        rdn: parts[i],
        isGroup: i === 0,  // only the original DN is a group
        children: [],
      })
    }
  }

  // Link children to parents
  for (const [dn, node] of nodeMap) {
    const commaIdx = dn.indexOf(',')
    if (commaIdx === -1) continue
    const parentDn = dn.substring(commaIdx + 1)
    const parent = nodeMap.get(parentDn)
    if (parent) {
      parent.children.push(node)
    }
  }

  // Root nodes are those whose parent DN is not in the map
  const roots = []
  for (const [dn, node] of nodeMap) {
    const commaIdx = dn.indexOf(',')
    if (commaIdx === -1) {
      roots.push(node)
    } else {
      const parentDn = dn.substring(commaIdx + 1)
      if (!nodeMap.has(parentDn)) {
        roots.push(node)
      }
    }
  }

  // Sort children alphabetically at each level
  function sortChildren(node) {
    node.children.sort((a, b) => a.rdn.localeCompare(b.rdn))
    node.children.forEach(sortChildren)
  }
  roots.sort((a, b) => a.rdn.localeCompare(b.rdn))
  roots.forEach(sortChildren)

  return roots
}

/**
 * Split a DN into its RDN components, respecting escaped commas.
 */
function parseDnComponents(dn) {
  const parts = []
  let current = ''
  for (let i = 0; i < dn.length; i++) {
    if (dn[i] === '\\' && i + 1 < dn.length) {
      current += dn[i] + dn[i + 1]
      i++
    } else if (dn[i] === ',') {
      parts.push(current.trim())
      current = ''
    } else {
      current += dn[i]
    }
  }
  if (current.trim()) parts.push(current.trim())
  return parts
}

async function openGroupPicker() {
  try {
    if (props.directoryType === 'ENTRA_ID') {
      // Entra: flat list from cache (no OU tree structure)
      const { data } = await listEntraGroups(props.directoryId)
      treeNodes.value = data.map(g => ({
        dn: g.objectId,
        rdn: g.displayName || g.objectId,
        isGroup: true,
        children: [],
      }))
    } else {
      // LDAP: tree structure from group DNs
      const { data } = await searchGroups(props.directoryId, { limit: 1000, attributes: 'dn' })
      const dns = data.map(e => e.dn)
      treeNodes.value = buildTree(dns)
    }
  } catch (e) {
    console.warn('Failed to load groups:', e)
    treeNodes.value = []
  }
}

// ── Shared logic ─────────────────────────────────────────────────────────────

async function openPicker() {
  if (!props.directoryId) return
  showPicker.value = true
  pickerSelectedDn.value = props.modelValue || ''
  treeLoading.value = true
  try {
    if (props.scope === 'group') {
      await openGroupPicker()
    } else {
      await openSubtreePicker()
    }
  } finally {
    treeLoading.value = false
  }
}

function onNodeSelect(dn) {
  pickerSelectedDn.value = dn
}

function confirmSelection() {
  emit('update:modelValue', pickerSelectedDn.value)
  showPicker.value = false
}
</script>
