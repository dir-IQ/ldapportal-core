<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <ul class="dn-tree" :class="{ 'ml-4': depth > 0 }">
    <li v-for="node in nodes" :key="node.dn">
      <div
        class="flex items-center gap-1 px-2 py-1.5 rounded cursor-pointer text-sm select-none"
        :class="selectedDn === node.dn ? 'bg-blue-100 text-blue-800 font-medium' : 'text-gray-700 hover:bg-gray-100'"
        @click="select(node)"
      >
        <!-- Expand/collapse toggle -->
        <button
          v-if="node.hasChildren"
          aria-label="Toggle children"
          class="w-4 h-4 flex items-center justify-center shrink-0 text-gray-500 hover:text-gray-600"
          @click.stop="toggle(node)"
        >
          <svg
            class="w-3 h-3 transition-transform duration-150"
            :class="{ 'rotate-90': expanded.has(node.dn) }"
            viewBox="0 0 12 12" fill="currentColor"
          ><path d="M4 2l5 4-5 4z"/></svg>
        </button>
        <span v-else class="w-4 shrink-0"></span>

        <!-- Node label -->
        <span class="truncate font-mono text-[13px]">{{ node.rdn || node.dn }}</span>
        <span
          v-if="childrenMap.has(node.dn)"
          class="shrink-0 text-[10px] text-gray-600 bg-gray-100 rounded-full px-1.5 leading-4"
          :title="badgeTitle(node.dn)"
        >{{ badgeText(node.dn) }}</span>
      </div>

      <!-- Children (lazy-loaded, recursive) -->
      <template v-if="node.hasChildren && expanded.has(node.dn)">
        <div v-if="loading.has(node.dn)" class="ml-8 py-1 text-xs text-gray-500">Loading…</div>
        <template v-else-if="childrenMap.has(node.dn)">
          <DnTree
            :ref="el => setChildRef(node.dn, el)"
            :nodes="childrenMap.get(node.dn) ?? []"
            :depth="depth + 1"
            :selected-dn="selectedDn"
            :load-children="loadChildren"
            @select="(dn: string) => $emit('select', dn)"
          />
          <!-- Page footer: explains a filtered or cut-short listing and
               offers the way out of each. Only rendered when there is
               something to say, so a small, complete branch looks as
               before. -->
          <div
            v-if="footerFor(node.dn)"
            class="ml-8 my-1 flex flex-wrap items-center gap-2 text-xs text-gray-500"
            data-testid="dn-tree-footer"
          >
            <span>{{ footerFor(node.dn) }}</span>
            <button
              v-if="pageMeta.get(node.dn)?.filter"
              type="button"
              class="btn-sm"
              @click.stop="filterNode(node.dn, '')"
            >Clear filter</button>
            <button
              v-if="pageMeta.get(node.dn)?.truncated"
              type="button"
              class="btn-sm"
              :disabled="loadingAll.has(node.dn)"
              @click.stop="loadAll(node.dn)"
            >{{ loadingAll.has(node.dn) ? 'Loading…' : 'Load all' }}</button>
          </div>
        </template>
      </template>
    </li>
  </ul>
</template>

<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import { useConfirm } from '@/composables/useConfirm'
import { escapeLdapValue } from '@/composables/useLdapFilter'
import { parseLeadingRdn } from '@/utils/dn'

/** A node as rendered by the tree. Matches the API's ChildEntry shape. */
export interface DnTreeNode {
  dn: string
  rdn?: string
  hasChildren: boolean
}

/** Options the tree passes to `loadChildren` beyond the DN. */
export interface LoadChildrenOptions {
  /** Child filter text (raw LDAP filter when it starts with "("). Empty = none. */
  filter?: string
  /** Ask for every child instead of the caller's default page. */
  all?: boolean
}

/**
 * What `loadChildren` may resolve to. A plain array keeps older callers
 * (e.g. DnPicker) working unchanged: it is treated as a complete listing.
 */
export interface ChildPage {
  children: DnTreeNode[]
  truncated?: boolean
  childCount?: number | null
  childCountApproximate?: boolean
}

