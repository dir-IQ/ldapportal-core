<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <!--
    A DN shown as a link to the entry it names. A plain click opens the
    quick-look drawer beside the page (without navigating); Ctrl/Cmd/Shift-
    click or middle-click follows the href, opening the full Directory
    Browser at that entry in a new tab or window. Admins below superadmin
    can't open the browser route, so they get a button with no href.
    Clicks never bubble, so a DN inside a clickable table row doesn't also
    trigger the row.
  -->
  <a
    v-if="auth.isSuperadmin"
    :href="href"
    class="dn-link font-mono text-blue-600 underline decoration-dotted underline-offset-2 hover:decoration-solid break-all"
    :title="`Quick look at ${dn} (Ctrl/⌘-click to open in the Directory Browser)`"
    @click="onClick"
    @auxclick.stop
  ><slot>{{ dn }}</slot></a>
  <button
    v-else
    type="button"
    class="dn-link font-mono text-left text-blue-600 underline decoration-dotted underline-offset-2 hover:decoration-solid break-all"
    :title="`Quick look at ${dn}`"
    @click.stop="openDrawer"
  ><slot>{{ dn }}</slot></button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useQuickLookStore } from '@/stores/quickLook'
import { browserHref } from '@/components/sync/syncLinks'

const props = withDefaults(defineProps<{
  dn: string
  directoryId: string
  /**
   * Inside the drawer: push onto its trail (so Back returns here) instead
   * of starting a fresh one.
   */
  nested?: boolean
}>(), { nested: false })

const auth = useAuthStore()
const quickLook = useQuickLookStore()

const href = computed(() => browserHref(props.directoryId, props.dn))

function openDrawer(): void {
  if (props.nested) quickLook.push(props.directoryId, props.dn)
  else quickLook.open(props.directoryId, props.dn)
}

function onClick(e: MouseEvent): void {
  e.stopPropagation()
  // Let the browser handle "open in new tab/window" gestures.
  if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey || e.button !== 0) return
  e.preventDefault()
  openDrawer()
}
</script>
