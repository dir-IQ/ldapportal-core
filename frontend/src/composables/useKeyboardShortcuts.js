// SPDX-License-Identifier: Apache-2.0
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'

/**
 * Register global keyboard shortcuts.
 * Shortcuts are disabled when the active element is an input, textarea, or select.
 */
export function useKeyboardShortcuts({ dirId, onNewUser, onSearch } = {}) {
  const router = useRouter()
  const showHelp = ref(false)
  let pendingG = false
  let gTimer = null

  function isInputFocused() {
    const el = document.activeElement
    if (!el) return false
    const tag = el.tagName
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
  }

  function handler(e) {
    if (isInputFocused()) return

    const key = e.key

    // Esc — close modals (let the event bubble, but also toggle help off)
    if (key === 'Escape') {
      showHelp.value = false
      return
    }

    // ? — show help overlay
    if (key === '?') {
      e.preventDefault()
      showHelp.value = !showHelp.value
      return
    }

    // / — focus search
    if (key === '/') {
      e.preventDefault()
      if (onSearch) {
        onSearch()
      } else {
        const searchInput = document.querySelector('input[placeholder*="filter"], input[placeholder*="search"], input[placeholder*="Search"]')
        if (searchInput) searchInput.focus()
      }
      return
    }

    // n — new user
    if (key === 'n') {
      e.preventDefault()
      if (onNewUser) onNewUser()
      return
    }

    // Two-key sequences starting with g
    if (key === 'g' && !pendingG) {
      pendingG = true
      gTimer = setTimeout(() => { pendingG = false }, 800)
      return
    }

    if (pendingG) {
      clearTimeout(gTimer)
      pendingG = false

      const dir = typeof dirId === 'function' ? dirId() : dirId
      if (!dir) return

      if (key === 'u') {
        e.preventDefault()
        router.push(`/directories/${dir}/users`)
      } else if (key === 'g') {
        e.preventDefault()
        router.push(`/directories/${dir}/groups`)
      } else if (key === 'a') {
        e.preventDefault()
        router.push(`/directories/${dir}/audit`)
      }
      return
    }
  }

  onMounted(() => document.addEventListener('keydown', handler))
  onUnmounted(() => {
    document.removeEventListener('keydown', handler)
    clearTimeout(gTimer)
  })

  return { showHelp }
}

export const SHORTCUTS = [
  { keys: '/', description: 'Focus search' },
  { keys: 'n', description: 'New user' },
  { keys: 'g u', description: 'Go to Users' },
  { keys: 'g g', description: 'Go to Groups' },
  { keys: 'g a', description: 'Go to Audit Log' },
  { keys: 'Esc', description: 'Close modal / overlay' },
  { keys: '?', description: 'Show this help' },
]