export type LoadChildrenFn = (dn: string, opts?: LoadChildrenOptions) => Promise<DnTreeNode[] | ChildPage>

interface PageMeta {
  truncated: boolean
  childCount: number | null
  approximate: boolean
  filter: string
  /** The listing was requested unbounded (Load all), so a reload keeps it that way. */
  all: boolean
}

/** The subset of a child DnTree's exposed API this component delegates to. */
interface DnTreeHandle {
  refreshNode: (dn: string, children: DnTreeNode[] | ChildPage) => boolean
  filterNode: (dn: string, filter: string) => Promise<boolean>
  loadAll: (dn: string) => Promise<boolean>
  getFilter: (dn: string) => string | null
  revealNode: (ancestors: string[], targetDn: string) => Promise<boolean>
  reloadNode: (dn: string) => Promise<boolean>
}

/**
 * Known counts above this ask for confirmation before a load-all: the
 * tree renders one row per child, and tens of thousands of rows is a
 * decision the operator should make knowingly.
 */
const CONFIRM_LOAD_ALL_ABOVE = 5000

const props = withDefaults(defineProps<{
  nodes: DnTreeNode[]
  depth?: number
  selectedDn?: string
  loadChildren: LoadChildrenFn
}>(), {
  depth: 0,
  selectedDn: '',
})

const emit = defineEmits<{
  (e: 'select', dn: string): void
}>()

const expanded    = reactive(new Set<string>())
const loading     = reactive(new Set<string>())
const loadingAll  = reactive(new Set<string>())
const childrenMap = ref(new Map<string, DnTreeNode[]>())
const pageMeta    = ref(new Map<string, PageMeta>())
const childRefs   = new Map<string, DnTreeHandle>()

function setChildRef(dn: string, el: unknown): void {
  if (el) {
    childRefs.set(dn, el as DnTreeHandle)
  } else {
    childRefs.delete(dn)
  }
}

function toPage(result: DnTreeNode[] | ChildPage): ChildPage {
  return Array.isArray(result) ? { children: result } : result
}

function storePage(dn: string, page: ChildPage, opts: LoadChildrenOptions): void {
  const children = new Map(childrenMap.value)
  children.set(dn, page.children ?? [])
  childrenMap.value = children

  const meta = new Map(pageMeta.value)
  meta.set(dn, {
    truncated: page.truncated === true,
    childCount: page.childCount ?? null,
    approximate: page.childCountApproximate === true,
    filter: opts.filter ?? '',
    all: opts.all === true,
  })
  pageMeta.value = meta
}

/** Fetches a page for `node` and swaps it in. Resolves false when the load failed. */
async function load(node: DnTreeNode, opts: LoadChildrenOptions): Promise<boolean> {
  const busy = opts.all ? loadingAll : loading
  busy.add(node.dn)
  try {
    const page = toPage(await props.loadChildren(node.dn, opts))
    storePage(node.dn, page, opts)
    return true
  } catch (e) {
    console.warn('Failed to load children for', node.dn, e)
    return false
  } finally {
    busy.delete(node.dn)
  }
}

async function toggle(node: DnTreeNode): Promise<void> {
  if (expanded.has(node.dn)) {
    expanded.delete(node.dn)
    return
  }

  expanded.add(node.dn)

  // Lazy-load children if not cached
  if (!childrenMap.value.has(node.dn)) {
    const ok = await load(node, {})
    if (!ok) expanded.delete(node.dn)
  }
}

function select(node: DnTreeNode): void {
  emit('select', node.dn)
  // Auto-expand on select if it has children and isn't expanded
  if (node.hasChildren && !expanded.has(node.dn)) {
    toggle(node)
  }
}

// ── Badge + footer copy ──────────────────────────────────────────────────

function countLabel(meta: PageMeta): string | null {
  if (meta.childCount == null) return null
  return (meta.approximate ? 'about ' : '') + meta.childCount.toLocaleString()
}

