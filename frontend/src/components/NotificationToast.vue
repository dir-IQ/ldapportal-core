<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="fixed top-4 right-4 z-50 flex flex-col gap-2 w-80 pointer-events-none"
       role="region" aria-label="Notifications">
    <TransitionGroup name="toast">
      <div
        v-for="n in store.items"
        :key="n.id"
        :role="n.type === 'error' ? 'alert' : 'status'"
        class="pointer-events-auto relative overflow-hidden rounded-xl shadow-lg bg-white text-gray-900 border border-gray-200"
      >
        <div class="flex items-start gap-3 px-4 py-3">
          <!-- Icon (colored per type) -->
          <svg v-if="n.type === 'success'" :class="['w-5 h-5 shrink-0 mt-0.5', iconClass(n.type)]" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          <svg v-else-if="n.type === 'error'" :class="['w-5 h-5 shrink-0 mt-0.5', iconClass(n.type)]" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
          </svg>
          <svg v-else-if="n.type === 'warning'" :class="['w-5 h-5 shrink-0 mt-0.5', iconClass(n.type)]" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
          </svg>
          <svg v-else :class="['w-5 h-5 shrink-0 mt-0.5', iconClass(n.type)]" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M11.25 11.25l.041-.02a.75.75 0 011.063.852l-.708 2.836a.75.75 0 001.063.853l.041-.021M21 12a9 9 0 11-18 0 9 9 0 0118 0zm-9-3.75h.008v.008H12V8.25z" />
          </svg>
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium text-gray-900">{{ n.message }}</p>
            <p v-if="n.detail" class="text-xs text-gray-600 mt-0.5">{{ n.detail }}</p>
          </div>
          <div class="flex items-center gap-1.5 shrink-0">
            <button v-if="n.action" @click="n.action.fn(); store.remove(n.id)"
              class="text-xs font-semibold underline text-gray-700 hover:text-gray-900">{{ n.action.label }}</button>
            <!-- Sticky toasts (duration=0, currently errors) get a slightly
                 bolder close affordance since clicking it is the only way
                 to dismiss them. Auto-dismissing toasts keep the lighter
                 default styling. -->
            <button
              @click="store.remove(n.id)"
              :class="n.duration === 0
                ? 'text-gray-600 hover:text-gray-900 text-lg leading-none font-semibold'
                : 'text-gray-500 hover:text-gray-700 text-lg leading-none'"
              :aria-label="n.duration === 0 ? 'Dismiss (click to acknowledge)' : 'Dismiss'"
            >&times;</button>
          </div>
        </div>
        <!-- Progress bar — only meaningful for auto-dismissing toasts.
             Sticky toasts (duration=0) hide it entirely; the absence of a
             countdown is itself a UX cue that the user has to dismiss it. -->
        <div v-if="n.duration > 0" class="h-0.5 bg-gray-200">
          <div class="h-full transition-all ease-linear" :class="progressClass(n.type)"
               :style="{ width: (n.remaining / n.duration * 100) + '%' }" />
        </div>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup>
import { useNotificationStore } from '@/stores/notifications'
const store = useNotificationStore()

function iconClass(type) {
  switch (type) {
    case 'success': return 'text-green-600'
    case 'error':   return 'text-red-600'
    case 'warning': return 'text-amber-600'
    default:        return 'text-blue-600'
  }
}

function progressClass(type) {
  switch (type) {
    case 'success': return 'bg-green-500'
    case 'error':   return 'bg-red-500'
    case 'warning': return 'bg-amber-500'
    default:        return 'bg-blue-500'
  }
}
</script>

<style scoped>
.toast-enter-active, .toast-leave-active { transition: all 0.3s ease; }
.toast-enter-from { opacity: 0; transform: translateX(100%); }
.toast-leave-to   { opacity: 0; transform: translateX(100%) scale(0.95); }
.toast-move { transition: transform 0.3s ease; }
</style>
