<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <!--
    The DN quick look: a read-only panel docked to the right edge showing the
    entry a DN link names. Deliberately NOT modal — no backdrop, no focus
    trap — so the page underneath stays usable (keep typing in a form while
    looking something up). DN values inside the drawer are links too and
    push onto a trail, so a chain such as secDN → user → group can be
    followed and stepped back. Mounted once, by AppLayout; opened through
    useQuickLookStore() (usually via <DnLink>).
  -->
  <Teleport to="body">
    <Transition name="quicklook">
      <aside
        v-if="quickLook.current"
        ref="panelRef"
        class="quicklook-panel fixed inset-y-0 right-0 z-[60] w-full max-w-md bg-white border-l border-gray-200 shadow-xl flex flex-col"
        :aria-labelledby="titleId"
        data-testid="dn-quick-look"
        @keydown.escape.stop="quickLook.close()"
      >
        <!-- Header -->
        <div class="px-4 py-3 border-b border-gray-200 shrink-0">
          <div class="flex items-center gap-2">
            <button
              v-if="quickLook.canGoBack"
              type="button"
              class="p-1 text-gray-500 hover:text-gray-700 transition-colors"
              aria-label="Back"
              title="Back"
              @click="quickLook.back()"
            >
              <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                   stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M15 18l-6-6 6-6"/>
              </svg>
            </button>
            <h2 :id="titleId" ref="titleRef" tabindex="-1"
                class="flex-1 min-w-0 text-base font-semibold text-gray-900 truncate outline-none">
              {{ rdnLabel(quickLook.current.dn) }}
            </h2>
            <button
              type="button"
              class="p-1 text-gray-500 hover:text-gray-700 transition-colors"
              aria-label="Close quick look"
              title="Close (Esc)"
              @click="quickLook.close()"
            >
              <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                   stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M18 6 6 18M6 6l12 12"/>
              </svg>
            </button>
          </div>

          <!-- Trail of entries followed from inside the drawer. -->
          <nav v-if="quickLook.canGoBack" aria-label="Quick look trail" class="mt-2">
            <ol class="flex flex-wrap items-center gap-1 text-xs text-gray-500">
              <li v-for="(t, i) in quickLook.stack" :key="i" class="flex items-center gap-1 min-w-0">
                <span v-if="i > 0" aria-hidden="true">›</span>
                <button
                  v-if="i < quickLook.stack.length - 1"
                  type="button"
                  class="truncate max-w-[10rem] text-blue-600 hover:underline"
                  :title="t.dn"
                  @click="quickLook.goTo(i)"
                >{{ rdnLabel(t.dn) }}</button>
                <span v-else class="truncate max-w-[10rem] font-medium text-gray-700" aria-current="page"
                      :title="t.dn">{{ rdnLabel(t.dn) }}</span>
              </li>
            </ol>
          </nav>

          <div class="mt-2 text-xs font-mono text-gray-700 bg-gray-50 px-2 py-1.5 rounded flex items-start gap-2">
            <span class="flex-1 break-all" data-testid="quick-look-dn">{{ quickLook.current.dn }}</span>
            <CopyButton :text="quickLook.current.dn" />
          </div>

          <div v-if="objectClasses.length" class="mt-2 flex flex-wrap gap-1">
            <span v-for="oc in objectClasses" :key="oc" class="badge-gray">{{ oc }}</span>
          </div>
        </div>

        <!-- Body -->
        <div class="flex-1 min-h-0 overflow-y-auto px-4 py-3">
          <p v-if="loading" class="text-sm text-gray-500 text-center mt-6">Loading…</p>
          <p v-else-if="error" class="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2"
             role="alert">{{ error }}</p>
          <p v-else-if="notFound"
             class="text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
            No entry with this DN was found in the directory (or the bind account can't read it).
          </p>
          <EntryAttributeTable
            v-else-if="entry"
            :attributes="entry.attributes"
            :directory-id="quickLook.current.directoryId"
            nested
            compact
          />
        </div>

        <!-- Footer -->
        <div v-if="auth.isSuperadmin" class="px-4 py-3 border-t border-gray-200 shrink-0 flex justify-end">
          <RouterLink
            :to="{ name: 'directoryBrowser', query: { dir: quickLook.current.directoryId, dn: quickLook.current.dn } }"
            class="btn-secondary btn-sm"
            @click="quickLook.close()"
          >Open in Directory Browser</RouterLink>
        </div>
      </aside>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onUnmounted, useId } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useQuickLookStore, type QuickLookTarget } from '@/stores/quickLook'