function badgeText(dn: string): string {
  const shown = childrenMap.value.get(dn)?.length ?? 0
  const meta = pageMeta.value.get(dn)
  if (!meta) return String(shown)
  if (meta.filter) return `${shown} of ${meta.childCount ?? '?'}`
  if (meta.childCount != null) return `${meta.approximate ? '~' : ''}${meta.childCount.toLocaleString()}`
  return meta.truncated ? `${shown}+` : String(shown)
}

function badgeTitle(dn: string): string {
  const meta = pageMeta.value.get(dn)
  if (!meta) return ''
  if (meta.filter) return `Filtered by "${meta.filter}"`
  if (meta.approximate) return 'Approximate count reported by the server'
  return ''
}

function footerFor(dn: string): string {
  const meta = pageMeta.value.get(dn)
  if (!meta) return ''
  const shown = (childrenMap.value.get(dn)?.length ?? 0).toLocaleString()
  if (meta.filter) {
    const matches = meta.truncated ? `first ${shown} matches` : `${shown} match${shown === '1' ? '' : 'es'}`
    return `Filtered by "${meta.filter}" · ${matches}`
  }
  if (meta.truncated) {
    const total = countLabel(meta)
    return total ? `Showing ${shown} of ${total} entries` : `Showing the first ${shown} entries`
  }
  return ''
}

// ── Exposed API ──────────────────────────────────────────────────────────

/**
 * Normalize a DN for comparison. LDAP DNs are case-insensitive on
 * attribute names and tolerate whitespace around the RDN separators,
 * so a server's canonical form (what tree nodes carry) can differ
 * character-for-character from a DN the app assembled by string
 * manipulation (e.g. the parent DN a delete/rename returns). Compare
 * on a normalized form so refreshNode still finds the node — otherwise
 * a formatting-only mismatch leaves the tree stale after a delete.
 */
function normDn(dn: string | null | undefined): string {
  return (dn || '').trim().toLowerCase().replace(/\s*,\s*/g, ',')
}

function findNode(dn: string): DnTreeNode | undefined {
  return props.nodes.find(n => normDn(n.dn) === normDn(dn))
}

/**
 * Refresh a node's children from externally provided data.
 * Propagates recursively through child DnTree instances until
 * the target DN is found at the correct level.
 */
function refreshNode(dn: string, children: DnTreeNode[] | ChildPage): boolean {
  // Check if the target DN is one of our direct nodes (normalized
  // comparison — see normDn).
  const node = findNode(dn)
  if (node) {
    // This is our level — update childrenMap and expand. Key by the
    // node's own dn (the template reads childrenMap/expanded by node.dn),
    // not the passed-in dn which may be a formatting variant. A refresh
    // is an unfiltered listing, so any branch filter is dropped.
    const page = toPage(children)
    storePage(node.dn, page, {})
    expanded.add(node.dn)
    node.hasChildren = page.children.length > 0
    return true
  }

  // Not at this level — delegate to child DnTree instances
  for (const [, childTree] of childRefs) {
    if (childTree?.refreshNode(dn, children)) {
      return true
    }
  }
  return false
}

/**
 * Re-list `dn`'s children with `filter` (empty string clears it) and make
 * sure the branch is open. Resolves true once a tree level owned the DN.
 */
async function filterNode(dn: string, filter: string): Promise<boolean> {
  const node = findNode(dn)
  if (node) {
    expanded.add(node.dn)
    await load(node, { filter: filter.trim() || undefined })
    return true
  }
  for (const [, childTree] of childRefs) {
    if (await childTree?.filterNode(dn, filter)) {
      return true
    }
  }
  return false
}

