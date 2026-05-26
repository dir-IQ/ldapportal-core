<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { discoverDirectory, commitDiscovery } from '@/api/discovery'
import { getDirectory } from '@/api/directories'
import PageContainer from '@/components/PageContainer.vue'

const route = useRoute()
const router = useRouter()
const notif = useNotificationStore()
const directoryId = route.params.directoryId

// ── State ─────────────────────────────────────────────────────────────────
const step = ref(1)
const directory = ref(null)
const loading = ref(false)
const scanning = ref(false)
const committing = ref(false)
const error = ref(null)

// Scan config
const rootDn = ref('')
const sampleSize = ref(20)
const includeGroups = ref(true)

// Proposal data
const proposal = ref(null)

// User selections per profile (keyed by targetOuDn)
const profileSelections = ref({})

// ── Load directory info ───────────────────────────────────────────────────
onMounted(async () => {
  try {
    const { data } = await getDirectory(directoryId)
    directory.value = data
    rootDn.value = data.baseDn || ''
  } catch (e) {
    error.value = 'Failed to load directory: ' + (e.response?.data?.detail || e.message)
  }
})

// ── Step 1: Run discovery scan ────────────────────────────────────────────
async function runDiscovery() {
  scanning.value = true
  error.value = null
  try {
    const { data } = await discoverDirectory(directoryId, {
      rootDn: rootDn.value || null,
      sampleSize: sampleSize.value,
      includeGroups: includeGroups.value,
    })
    proposal.value = data

    // Initialize profile selections
    const sel = {}
    for (const p of data.profiles) {
      sel[p.targetOuDn] = {
        included: !p.alreadyConfigured,
        name: p.name,
        objectClasses: [...p.objectClasses],
        rdnAttribute: p.rdnAttribute,
        attributes: p.attributeConfigs.map(a => ({
          ...a,
          included: !a.hidden,
        })),
        groupAssignments: p.groupCandidates.map(g => ({
          ...g,
          included: g.overlapPercent >= 80,
        })),
      }
    }
    profileSelections.value = sel
    step.value = 2
  } catch (e) {
    error.value = 'Discovery failed: ' + (e.response?.data?.detail || e.message)
  } finally {
    scanning.value = false
  }
}

// ── Computed helpers ──────────────────────────────────────────────────────
const includedProfiles = computed(() => {
  if (!proposal.value) return []
  return proposal.value.profiles.filter(p => profileSelections.value[p.targetOuDn]?.included)
})

const commitSummary = computed(() => {
  const profiles = includedProfiles.value
  let totalAttrs = 0
  let totalGroups = 0
  const userBaseDns = []
  const groupBaseDns = []

  for (const p of profiles) {
    const sel = profileSelections.value[p.targetOuDn]
    totalAttrs += sel.attributes.filter(a => a.included).length
    totalGroups += sel.groupAssignments.filter(g => g.included).length
    userBaseDns.push(p.targetOuDn)
  }

  if (proposal.value?.groupOUs) {
    for (const g of proposal.value.groupOUs) {
      groupBaseDns.push(g.dn)
    }
  }

  return { profileCount: profiles.length, totalAttrs, totalGroups, userBaseDns, groupBaseDns }
})

// ── Step 5: Commit ────────────────────────────────────────────────────────
async function doCommit() {
  committing.value = true
  error.value = null
  try {
    const profiles = includedProfiles.value.map(p => {
      const sel = profileSelections.value[p.targetOuDn]
      return {
        name: sel.name,
        targetOuDn: p.targetOuDn,
        objectClassNames: sel.objectClasses,
        rdnAttribute: sel.rdnAttribute,
        showDnField: false,
        enabled: true,
        selfRegistrationAllowed: false,
        autoIncludeGroups: false,
        excludeAutoIncludes: false,
        attributeConfigs: sel.attributes
          .filter(a => a.included)
          .map(a => ({
            attributeName: a.attributeName,
            customLabel: a.suggestedLabel,
            inputType: a.inputType,
            requiredOnCreate: a.requiredOnCreate,
            editableOnCreate: true,
            editableOnUpdate: true,
            selfServiceEdit: false,
            selfRegistrationEdit: false,
            hidden: false,
          })),
        groupAssignments: sel.groupAssignments
          .filter(g => g.included)
          .map(g => ({
            groupDn: g.groupDn,
            memberAttribute: g.memberAttribute,
          })),
      }
    })

    const { data } = await commitDiscovery(directoryId, {
      profiles,
      userBaseDns: commitSummary.value.userBaseDns,
      groupBaseDns: commitSummary.value.groupBaseDns,
    })

    notif.success(
      `Discovery complete: ${data.profilesCreated} profiles created, ` +
      `${data.userBaseDnsAdded} user base DNs added, ${data.groupBaseDnsAdded} group base DNs added.`)

    if (data.warnings?.length) {
      for (const w of data.warnings) notif.warning(w)
    }

    router.push('/superadmin/profiles')
  } catch (e) {
    error.value = 'Commit failed: ' + (e.response?.data?.detail || e.message)
  } finally {
    committing.value = false
  }
}