import { browse, directoryBrowse } from '@/api/browse'
import { rdnLabel } from '@/utils/dnValue'
import CopyButton from '@/components/CopyButton.vue'
import EntryAttributeTable from './EntryAttributeTable.vue'

interface QuickLookEntry {
  dn: string
  attributes: Record<string, string[]>
}

type ApiError = {
  response?: { status?: number, data?: { detail?: string, message?: string } }
  message?: string
}

const auth = useAuthStore()
const quickLook = useQuickLookStore()
const titleId = `quick-look-title-${useId()}`

const panelRef = ref<HTMLElement | null>(null)
const titleRef = ref<HTMLElement | null>(null)
const entry = ref<QuickLookEntry | null>(null)
const loading = ref(false)
const error = ref('')

// An entry the server returned has at least an objectClass; an empty
// attribute set means the DN wasn't there to read.
const notFound = computed(() => !!entry.value && Object.keys(entry.value.attributes).length === 0)

const objectClasses = computed<string[]>(() => {
  const attrs = entry.value?.attributes ?? {}
  const key = Object.keys(attrs).find(k => k.toLowerCase() === 'objectclass')
  return key ? attrs[key].filter(oc => oc.toLowerCase() !== 'top') : []
})

// Superadmins read through the superadmin browse API; everyone else through
// the directory-scoped one, which enforces their DN scope server-side.
// limit 1: only the entry's attributes are wanted, not its children.
function fetchEntry(t: QuickLookTarget) {
  return auth.isSuperadmin
    ? browse(t.directoryId, t.dn, { limit: 1 })
    : directoryBrowse(t.directoryId, t.dn, { limit: 1 })
}

function describeError(e: unknown): string {
  const err = e as ApiError
  const status = err.response?.status
  if (status === 404) return 'No entry with this DN was found in the directory.'
  if (status === 403) return 'This entry is outside the part of the directory you can view.'
  return err.response?.data?.detail || err.response?.data?.message || err.message || 'Failed to load the entry.'
}

// Guards against an older request landing after a newer one (fast clicks
// through the trail).
let requestSeq = 0
// Where focus was before the drawer opened, to put it back on close.
let returnFocusTo: HTMLElement | null = null

watch(() => quickLook.current, async (t, prev) => {
  if (!t) {
    entry.value = null
    error.value = ''
    returnFocusTo?.focus?.()
    returnFocusTo = null
    return
  }
  if (!prev) returnFocusTo = document.activeElement as HTMLElement | null

  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  entry.value = null
  // Land keyboard and screen-reader users on the drawer without trapping
  // them there: Tab continues into the drawer's controls, and the page
  // stays reachable with the mouse or Shift-Tab.
  void nextTick(() => titleRef.value?.focus())
  try {
    const { data } = await fetchEntry(t)
    if (seq !== requestSeq) return
    entry.value = { dn: data?.dn ?? t.dn, attributes: data?.attributes ?? {} }
  } catch (e) {
    if (seq !== requestSeq) return
    error.value = describeError(e)
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}, { immediate: true })

// AppLayout unmounts on sign-out; don't reopen a stale drawer next session.
onUnmounted(() => quickLook.close())
</script>

<style scoped>
.quicklook-enter-active,
.quicklook-leave-active {
  transition: transform 0.2s ease;
}
.quicklook-enter-from,
.quicklook-leave-to {
  transform: translateX(100%);
}
@media (prefers-reduced-motion: reduce) {
  .quicklook-enter-active,
  .quicklook-leave-active {
    transition: none;
  }
}
</style>
