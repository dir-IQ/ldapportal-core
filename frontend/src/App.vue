<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <RouterView />
  <NotificationToast />
</template>

<script setup>
import { RouterView } from 'vue-router'
import NotificationToast from '@/components/NotificationToast.vue'
import { useTheme } from '@/composables/useTheme'

// Initialize theme on app mount
useTheme()
</script>