// ── Attribute actions ─────────────────────────────────────────────────────
function includeAllAttrs(ouDn) {
  profileSelections.value[ouDn].attributes.forEach(a => a.included = true)
}
function includePopulatedOnly(ouDn) {
  profileSelections.value[ouDn].attributes.forEach(a => a.included = !a.hidden)
}
function resetAttrs(ouDn) {
  const profile = proposal.value.profiles.find(p => p.targetOuDn === ouDn)
  if (!profile) return
  profileSelections.value[ouDn].attributes = profile.attributeConfigs.map(a => ({
    ...a,
    included: !a.hidden,
  }))
}

// ── Input type options ────────────────────────────────────────────────────
const inputTypeOptions = [
  'TEXT', 'TEXTAREA', 'PASSWORD', 'BOOLEAN', 'DATETIME',
  'MULTI_VALUE', 'DN_LOOKUP', 'SELECT', 'HIDDEN_FIXED',
]

// ── Step navigation ───────────────────────────────────────────────────────
const stepLabels = ['Configure', 'Review OUs', 'Profiles', 'Groups', 'Commit']
const maxStep = computed(() => includeGroups.value ? 5 : 4)
</script>

<template>
  <PageContainer variant="wizard">
    <!-- Header -->
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Directory Discovery Wizard</h1>
        <p v-if="directory" class="text-sm text-gray-500 mt-0.5">{{ directory.displayName || directory.name }}</p>
      </div>
      <button @click="router.push('/superadmin/directories')" class="btn-neutral btn-compact">Cancel</button>
    </div>

    <!-- Step indicator -->
    <nav class="flex items-center gap-2 mb-8">
      <template v-for="(label, i) in stepLabels" :key="i">
        <div v-if="i > 0" class="h-px flex-1 bg-gray-200" :class="step > i ? 'bg-blue-400' : ''"></div>
        <button
          class="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium transition-colors"
          :class="step === i + 1
            ? 'bg-blue-600 text-white'
            : step > i + 1
              ? 'bg-blue-100 text-blue-700 cursor-pointer'
              : 'bg-gray-100 text-gray-600 cursor-default'"
          :disabled="step <= i + 1"
          @click="step > i + 1 && (step = i + 1)">
          <span class="w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold"
                :class="step > i + 1 ? 'bg-blue-600 text-white' : step === i + 1 ? 'bg-white/20' : 'bg-gray-200 text-gray-600'">
            {{ step > i + 1 ? '✓' : i + 1 }}
          </span>
          {{ label }}
        </button>
      </template>
    </nav>

    <!-- Error banner -->
    <div v-if="error" class="bg-red-50 border border-red-200 rounded-xl p-4 mb-6 text-red-700 text-sm">
      {{ error }}
    </div>

    <!-- ══════════════════════════════════════════════════════════════════ -->
    <!-- Step 1: Configure Scan -->
    <!-- ══════════════════════════════════════════════════════════════════ -->
    <div v-if="step === 1" class="space-y-6">
      <div class="bg-white border border-gray-200 rounded-xl p-6 shadow-sm">
        <h2 class="text-lg font-semibold text-gray-900 mb-4">Scan Configuration</h2>

        <div v-if="directory" class="grid grid-cols-2 gap-4 mb-6 text-sm">
          <div><span class="text-gray-500">Host:</span> <span class="font-medium">{{ directory.host }}:{{ directory.port }}</span></div>
          <div><span class="text-gray-500">Base DN:</span> <span class="font-medium">{{ directory.baseDn }}</span></div>
        </div>

        <div class="space-y-4">
          <div>
            <label for="dw-root-dn" class="block text-sm font-medium text-gray-700 mb-1">Root DN (optional)</label>
            <input id="dw-root-dn" v-model="rootDn" class="input w-full" placeholder="Leave blank to use directory base DN" />
            <p class="text-xs text-gray-500 mt-1">Start scanning from this DN instead of the directory base DN.</p>
          </div>

          <div>
            <label for="dw-sample-size" class="block text-sm font-medium text-gray-700 mb-1">Sample Size: {{ sampleSize }}</label>
            <input id="dw-sample-size" type="range" v-model.number="sampleSize" min="5" max="50" step="5"
                   class="w-full h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer" />
            <p class="text-xs text-gray-500 mt-1">Number of entries sampled per OU to detect populated attributes.</p>
          </div>

          <div class="flex items-center gap-2">
            <input type="checkbox" v-model="includeGroups" id="includeGroups" class="rounded" />
            <label for="includeGroups" class="text-sm text-gray-700">Include group analysis</label>
          </div>
        </div>

        <div class="mt-6">
          <button @click="runDiscovery" :disabled="scanning" class="btn-primary">
            <template v-if="scanning">
              <svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-white inline" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
              </svg>
              Scanning directory...
            </template>
            <template v-else>Start Discovery</template>
          </button>
        </div>
      </div>
    </div>

    <!-- ══════════════════════════════════════════════════════════════════ -->
    <!-- Step 2: Review Discovered OUs -->
    <!-- ══════════════════════════════════════════════════════════════════ -->
    <div v-if="step === 2 && proposal" class="space-y-6">
      <!-- Warnings -->
      <div v-if="proposal.warnings?.length" class="bg-amber-50 border border-amber-200 rounded-xl p-4">
        <h3 class="text-sm font-semibold text-amber-800 mb-2">Warnings</h3>
        <ul class="text-sm text-amber-700 space-y-1">
          <li v-for="(w, i) in proposal.warnings" :key="i">{{ w }}</li>
        </ul>
      </div>

      <!-- User OUs -->
      <div class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        <div class="px-5 py-3 border-b border-gray-100 flex items-center justify-between">
          <h2 class="text-sm font-semibold text-gray-700">Discovered User OUs ({{ proposal.profiles.length }})</h2>
          <p class="text-xs text-gray-500">Select which OUs to create provisioning profiles for</p>
        </div>
        <div v-if="!proposal.profiles.length" class="px-5 py-8 text-center text-gray-500 text-sm">
          No user OUs discovered.
        </div>
        <table v-else class="w-full text-sm">
          <thead class="bg-gray-50">
            <tr>
              <th class="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase">Create Profile</th>
              <th class="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase">OU</th>
              <th class="px-4 py-2.5 text-right text-xs font-semibold text-gray-500 uppercase">Users</th>
              <th class="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase">Object Classes</th>
              <th class="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase">RDN</th>
              <th class="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase">Status</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            <tr v-for="p in proposal.profiles" :key="p.targetOuDn" class="hover:bg-gray-50">
              <td class="px-4 py-2.5">
                <input type="checkbox"
                       v-model="profileSelections[p.targetOuDn].included"
                       :disabled="p.alreadyConfigured"
                       class="rounded" />
              </td>
              <td class="px-4 py-2.5">
                <div class="font-medium text-gray-900">{{ p.name }}</div>
                <div class="text-xs text-gray-500 truncate max-w-xs" :title="p.targetOuDn">{{ p.targetOuDn }}</div>
              </td>
              <td class="px-4 py-2.5 text-right">
                <span class="font-medium">{{ p.estimatedUserCount >= 1001 ? '1000+' : p.estimatedUserCount }}</span>
              </td>
              <td class="px-4 py-2.5">
                <div class="flex flex-wrap gap-1">
                  <span v-for="oc in p.objectClasses" :key="oc" class="badge-gray text-xs">{{ oc }}</span>
                </div>
              </td>
              <td class="px-4 py-2.5 text-gray-600">{{ p.rdnAttribute }}</td>
              <td class="px-4 py-2.5">
                <span v-if="p.alreadyConfigured" class="badge-gray text-xs">Already configured</span>
                <span v-else-if="p.estimatedUserCount < 5" class="text-amber-600 text-xs font-medium">Few users</span>
                <span v-else class="text-green-600 text-xs font-medium">Ready</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Group OUs -->
      <div v-if="proposal.groupOUs?.length" class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        <div class="px-5 py-3 border-b border-gray-100">
          <h2 class="text-sm font-semibold text-gray-700">Discovered Group OUs ({{ proposal.groupOUs.length }})</h2>
        </div>
        <table class="w-full text-sm">
          <thead class="bg-gray-50">
            <tr>
              <th class="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase">OU</th>
              <th class="px-4 py-2.5 text-right text-xs font-semibold text-gray-500 uppercase">Groups</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            <tr v-for="g in proposal.groupOUs" :key="g.dn" class="hover:bg-gray-50">
              <td class="px-4 py-2.5">
                <div class="font-medium text-gray-900">{{ g.name }}</div>
                <div class="text-xs text-gray-500">{{ g.dn }}</div>
              </td>
              <td class="px-4 py-2.5 text-right font-medium">{{ g.groupCount }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="flex justify-between">
        <button @click="step = 1" class="btn-neutral">Back</button>
        <button @click="step = 3" :disabled="!includedProfiles.length" class="btn-primary">
          Next: Review Profiles ({{ includedProfiles.length }})
        </button>
      </div>
    </div>

    <!-- ══════════════════════════════════════════════════════════════════ -->
    <!-- Step 3: Review Proposed Profiles -->
    <!-- ══════════════════════════════════════════════════════════════════ -->
    <div v-if="step === 3 && proposal" class="space-y-6">
      <div v-for="p in includedProfiles" :key="p.targetOuDn"
           class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        <div class="px-5 py-4 border-b border-gray-100 flex items-center justify-between">
          <div class="flex-1 mr-4">
            <label class="text-xs text-gray-500">Profile Name</label>
            <input v-model="profileSelections[p.targetOuDn].name" aria-label="Profile name"
                   class="input w-full mt-1" />
          </div>
          <div class="text-right text-xs text-gray-500">
            <div>{{ p.targetOuDn }}</div>
            <div>~{{ p.estimatedUserCount }} users</div>
          </div>
        </div>

        <div class="px-5 py-3 border-b border-gray-50 flex items-center gap-3">
          <span class="text-xs text-gray-500">Object Classes:</span>
          <div class="flex flex-wrap gap-1">
            <span v-for="oc in profileSelections[p.targetOuDn].objectClasses" :key="oc"
                  class="badge-gray text-xs">{{ oc }}</span>
          </div>
          <span class="text-xs text-gray-500 ml-4">RDN:</span>
          <input v-model="profileSelections[p.targetOuDn].rdnAttribute" aria-label="RDN attribute"
                 class="input input-sm w-24" />
        </div>

        <!-- Attribute table -->
        <div class="px-5 py-3 border-b border-gray-50 flex items-center justify-between">
          <h3 class="text-sm font-medium text-gray-700">
            Attributes ({{ profileSelections[p.targetOuDn].attributes.filter(a => a.included).length }}/{{ profileSelections[p.targetOuDn].attributes.length }})
          </h3>
          <div class="flex gap-2">
            <button @click="includeAllAttrs(p.targetOuDn)" class="text-xs text-blue-600 hover:text-blue-800">All</button>
            <button @click="includePopulatedOnly(p.targetOuDn)" class="text-xs text-blue-600 hover:text-blue-800">Populated only</button>
            <button @click="resetAttrs(p.targetOuDn)" class="text-xs text-gray-500 hover:text-gray-700">Reset</button>
          </div>
        </div>

        <div class="max-h-80 overflow-y-auto">
          <table class="w-full text-sm">
            <thead class="bg-gray-50 sticky top-0">
              <tr>
                <th class="px-4 py-2 text-left text-xs font-semibold text-gray-500 w-10"></th>
                <th class="px-4 py-2 text-left text-xs font-semibold text-gray-500">Attribute</th>
                <th class="px-4 py-2 text-left text-xs font-semibold text-gray-500">Label</th>
                <th class="px-4 py-2 text-left text-xs font-semibold text-gray-500">Type</th>
                <th class="px-4 py-2 text-center text-xs font-semibold text-gray-500">Required</th>
                <th class="px-4 py-2 text-center text-xs font-semibold text-gray-500">Populated</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-gray-50">
              <tr v-for="attr in profileSelections[p.targetOuDn].attributes" :key="attr.attributeName"
                  :class="attr.included ? '' : 'opacity-50'">
                <td class="px-4 py-1.5">
                  <input type="checkbox" v-model="attr.included" :aria-label="`Include ${attr.attributeName}`" class="rounded" />
                </td>
                <td class="px-4 py-1.5 font-medium text-gray-900 text-xs">{{ attr.attributeName }}</td>
                <td class="px-4 py-1.5">
                  <input v-model="attr.suggestedLabel" :aria-label="`Label for ${attr.attributeName}`" class="input input-sm w-full text-xs" />
                </td>
                <td class="px-4 py-1.5">
                  <select v-model="attr.inputType" :aria-label="`Type for ${attr.attributeName}`" class="input input-sm text-xs">
                    <option v-for="t in inputTypeOptions" :key="t" :value="t">{{ t }}</option>
                  </select>
                </td>
                <td class="px-4 py-1.5 text-center">
                  <input type="checkbox" v-model="attr.requiredOnCreate" class="rounded" />
                </td>
                <td class="px-4 py-1.5 text-center">
                  <span v-if="!attr.hidden" class="text-green-600 text-xs">Yes</span>
                  <span v-else class="text-gray-500 text-xs">No</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="flex justify-between">
        <button @click="step = 2" class="btn-neutral">Back</button>
        <button @click="step = includeGroups ? 4 : 5" class="btn-primary">
          Next: {{ includeGroups ? 'Group Assignments' : 'Review & Commit' }}
        </button>
      </div>
    </div>

    <!-- ══════════════════════════════════════════════════════════════════ -->
    <!-- Step 4: Review Group Assignments -->
    <!-- ══════════════════════════════════════════════════════════════════ -->
    <div v-if="step === 4 && proposal" class="space-y-6">
      <div v-for="p in includedProfiles" :key="p.targetOuDn"
           class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        <div class="px-5 py-3 border-b border-gray-100">
          <h2 class="text-sm font-semibold text-gray-700">{{ profileSelections[p.targetOuDn].name }}</h2>
          <p class="text-xs text-gray-500">{{ p.targetOuDn }}</p>
        </div>

        <div v-if="!profileSelections[p.targetOuDn].groupAssignments.length"
             class="px-5 py-6 text-center text-gray-500 text-sm">
          No group overlap detected for this OU.
        </div>

        <table v-else class="w-full text-sm">
          <thead class="bg-gray-50">
            <tr>
              <th class="px-4 py-2 text-left text-xs font-semibold text-gray-500 w-10"></th>
              <th class="px-4 py-2 text-left text-xs font-semibold text-gray-500">Group</th>
              <th class="px-4 py-2 text-left text-xs font-semibold text-gray-500">Member Attr</th>
              <th class="px-4 py-2 text-right text-xs font-semibold text-gray-500">Overlap</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            <tr v-for="g in profileSelections[p.targetOuDn].groupAssignments" :key="g.groupDn">
              <td class="px-4 py-2">
                <input type="checkbox" v-model="g.included" class="rounded" />
              </td>
              <td class="px-4 py-2">
                <div class="font-medium text-gray-900">{{ g.groupCn }}</div>
                <div class="text-xs text-gray-500 truncate max-w-xs">{{ g.groupDn }}</div>
              </td>
              <td class="px-4 py-2 text-gray-600">{{ g.memberAttribute }}</td>
              <td class="px-4 py-2 text-right">
                <div class="flex items-center justify-end gap-2">
                  <div class="w-20 h-2 bg-gray-100 rounded-full overflow-hidden">
                    <div class="h-full rounded-full"
                         :class="g.overlapPercent >= 80 ? 'bg-green-500' : g.overlapPercent >= 40 ? 'bg-yellow-500' : 'bg-blue-500'"
                         :style="{ width: g.overlapPercent + '%' }"></div>
                  </div>
                  <span class="text-xs font-medium w-10 text-right">{{ g.overlapPercent }}%</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="flex justify-between">
        <button @click="step = 3" class="btn-neutral">Back</button>
        <button @click="step = 5" class="btn-primary">Next: Review &amp; Commit</button>
      </div>
    </div>

    <!-- ══════════════════════════════════════════════════════════════════ -->
    <!-- Step 5: Review & Commit -->
    <!-- ══════════════════════════════════════════════════════════════════ -->
    <div v-if="step === 5 && proposal" class="space-y-6">
      <div class="bg-white border border-gray-200 rounded-xl p-6 shadow-sm">
        <h2 class="text-lg font-semibold text-gray-900 mb-4">Commit Summary</h2>

        <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-6">
          <div class="bg-blue-50 rounded-lg p-4 text-center">
            <p class="text-2xl font-bold text-blue-700">{{ commitSummary.profileCount }}</p>
            <p class="text-xs text-blue-600">Profiles to create</p>
          </div>
          <div class="bg-green-50 rounded-lg p-4 text-center">
            <p class="text-2xl font-bold text-green-700">{{ commitSummary.totalAttrs }}</p>
            <p class="text-xs text-green-600">Total attributes</p>
          </div>
          <div class="bg-purple-50 rounded-lg p-4 text-center">
            <p class="text-2xl font-bold text-purple-700">{{ commitSummary.totalGroups }}</p>
            <p class="text-xs text-purple-600">Group assignments</p>
          </div>
          <div class="bg-amber-50 rounded-lg p-4 text-center">
            <p class="text-2xl font-bold text-amber-700">{{ commitSummary.userBaseDns.length + commitSummary.groupBaseDns.length }}</p>
            <p class="text-xs text-amber-600">Base DNs to add</p>
          </div>
        </div>

        <h3 class="text-sm font-semibold text-gray-700 mb-2">Profiles</h3>
        <ul class="space-y-2 mb-6">
          <li v-for="p in includedProfiles" :key="p.targetOuDn"
              class="flex items-center justify-between text-sm bg-gray-50 rounded-lg px-4 py-2">
            <div>
              <span class="font-medium text-gray-900">{{ profileSelections[p.targetOuDn].name }}</span>
              <span class="text-gray-500 ml-2 text-xs">{{ p.targetOuDn }}</span>
            </div>
            <div class="text-xs text-gray-500">
              {{ profileSelections[p.targetOuDn].attributes.filter(a => a.included).length }} attrs,
              {{ profileSelections[p.targetOuDn].groupAssignments.filter(g => g.included).length }} groups
            </div>
          </li>
        </ul>

        <div v-if="commitSummary.userBaseDns.length" class="mb-4">
          <h3 class="text-sm font-semibold text-gray-700 mb-2">User Base DNs</h3>
          <ul class="text-sm text-gray-600 space-y-1">
            <li v-for="dn in commitSummary.userBaseDns" :key="dn" class="text-xs">{{ dn }}</li>
          </ul>
        </div>

        <div v-if="commitSummary.groupBaseDns.length" class="mb-4">
          <h3 class="text-sm font-semibold text-gray-700 mb-2">Group Base DNs</h3>
          <ul class="text-sm text-gray-600 space-y-1">
            <li v-for="dn in commitSummary.groupBaseDns" :key="dn" class="text-xs">{{ dn }}</li>
          </ul>
        </div>
      </div>

      <div class="flex justify-between">
        <button @click="step = includeGroups ? 4 : 3" class="btn-neutral">Back</button>
        <button @click="doCommit" :disabled="committing || !commitSummary.profileCount" class="btn-primary">
          <template v-if="committing">
            <svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-white inline" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
            </svg>
            Committing...
          </template>
          <template v-else>Commit {{ commitSummary.profileCount }} Profiles</template>
        </button>
      </div>
    </div>
  </PageContainer>
</template>

<style scoped>
@reference "tailwindcss";
</style>
