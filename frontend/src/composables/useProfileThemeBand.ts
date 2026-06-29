// SPDX-License-Identifier: Apache-2.0
import { computed, type ComputedRef, type Ref } from 'vue'
import { onThemeTitleClass, onThemeMutedClass } from '@/utils/themeColor'

/**
 * Derives the page-header "theme band" presentation for a provisioning profile.
 *
 * When the profile carries a theme colour, the admin user/group list page
 * headers render a band of that colour with legible foreground classes; when
 * it's unset they fall back to the default styling — title in gray, profile
 * name in blue. Returned values are individual computed refs so callers can
 * destructure them in `<script setup>` and bind directly in the template.
 */
export function useProfileThemeBand(
  color: Ref<string | null | undefined> | ComputedRef<string | null | undefined>,
) {
  const themed = computed(() => !!color.value)
  return {
    /** True when a theme colour is set (header renders as a band). */
    themed,
    /** Inline background for the banded header wrapper (empty when unset). */
    bandStyle: computed(() => (themed.value ? { backgroundColor: color.value as string } : {})),
    /** Padding/rounding added to the header wrapper only when banded. */
    bandClass: computed(() => (themed.value ? 'rounded-lg px-4 py-3' : '')),
    /** Page-title colour — contrast on the band, gray-900 otherwise. */
    titleClass: computed(() => (themed.value ? onThemeTitleClass(color.value) : 'text-gray-900')),
    /** Secondary/subtitle copy colour. */
    mutedClass: computed(() => (themed.value ? onThemeMutedClass(color.value) : 'text-gray-500')),
    /** Faint connector ("—", "profile") colour. */
    faintClass: computed(() => (themed.value ? onThemeMutedClass(color.value) : 'text-gray-400')),
    /** Profile-name token — inherits the title colour when banded, else blue. */
    profileNameClass: computed(() => (themed.value ? '' : 'text-blue-600')),
  }
}