/** Replace `dn`'s capped listing with every child (keeping any active filter). */
async function loadAll(dn: string): Promise<boolean> {
  const node = findNode(dn)
  if (node) {
    const meta = pageMeta.value.get(node.dn)
    const known = meta?.childCount ?? null
    if (known != null && known > CONFIRM_LOAD_ALL_ABOVE) {
      const confirm = useConfirm()
      const ok = await confirm({
        title: 'Load all entries?',
        message: `This branch has ${meta?.approximate ? 'about ' : ''}${known.toLocaleString()} entries. `
          + 'Loading them all may take a while and make the tree slow to scroll.',
        confirmLabel: 'Load all',
      })
      if (!ok) return true
    }
    expanded.add(node.dn)
    await load(node, { all: true, filter: meta?.filter || undefined })
    return true
  }
  for (const [, childTree] of childRefs) {
    if (await childTree?.loadAll(dn)) {
      return true
    }
  }
  return false
}

/**
 * Open the tree along `ancestors` (this level's node first, the target's
 * parent last) so that `targetDn` is listed. Each level is loaded on demand.
 * If the parent's listing is capped or filtered and the target isn't in it,
 * the parent is re-listed with an exact filter on the target's RDN, which
 * finds it without loading the whole branch. Resolves true when the target
 * is in the tree afterwards.
 */
async function revealNode(ancestors: string[], targetDn: string): Promise<boolean> {
  if (ancestors.length === 0) return false
  const node = findNode(ancestors[0])
  if (!node) return false

  // We know this node has at least the target below it, whatever the
  // server's hint said, and the template only renders children when
  // hasChildren is set.
  node.hasChildren = true
  expanded.add(node.dn)
  if (!childrenMap.value.has(node.dn) && !(await load(node, {}))) {
    return false
  }

  if (ancestors.length > 1) {
    // Let the child DnTree for this node mount so childRefs has it.
    await nextTick()
    const child = childRefs.get(node.dn)
    return child ? child.revealNode(ancestors.slice(1), targetDn) : false
  }

  const listed = () => (childrenMap.value.get(node.dn) ?? [])
    .some(c => normDn(c.dn) === normDn(targetDn))
  if (listed()) return true

  const meta = pageMeta.value.get(node.dn)
  const rdnFilter = exactRdnFilter(targetDn)
  if ((meta?.truncated || meta?.filter) && rdnFilter) {
    await load(node, { filter: rdnFilter })
    return listed()
  }
  return false
}

/** `(uid=jsmith)` for `uid=jsmith,…` — an exact one-level filter for the entry's RDN. */
function exactRdnFilter(dn: string): string | null {
  const ava = parseLeadingRdn(dn)[0]
  return ava ? `(${ava.name}=${escapeLdapValue(ava.value)})` : null
}

/**
 * Re-fetch `dn`'s children from the server, keeping the branch's current
 * view: the same filter, and unbounded if it had been loaded in full. This
 * is what the browser calls after a create/delete/move/rename instead of
 * using the listing the mutation returned, so a capped or filtered branch
 * stays capped or filtered. A node that is in the tree but was never
 * expanded gets a first page fetched (not shown until expanded) so its
 * expand arrow reflects whether it now has children. Resolves true once a
 * tree level owned the DN.
 */
async function reloadNode(dn: string): Promise<boolean> {
  const node = findNode(dn)
  if (node) {
    const meta = pageMeta.value.get(node.dn)
    const opts: LoadChildrenOptions = { filter: meta?.filter || undefined }
    if (meta?.all) opts.all = true
    const ok = await load(node, opts)
    if (ok && !opts.filter) {
      node.hasChildren = (childrenMap.value.get(node.dn)?.length ?? 0) > 0
    }
    return true
  }
  for (const [, childTree] of childRefs) {
    if (await childTree?.reloadNode(dn)) {
      return true
    }
  }
  return false
}

/** The active filter on `dn`'s listing, '' when unfiltered, null when the DN isn't loaded here. */
function getFilter(dn: string): string | null {
  const node = findNode(dn)
  if (node) {
    return pageMeta.value.get(node.dn)?.filter ?? ''
  }
  for (const [, childTree] of childRefs) {
    const f = childTree?.getFilter(dn)
    if (f !== null && f !== undefined) return f
  }
  return null
}

defineExpose({ refreshNode, filterNode, loadAll, getFilter, revealNode, reloadNode })
</script>
