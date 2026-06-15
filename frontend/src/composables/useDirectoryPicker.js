// SPDX-License-Identifier: Apache-2.0
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { listDirectories } from '@/api/directories'

/**
 * Composable that provides directory picker state for superadmin pages.
 * If a dirId is in the route params, uses that. Otherwise loads the
 * directory list and provides a selectedDir ref for a picker.
 *
 * @param options.ldapOnly  If true, filters out ENTRA_ID directories (default false)
 *
 * Usage:
 *   const { dirId, directories, selectedDir, loadingDirs, showPicker } = useDirectoryPicker()
 *   const { dirId, directories, selectedDir, loadingDirs, showPicker } = useDirectoryPicker({ ldapOnly: true })
 */
export function useDirectoryPicker(options = {}) {
  const route = useRoute()
  const routeDirId = route.params.dirId

  const directories = ref([])
  const selectedDir = ref('')
  const loadingDirs = ref(false)

  const dirId = computed(() => routeDirId || selectedDir.value)
  const showPicker = computed(() => !routeDirId)

  onMounted(async () => {
    if (routeDirId) return // no need to load picker
    loadingDirs.value = true
    try {
      const { data } = await listDirectories()
      const filtered = options.ldapOnly
        ? data.filter(d => d.directoryType !== 'ENTRA_ID')
        : data
      directories.value = filtered
      if (filtered.length === 1) selectedDir.value = filtered[0].id
    } catch (e) {
      console.warn('Failed to load directories:', e)
    } finally {
      loadingDirs.value = false
    }
  })

  return { dirId, directories, selectedDir, loadingDirs, showPicker }
}
