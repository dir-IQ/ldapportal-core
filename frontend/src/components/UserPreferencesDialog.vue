<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <!-- Backdrop click is suppressed while a save is in flight: dismissing
       the dialog mid-request would revert the live-preview density (via
       cancelDialog → revertDensityIfChanged) while the request still
       resolved later, causing a flicker — and any save error would
       happen with the dialog already closed, swallowing the message. -->
  <!--
    Backdrop click intentionally does NOT close the dialog. Same
    rationale as AppModal: accidental off-target clicks would discard
    pending preference changes. Dismissal paths are the explicit × /
    Cancel buttons and Escape (preserved on the panel below for
    keyboard a11y).
  -->
  <div class="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
    <div v-dialog-a11y role="dialog" aria-modal="true" aria-labelledby="user-prefs-title"
         @keydown.escape="saving ? null : cancelDialog()"
         class="bg-white rounded-xl shadow-xl w-full max-w-lg max-h-[85vh] flex flex-col">
      <div class="flex items-center justify-between px-5 py-3 border-b border-gray-200">
        <h3 id="user-prefs-title" class="text-base font-semibold text-gray-900">User Preferences</h3>
        <button @click="cancelDialog" aria-label="Close" class="text-gray-500 hover:text-gray-600 text-lg leading-none">&times;</button>
      </div>

      <div class="overflow-y-auto p-5 space-y-6">

        <!-- Theme -->
        <section>
          <h4 class="text-sm font-semibold text-gray-700 text-gray-700 mb-2">Theme</h4>
          <div class="flex gap-2">
            <button v-for="opt in themeOptions" :key="opt.value"
              @click="form.themePreference = opt.value"
              :class="form.themePreference === opt.value ? 'btn-tab-active' : 'btn-tab'">
              {{ opt.label }}
            </button>
          </div>
        </section>

        <!-- Display density -->
        <section>
          <h4 class="text-sm font-semibold text-gray-700 mb-2">Display Density</h4>
          <p class="text-xs text-gray-500 mb-2">
            Compact reduces the vertical space used by buttons, inputs and headings so more content fits on screen.
          </p>
          <div class="flex gap-2">
            <button v-for="opt in densityOptions" :key="opt.value"
              @click="form.densityPreference = opt.value; applyDensityPreview(opt.value)"
              :class="form.densityPreference === opt.value ? 'btn-tab-active' : 'btn-tab'">
              {{ opt.label }}
            </button>
          </div>
        </section>

        <!-- Profile -->
        <section>
          <h4 class="text-sm font-semibold text-gray-700 text-gray-700 mb-2">Profile</h4>
          <div class="space-y-3">
            <div>
              <label class="block text-sm text-gray-600 text-gray-500 mb-1">Display Name</label>
              <input v-model="form.displayName" type="text" autocomplete="off"
                class="input w-full" placeholder="Your name" />
            </div>
            <div>
              <label class="block text-sm text-gray-600 text-gray-500 mb-1">Email</label>
              <input v-model="form.email" type="email" autocomplete="off"
                class="input w-full" placeholder="you@example.com" />
            </div>
          </div>
        </section>

        <!-- Change Password -->
        <section v-if="canChangePassword">
          <h4 class="text-sm font-semibold text-gray-700 text-gray-700 mb-2">Change Password</h4>
          <div class="space-y-3">
            <div>
              <label class="block text-sm text-gray-600 text-gray-500 mb-1">Current Password</label>
              <input v-model="pwForm.currentPassword" type="password" autocomplete="new-password"
                class="input w-full" />
            </div>
            <div>
              <label class="block text-sm text-gray-600 text-gray-500 mb-1">New Password</label>
              <input v-model="pwForm.newPassword" type="password" autocomplete="new-password"
                class="input w-full" />
            </div>
            <div>
              <label class="block text-sm text-gray-600 text-gray-500 mb-1">Confirm New Password</label>
              <input v-model="pwForm.confirmPassword" type="password" autocomplete="new-password"
                class="input w-full" />
            </div>
            <button @click="doChangePassword" :disabled="!canSubmitPassword || savingPw"
              class="btn-secondary text-sm">
              {{ savingPw ? 'Changing…' : 'Change Password' }}
            </button>
            <p v-if="pwError" class="text-xs text-red-600">{{ pwError }}</p>
            <p v-if="pwSuccess" class="text-xs text-green-600">{{ pwSuccess }}</p>
          </div>
        </section>
      </div>

      <!-- Footer -->
      <div class="px-5 py-3 border-t border-gray-200 flex justify-between items-center">
        <p v-if="saveError" class="text-xs text-red-600">{{ saveError }}</p>
        <div class="flex gap-2 ml-auto">
          <button @click="cancelDialog" class="btn-neutral text-sm">Cancel</button>
          <button @click="doSavePrefs" :disabled="saving" class="btn-primary text-sm">
            {{ saving ? 'Saving…' : 'Save' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'
import { useDensity, type Density } from '@/composables/useDensity'
import { updatePreferences, changePassword } from '@/api/auth'

interface ApiErrorShape {
  response?: { data?: { error?: string, detail?: string, message?: string } }
  message?: string
}

const emit = defineEmits<{ (e: 'close'): void }>()

const auth = useAuthStore()
const { setTheme } = useTheme()
const { density, setDensity } = useDensity()

const themeOptions: ReadonlyArray<{ label: string, value: string }> = [
  { label: 'Light', value: 'light' },
  { label: 'Dark', value: 'dark' },
  { label: 'System', value: 'system' },
]

const densityOptions: ReadonlyArray<{ label: string, value: Density }> = [
  { label: 'Comfortable', value: 'comfortable' },
  { label: 'Compact',     value: 'compact' },
]

// Tracks the density at which we entered the dialog OR the density that
// was last successfully saved — whichever came later. Mutable because a
// successful Save bumps it so a subsequent click-outside doesn't revert
// the freshly-saved choice. (Pre-fix bug: this was a const set at mount,
// so saving Compact and then closing the dialog reverted to Comfortable.)
let initialDensity: Density = density.value
const form = ref({
  themePreference: auth.themePreference || 'system',
  densityPreference: density.value as Density,
  displayName: (auth.principal as { displayName?: string } | null)?.displayName || '',
  email: (auth.principal as { email?: string } | null)?.email || '',
})

// Live preview: apply density immediately on click so the user can see
// the change without saving. If they cancel, restore the original.
function applyDensityPreview(value: Density): void {
  setDensity(value)
}
function revertDensityIfChanged(): void {
  if (form.value.densityPreference !== initialDensity) {
    setDensity(initialDensity)
  }
}

const saving = ref(false)
const saveError = ref('')

const canChangePassword = computed(() => {
  const t = auth.authType
  return t === 'LOCAL' || t === 'LDAP'
})

const pwForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const savingPw = ref(false)
const pwError = ref('')
const pwSuccess = ref('')

const canSubmitPassword = computed(() =>
  !!pwForm.value.currentPassword && !!pwForm.value.newPassword &&
  pwForm.value.newPassword === pwForm.value.confirmPassword &&
  pwForm.value.newPassword.length >= 8,
)

async function doSavePrefs(): Promise<void> {
  saving.value = true
  saveError.value = ''
  try {
    await updatePreferences({
      themePreference: form.value.themePreference,
      densityPreference: form.value.densityPreference,
      displayName: form.value.displayName,
      email: form.value.email,
    })
    // Apply theme immediately
    setTheme(form.value.themePreference)
    // Density was applied via live preview during the click; this is the
    // belt-and-braces "the server agreed, lock it in" call. The Account
    // entity's density_preference column (V72) carries the choice across
    // browsers / devices via /auth/me on the next session.
    setDensity(form.value.densityPreference)
    // Update auth store
    auth.updatePrincipal({
      themePreference: form.value.themePreference,
      densityPreference: form.value.densityPreference,
      displayName: form.value.displayName,
      email: form.value.email,
    })
    // Bump the "initial" density to the just-saved value. Without this,
    // a subsequent click-outside would call cancelDialog →
    // revertDensityIfChanged, which would see the form value differs
    // from the stale initialDensity and revert to it — silently undoing
    // the user's save.
    initialDensity = form.value.densityPreference
    // Close the dialog on success — the dialog dismissing IS the
    // success signal. Leaving it open after Save was unintuitive
    // (users assumed the save hadn't happened) and forced them to
    // click outside, which then triggered the revert path above.
    emit('close')
  } catch (e) {
    const err = e as ApiErrorShape
    saveError.value = err.response?.data?.error || err.response?.data?.detail || err.message || 'Save failed'
  } finally {
    saving.value = false
  }
}

function cancelDialog(): void {
  // Live preview persisted to localStorage; revert if user cancels.
  revertDensityIfChanged()
  emit('close')
}

// Exposed to tests so they can call cancelDialog directly after a Save
// to verify the initialDensity bump prevents the revert path. In normal
// runtime use the dialog closes on save and the user can't reach
// cancelDialog without remounting, so this seam doesn't change UX.
defineExpose({ cancelDialog })

async function doChangePassword(): Promise<void> {
  if (pwForm.value.newPassword !== pwForm.value.confirmPassword) {
    pwError.value = 'Passwords do not match.'
    return
  }
  savingPw.value = true
  pwError.value = ''
  pwSuccess.value = ''
  try {
    const { data } = await changePassword(pwForm.value.currentPassword, pwForm.value.newPassword)
    if (data.error) {
      pwError.value = data.error
    } else {
      pwSuccess.value = 'Password changed successfully.'
      pwForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
      setTimeout(() => { pwSuccess.value = '' }, 3000)
    }
  } catch (e) {
    const err = e as ApiErrorShape
    pwError.value = err.response?.data?.error || err.response?.data?.detail || err.message || 'Password change failed'
  } finally {
    savingPw.value = false
  }
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
