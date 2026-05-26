<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="min-h-screen bg-gray-50">
    <a href="#main-content"
       class="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:top-2 focus:left-2 focus:px-4 focus:py-2 focus:bg-white focus:text-blue-700 focus:rounded focus:shadow">
      Skip to main content
    </a>
    <!-- Top bar -->
    <header class="bg-white border-b border-gray-200 shadow-sm">
      <div class="max-w-4xl mx-auto px-6 h-14 flex items-center justify-between">
        <RouterLink to="/self-service/profile" class="flex items-center gap-2">
          <svg class="w-7 h-7" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" fill="none">
            <rect width="32" height="32" rx="6" fill="#2563eb"/>
            <path d="M8 8h4v12H8z" fill="#fff"/>
            <path d="M8 20h10v3H8z" fill="#fff"/>
            <path d="M20 8h4v15h-4z" fill="#fff" opacity="0.55"/>
            <circle cx="22" cy="12" r="2.5" fill="#fff" opacity="0.55"/>
          </svg>
          <span class="text-lg font-bold text-gray-900">My Account</span>
        </RouterLink>

        <div v-if="auth.isLoggedIn" class="flex items-center gap-4">
          <nav class="hidden sm:flex gap-1">
            <RouterLink to="/self-service/profile" class="nav-link">Profile</RouterLink>
            <RouterLink to="/self-service/password" class="nav-link">Password</RouterLink>
            <RouterLink to="/self-service/groups" class="nav-link">Groups</RouterLink>
          </nav>
          <div class="flex items-center gap-3 pl-4 border-l border-gray-200">
            <span class="text-sm text-gray-600">{{ auth.username }}</span>
            <button @click="handleLogout" class="text-sm text-gray-500 hover:text-gray-700">Logout</button>
          </div>
        </div>
      </div>
    </header>

    <!-- Mobile nav -->
    <div v-if="auth.isLoggedIn" class="sm:hidden bg-white border-b border-gray-200 px-4 py-2 flex gap-1">
      <RouterLink to="/self-service/profile" class="nav-link text-xs">Profile</RouterLink>
      <RouterLink to="/self-service/password" class="nav-link text-xs">Password</RouterLink>
      <RouterLink to="/self-service/groups" class="nav-link text-xs">Groups</RouterLink>
    </div>

    <!-- Page content -->
    <main id="main-content" class="max-w-4xl mx-auto px-6 py-8">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function handleLogout() {
  const logoutUrl = await auth.logout()
  if (logoutUrl) window.location.assign(logoutUrl)
  else router.push('/self-service/login')
}
</script>

<style scoped>
@reference "tailwindcss";
.nav-link {
  @apply px-3 py-1.5 rounded-lg text-sm text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors;
}
.nav-link.router-link-active {
  @apply bg-blue-50 text-blue-700;
}
</style>
