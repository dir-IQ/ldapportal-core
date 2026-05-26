// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface ProfileSummary {
  id: string
  name: string
  directoryId?: string
  directoryType?: string
  targetOuDn?: string | null
  enabled?: boolean
}

/**
 * Tracks the provisioning profile the admin currently has selected
 * in the sidebar picker. AppLayout writes to this; admin views
 * (Reports, Users, Groups, etc.) read it so they can scope queries
 * to the picked profile's target OU.
 *
 * Lives in a Pinia store rather than provide/inject so any descendant
 * — including modal teleports rendered outside AppLayout's tree —
 * sees the same value reactively.
 *
 * Superadmin views ignore this; their directory + scope are
 * URL-driven, not picker-driven.
 */
export const useProfilePickerStore = defineStore('profilePicker', () => {
  const profiles = ref<ProfileSummary[]>([])
  const selectedId = ref<string>('')

  const selectedProfile = computed<ProfileSummary | null>(
    () => profiles.value.find(p => p.id === selectedId.value) || null,
  )

  /** Replace the list of available profiles (called by AppLayout on load). */
  function setProfiles(list: ProfileSummary[] | null | undefined) {
    profiles.value = list || []
  }

  /** Set the picked profile id (called by AppLayout when the user picks). */
  function setSelectedId(id: string | null | undefined) {
    selectedId.value = id || ''
  }

  return { profiles, selectedId, selectedProfile, setProfiles, setSelectedId }
})
