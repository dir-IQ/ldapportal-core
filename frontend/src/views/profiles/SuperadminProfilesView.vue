<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import {
  listAllProfiles, createProfile, updateProfile, deleteProfile, cloneProfile,
  getApprovalConfig, setApprovalConfig, getApprovers, setApprovers,
  evaluateGroupChanges, applySelectiveGroupChanges, seedAttributeDefaults,
  probeTargetOu
} from '@/api/profiles'
import { listDirectories } from '@/api/directories'
import { listObjectClasses, getObjectClass } from '@/api/schema'
import { listAdmins } from '@/api/adminManagement'
import type { components } from '@/api/openapi'
import AppModal from '@/components/AppModal.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import FormLayoutDesigner from '@/components/FormLayoutDesigner.vue'
import DnPicker from '@/components/DnPicker.vue'
import DataTable from '@/components/DataTable.vue'
import IsvaProfileOverrideControl from '@/components/profiles/IsvaProfileOverrideControl.vue'

type DirectoryConn = components['schemas']['DirectoryConnectionResponse']

interface AttributeConfig {
  attributeName: string
  customLabel: string
  inputType: string
  requiredOnCreate: boolean
  editableOnCreate: boolean
  editableOnUpdate: boolean
  selfServiceEdit: boolean
  selfRegistrationEdit: boolean
  defaultValue: string
  computedExpression: string
  validationRegex: string
  validationMessage: string
  allowedValues: string
  minLength: number | null
  maxLength: number | null
  sectionName: string
  columnSpan: number | null
  hidden: boolean
  registrationSectionName: string | null
  registrationColumnSpan: number | null
  registrationDisplayOrder: number | null
  selfServiceSectionName: string | null
  selfServiceColumnSpan: number | null
  selfServiceDisplayOrder: number | null
}

interface GroupAssignment {
  groupDn: string
  memberAttribute: string
}

interface ProfileForm {
  name: string
  description: string
  targetOuDn: string
  objectClassNames: string[]
  rdnAttribute: string
  showDnField: boolean
  enabled: boolean
  selfRegistrationAllowed: boolean
  passwordLength: number
  passwordUppercase: boolean
  passwordLowercase: boolean
  passwordDigits: boolean
  passwordSpecial: boolean
  passwordSpecialChars: string
  emailPasswordToUser: boolean
  autoIncludeGroups: boolean
  excludeAutoIncludes: boolean
  additionalProfileIds: string[]
  attributeConfigs: AttributeConfig[]
  groupAssignments: GroupAssignment[]
}

interface ApprovalForm {
  requireApproval: boolean
  approverMode: string
  approverGroupDn: string
  autoEscalateDays: number | null
  escalationAccountId: string | null
}

// Profile rows as returned by the (still-untyped) profiles API. Only the
// fields this view reads are modelled; nullable where the response omits them.
interface ProfileRow {
  id: string
  name: string
  description?: string | null
  directoryId: string
  directoryName?: string
  targetOuDn: string
  objectClassNames: string[]
  rdnAttribute: string
  showDnField: boolean
  enabled: boolean
  selfRegistrationAllowed: boolean
  passwordLength?: number | null
  passwordUppercase?: boolean | null
  passwordLowercase?: boolean | null
  passwordDigits?: boolean | null
  passwordSpecial?: boolean | null
  passwordSpecialChars?: string | null
  emailPasswordToUser?: boolean | null
  autoIncludeGroups?: boolean | null
  excludeAutoIncludes?: boolean | null
  additionalProfiles?: Array<{ id: string; name?: string }>
  attributeConfigs: AttributeConfig[]
  groupAssignments: GroupAssignment[]
}

interface AdminRow {
  id: string
  username: string
  displayName?: string | null
  email?: string | null
  role: string
}

interface ComplianceRow {
  userDn: string
  groupDn: string
  memberAttribute: string
  selected: boolean
}

// Row shape the FormLayoutDesigner round-trips: an attribute config plus
// the rdn marker it renders.
type LayoutRow = AttributeConfig & { rdn: boolean }

// Repo-standard axios/native error narrowing (see docs/frontend-conventions.md).
function errMsg(e: unknown, fallback = 'Something went wrong'): string {
  const err = e as {
    response?: { data?: { detail?: string; message?: string } }
    message?: string
  }
  return err.response?.data?.detail || err.response?.data?.message || err.message || fallback
}


const profileCols = [
  { key: 'name', label: 'Name' },
  { key: 'directoryName', label: 'Directory' },
  { key: 'targetOuDn', label: 'Target OU' },
  { key: 'objectClassNames', label: 'Object Classes' },
  { key: 'enabled', label: 'Status' },
]

const notif = useNotificationStore()

const loading = ref(false)
const saving = ref(false)
const profiles = ref<ProfileRow[]>([])
const directories = ref<DirectoryConn[]>([])
const admins = ref<AdminRow[]>([])

const showModal = ref(false)
const loadingEdit = ref(false)   // gates the modal body during openEdit's awaits
const editing = ref<string | null>(null)
const showDeleteConfirm = ref(false)
const deleteTarget = ref<ProfileRow | null>(null)
const modalTab = ref('general')

// Schema caching
const objectClasses = ref<string[]>([])
const loadingOCs = ref(false)
const selectedDirId = ref<string | null>(null)
const ocSchemaCache = ref<Record<string, { required: string[]; optional: string[] }>>({})

// Profile form
const profile = ref<ProfileForm>(emptyProfile())


// Group change preview dialog
// Group compliance check state is declared near checkCompliance()
const applyingGroupChanges = ref(false)

// Approval form
const approval = ref<ApprovalForm>(emptyApproval())
const profileApprovers = ref<string[]>([])

function emptyProfile(): ProfileForm {
  return {
    name: '', description: '', targetOuDn: '',
    objectClassNames: [], rdnAttribute: '',
    showDnField: true, enabled: true, selfRegistrationAllowed: false,
    passwordLength: 16, passwordUppercase: true, passwordLowercase: true,
    passwordDigits: true, passwordSpecial: true, passwordSpecialChars: '!@#$%^&*',
    emailPasswordToUser: false,
    autoIncludeGroups: false, excludeAutoIncludes: false,
    additionalProfileIds: [],
    attributeConfigs: [], groupAssignments: []
  }
}

function emptyApproval(): ApprovalForm {
  return {
    requireApproval: false, approverMode: 'DATABASE',
    approverGroupDn: '', autoEscalateDays: null, escalationAccountId: null
  }
}

onMounted(async () => {
  loading.value = true
  try {
    const [profilesRes, dirsRes, adminsRes] = await Promise.all([
      listAllProfiles(), listDirectories(), listAdmins()
    ])
    profiles.value = profilesRes.data
    directories.value = dirsRes.data.filter((d) => d.directoryType !== 'ENTRA_ID')
    admins.value = adminsRes.data
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    loading.value = false
  }
})

watch(selectedDirId, async (dirId) => {
  if (!dirId) return
  objectClasses.value = []
  loadingOCs.value = true
  try {
    const { data } = await listObjectClasses(dirId)
    objectClasses.value = data.map((oc: string | { name: string }) => typeof oc === 'string' ? oc : oc.name)
  } catch {
    notif.error('Failed to load object classes')
  } finally {
    loadingOCs.value = false
  }
})

function openCreate() {
  editing.value = null
  profile.value = emptyProfile()
  approval.value = emptyApproval()
  profileApprovers.value = []
  schemaRequiredAttrs.value = new Set()
  ocSchemaCache.value = {}
  selectedDirId.value = directories.value.length > 0 ? (directories.value[0].id ?? null) : null
  modalTab.value = 'general'
  layoutMode.value = 'admin'
  showModal.value = true
}

async function openEdit(p: ProfileRow) {
  editing.value = p.id
  selectedDirId.value = p.directoryId
  profile.value = {
    name: p.name, description: p.description || '', targetOuDn: p.targetOuDn,
    objectClassNames: [...p.objectClassNames], rdnAttribute: p.rdnAttribute,
    showDnField: p.showDnField, enabled: p.enabled,
    selfRegistrationAllowed: p.selfRegistrationAllowed,
    passwordLength: p.passwordLength ?? 16,
    passwordUppercase: p.passwordUppercase ?? true,
    passwordLowercase: p.passwordLowercase ?? true,
    passwordDigits: p.passwordDigits ?? true,
    passwordSpecial: p.passwordSpecial ?? true,
    passwordSpecialChars: p.passwordSpecialChars ?? '!@#$%^&*',
    emailPasswordToUser: p.emailPasswordToUser ?? false,
    autoIncludeGroups: p.autoIncludeGroups ?? false,
    excludeAutoIncludes: p.excludeAutoIncludes ?? false,
    additionalProfileIds: (p.additionalProfiles || []).map(ap => ap.id),
    attributeConfigs: p.attributeConfigs.map(a => ({
      attributeName: a.attributeName, customLabel: a.customLabel || '',
      inputType: a.inputType, requiredOnCreate: a.requiredOnCreate,
      editableOnCreate: a.editableOnCreate, editableOnUpdate: a.editableOnUpdate,
      selfServiceEdit: a.selfServiceEdit, selfRegistrationEdit: a.selfRegistrationEdit,
      defaultValue: a.defaultValue || '',
      computedExpression: a.computedExpression || '',
      validationRegex: a.validationRegex || '', validationMessage: a.validationMessage || '',
      allowedValues: a.allowedValues || '', minLength: a.minLength,
      maxLength: a.maxLength, sectionName: a.sectionName || '',
      columnSpan: a.columnSpan, hidden: a.hidden,
      registrationSectionName: a.registrationSectionName ?? null,
      registrationColumnSpan: a.registrationColumnSpan ?? null, registrationDisplayOrder: a.registrationDisplayOrder ?? null,
      selfServiceSectionName: a.selfServiceSectionName ?? null,
      selfServiceColumnSpan: a.selfServiceColumnSpan ?? null, selfServiceDisplayOrder: a.selfServiceDisplayOrder ?? null
    })),
    groupAssignments: p.groupAssignments.map(g => ({
      groupDn: g.groupDn, memberAttribute: g.memberAttribute
    }))
  }
  modalTab.value = 'general'

  // Pop the modal IMMEDIATELY (with the loading overlay) before any
  // awaits — schema fetch + approval-config lookup can take a few
  // seconds on a slow directory, and a button click that takes 3s to
  // do anything visible reads as broken. The modal body is gated on
  // `loadingEdit` and renders a centred spinner until every fetch
  // completes.
  loadingEdit.value = true
  showModal.value = true

  try {
    // Load schema data for existing object classes (for RDN picker and required tracking)
    schemaRequiredAttrs.value = new Set()
    ocSchemaCache.value = {}
    for (const ocName of p.objectClassNames) {
      try {
        const { data } = await getObjectClass(selectedDirId.value, ocName)
        const required = data.requiredAttributes || data.required || []
        const optional = data.optionalAttributes || data.optional || []
        ocSchemaCache.value[ocName] = { required: [...required], optional: [...optional] }
        for (const attr of required) schemaRequiredAttrs.value.add(attr.toLowerCase())
      } catch { /* schema lookup optional */ }
    }

    // Auto-populate Attributes tab from schema when the profile arrives
    // with object classes but no saved attributeConfigs. Two paths land
    // here: (a) a freshly-cloned profile whose source had no saved
    // configs (or whose configs got lost en route — defensive), and (b)
    // an older profile that was created via API / migrated in without
    // going through addObjectClass()'s auto-add. Either way, showing
    // the operator an empty Attributes tab with the "add object classes"
    // hint is wrong — the object classes are right there. Mirror the
    // exact shape addObjectClass() builds so the two code paths stay
    // consistent.
    if (profile.value.attributeConfigs.length === 0
        && profile.value.objectClassNames.length > 0) {
      for (const requiredSet of Object.values(ocSchemaCache.value)) {
        for (const attr of requiredSet.required) {
          if (profile.value.attributeConfigs.find(
              a => a.attributeName.toLowerCase() === attr.toLowerCase())) continue
          const isObjClass = attr.toLowerCase() === 'objectclass'
          profile.value.attributeConfigs.push({
            attributeName: attr, customLabel: isObjClass ? '' : guessLabel(attr),
            inputType: isObjClass ? 'HIDDEN_FIXED' : 'TEXT',
            requiredOnCreate: true, editableOnCreate: !isObjClass,
            editableOnUpdate: !isObjClass,
            selfServiceEdit: !isObjClass && isSelfServiceEditable(attr),
            selfRegistrationEdit: !isObjClass && isSelfServiceEditable(attr),
            defaultValue: '', computedExpression: '', validationRegex: '',
            validationMessage: '', allowedValues: '', minLength: null,
            maxLength: null, sectionName: '', columnSpan: 6, hidden: isObjClass,
            registrationSectionName: null, registrationColumnSpan: null, registrationDisplayOrder: null,
            selfServiceSectionName: null, selfServiceColumnSpan: null, selfServiceDisplayOrder: null,
          })
        }
      }
    }

    // Load approval data
    try {
      const { data } = await getApprovalConfig(p.id)
      approval.value = { ...data }
    } catch { approval.value = emptyApproval() }

    try {
      const { data } = await getApprovers(p.id)
      profileApprovers.value = data.map((a: { accountId: string }) => a.accountId)
    } catch { profileApprovers.value = [] }
  } finally {
    loadingEdit.value = false
  }
}

async function save() {
  if (!profile.value.name || !profile.value.targetOuDn) {
    notif.error('Name and Target OU DN are required')
    return
  }
  if (profile.value.objectClassNames.length === 0) {
    notif.error('At least one object class is required')
    return
  }
  if (!profile.value.rdnAttribute) {
    notif.error('RDN Attribute is required')
    return
  }
  saving.value = true
  // If the probe already determined the target OU is missing,
  // pass force=true so the save goes through (the operator can see
  // the banner and is consciously pre-staging). 'checking' / 'idle'
  // states still go through default (server-side validation is the
  // source of truth — a 400 from the server tells us if force was
  // needed).
  const force = targetOuProbeState.value === 'missing'
  try {
    if (editing.value) {
      await updateProfile(selectedDirId.value, editing.value, profile.value, force)
      // Save approval config
      await setApprovalConfig(editing.value, approval.value)
      await setApprovers(editing.value, { accountIds: profileApprovers.value })
      notif.success('Profile updated')
      showModal.value = false
      editing.value = null
      await reload()
    } else {
      const { data } = await createProfile(selectedDirId.value, profile.value, force)
      // Save approval config
      await setApprovalConfig(data.id, approval.value)
      if (profileApprovers.value.length > 0) {
        await setApprovers(data.id, { accountIds: profileApprovers.value })
      }
      notif.success('Profile created')
      showModal.value = false
      editing.value = null
      await reload()
    }
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    saving.value = false
  }
}

async function confirmDelete(p: ProfileRow) {
  deleteTarget.value = p
  showDeleteConfirm.value = true
}

async function doDelete() {
  if (!deleteTarget.value) return
  try {
    await deleteProfile(deleteTarget.value.directoryId, deleteTarget.value.id)
    notif.success('Profile deleted')
    showDeleteConfirm.value = false
    await reload()
  } catch (e) {
    notif.error(errMsg(e))
  }
}

const cloneTarget = ref<ProfileRow | null>(null)
const cloneName = ref('')
const showCloneModal = ref(false)

function openClone(p: ProfileRow) {
  cloneTarget.value = p
  cloneName.value = p.name + ' (Copy)'
  showCloneModal.value = true
}

async function doClone() {
  if (!cloneName.value.trim() || !cloneTarget.value) return
  showCloneModal.value = false
  try {
    await cloneProfile(cloneTarget.value.directoryId, cloneTarget.value.id, cloneName.value.trim())
    notif.success('Profile cloned')
    await reload()
  } catch (e) {
    notif.error(errMsg(e))
  }
}

async function reload() {
  const { data } = await listAllProfiles()
  profiles.value = data
}

/**
 * Server-side seed of the curated inetOrgPerson attribute defaults
 * (~27 attributes across Identity / Contact / Organization / Account
 * sections, with sensible columnSpans and required flags). Refuses
 * 409 if the profile already has any attribute configs — the user
 * has to clear them first if a re-seed is intended.
 *
 * Only valuable when the profile is saved (editing an existing one)
 * AND has no attribute configs yet — the button gates on both.
 */
const seeding = ref(false)

// ── Target-OU probe / warning banner ────────────────────────────
//
// Debounce a probe against the directory whenever the operator
// edits the Target OU DN. The banner surfaces 'missing' / 'exists'
// state; if 'missing' at save time, the form passes force=true so
// the operator can still save (pre-staging a profile before the OU
// exists is a legitimate workflow).
type TargetOuProbeState = 'idle' | 'checking' | 'exists' | 'missing'
const targetOuProbeState = ref<TargetOuProbeState>('idle')
let targetOuProbeTimer: ReturnType<typeof setTimeout> | null = null
let targetOuProbeToken = 0

function scheduleTargetOuProbe() {
  if (targetOuProbeTimer) clearTimeout(targetOuProbeTimer)
  const dn = profile.value.targetOuDn?.trim() ?? ''
  const dirId = selectedDirId.value
  if (!dn || !dirId) {
    targetOuProbeState.value = 'idle'
    return
  }
  targetOuProbeState.value = 'checking'
  // 400ms debounce — long enough that the DnPicker's typeahead
  // doesn't fire a probe on every keystroke, short enough that the
  // operator sees the result before tabbing past the field.
  const myToken = ++targetOuProbeToken
  targetOuProbeTimer = setTimeout(async () => {
    try {
      const { data } = await probeTargetOu(dirId, dn)
      // Drop the result if a newer probe has been queued.
      if (myToken !== targetOuProbeToken) return
      targetOuProbeState.value = data?.exists ? 'exists' : 'missing'
    } catch {
      // Probe failure (network, 403, etc) leaves the state as
      // 'checking'-but-unresolved → treat as idle so a save attempt
      // gets the server-side validation as the source of truth.
      if (myToken === targetOuProbeToken) targetOuProbeState.value = 'idle'
    }
  }, 400)
}

// Re-probe whenever the DN or directory changes.
watch(() => [profile.value.targetOuDn, selectedDirId.value],
  () => { scheduleTargetOuProbe() })
async function doSeedDefaults() {
  if (!editing.value || !selectedDirId.value) return
  seeding.value = true
  try {
    const { data } = await seedAttributeDefaults(
      selectedDirId.value, editing.value, 'inetOrgPerson')
    // Replace local attributeConfigs with the server-rebuilt set.
    // Avoids hand-merging seeds back into the in-memory profile and
    // keeps displayOrder / sectionName exactly as the server wrote them.
    profile.value.attributeConfigs = (data.attributeConfigs ?? []) as AttributeConfig[]
    notif.success(`Seeded ${data.attributeConfigs?.length ?? 0} attributes from inetOrgPerson defaults`)
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    seeding.value = false
  }
}

// Group assignment management
function addGroupAssignment() {
  profile.value.groupAssignments.push({ groupDn: '', memberAttribute: 'member' })
}
function removeGroupAssignment(index: number) {
  profile.value.groupAssignments.splice(index, 1)
}

// When auto-include is toggled on, clear additional profiles and exclude flag
function onAutoIncludeToggle() {
  if (profile.value.autoIncludeGroups) {
    profile.value.additionalProfileIds = []
    profile.value.excludeAutoIncludes = false
  }
}

// Additional profiles: profiles from the same directory that can be stacked
const availableAdditionalProfiles = computed(() => {
  if (!selectedDirId.value) return []
  return profiles.value
    .filter(p => p.directoryId === selectedDirId.value
      && p.id !== editing.value
      && !p.autoIncludeGroups) // auto-include profiles are implicit, not selectable
    .map(p => ({ id: p.id, name: p.name }))
    .sort((a, b) => a.name.localeCompare(b.name))
})

// Auto-included profiles (read-only display)
const autoIncludedProfiles = computed(() => {
  if (!selectedDirId.value) return []
  return profiles.value
    .filter(p => p.directoryId === selectedDirId.value
      && p.id !== editing.value
      && p.autoIncludeGroups)
    .map(p => ({ id: p.id, name: p.name }))
})

// Effective groups — live preview merging own + additional + auto-include groups
const effectiveGroups = computed<GroupAssignment[]>(() => {
  const seen = new Map<string, GroupAssignment>()

  // 1. Own groups (from dialog state)
  for (const g of profile.value.groupAssignments) {
    if (g.groupDn && !seen.has(g.groupDn)) {
      seen.set(g.groupDn, { groupDn: g.groupDn, memberAttribute: g.memberAttribute })
    }
  }

  // 2. Explicit additional profiles (selected in dialog)
  for (const apId of profile.value.additionalProfileIds) {
    const ap = profiles.value.find(p => p.id === apId)
    if (!ap) continue
    for (const g of (ap.groupAssignments || [])) {
      if (!seen.has(g.groupDn)) {
        seen.set(g.groupDn, { groupDn: g.groupDn, memberAttribute: g.memberAttribute })
      }
    }
  }

  // 3. Auto-include profiles (unless excluded)
  if (!profile.value.excludeAutoIncludes) {
    for (const ai of autoIncludedProfiles.value) {
      const ap = profiles.value.find(p => p.id === ai.id)
      if (!ap) continue
      for (const g of (ap.groupAssignments || [])) {
        if (!seen.has(g.groupDn)) {
          seen.set(g.groupDn, { groupDn: g.groupDn, memberAttribute: g.memberAttribute })
        }
      }
    }
  }

  return [...seen.values()]
})

function toggleAdditionalProfile(profileId: string) {
  const ids = profile.value.additionalProfileIds
  const idx = ids.indexOf(profileId)
  if (idx >= 0) {
    profile.value.additionalProfileIds = ids.filter((_, i) => i !== idx)
  } else {
    profile.value.additionalProfileIds = [...ids, profileId]
  }
}

// ── Group membership compliance check ─────────────────────────────────────
const complianceRows = ref<ComplianceRow[]>([])
const complianceLoading = ref(false)
const complianceChecked = ref(false)

interface GroupChangeEntry { groupDn: string; memberAttribute: string }
interface UserGroupChange { userDn: string; groupsToAdd?: GroupChangeEntry[] }

async function checkCompliance() {
  if (!editing.value) return
  complianceLoading.value = true
  complianceChecked.value = false
  try {
    const { data } = await evaluateGroupChanges(selectedDirId.value, editing.value)
    // Flatten to one row per user+group
    const rows: ComplianceRow[] = []
    for (const change of ((data.changes || []) as UserGroupChange[])) {
      for (const g of (change.groupsToAdd || [])) {
        rows.push({
          userDn: change.userDn,
          groupDn: g.groupDn,
          memberAttribute: g.memberAttribute,
          selected: false,
        })
      }
    }
    complianceRows.value = rows
    complianceChecked.value = true
  } catch (e) {
    notif.error('Compliance check failed: ' + errMsg(e))
  } finally {
    complianceLoading.value = false
  }
}

const complianceSelectedCount = computed(() => complianceRows.value.filter(r => r.selected).length)

function toggleAllCompliance(checked: boolean) {
  complianceRows.value.forEach(r => { r.selected = checked })
}

async function applySelectedCompliance() {
  const entries = complianceRows.value
    .filter(r => r.selected)
    .map(r => ({ userDn: r.userDn, groupDn: r.groupDn, memberAttribute: r.memberAttribute }))
  if (!entries.length) return

  applyingGroupChanges.value = true
  try {
    const { data } = await applySelectiveGroupChanges(selectedDirId.value, entries)
    notif.success(`Added ${data.applied} group membership(s)`)
    // Re-check to refresh the list
    await checkCompliance()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    applyingGroupChanges.value = false
  }
}

// Object class management
const ocToAdd = ref('')
// Track which attributes are required by the schema (cannot uncheck required or remove)
const schemaRequiredAttrs = ref<Set<string>>(new Set())

// Attributes commonly safe for users to self-edit
const SELF_SERVICE_EDITABLE_ATTRS = new Set([
  'givenname', 'sn', 'displayname', 'cn', 'preferredlanguage',
  'mail', 'telephonenumber', 'mobile', 'facsimiletelephonenumber', 'pager',
  'street', 'l', 'st', 'postalcode', 'postaladdress', 'co',
  'title', 'description',
  'jpegphoto', 'labeleduri', 'homephone',
])

function isSelfServiceEditable(attrName: string) {
  return SELF_SERVICE_EDITABLE_ATTRS.has(attrName.toLowerCase())
}

// Human-readable labels for well-known LDAP attributes
const ATTR_LABELS: Record<string, string> = {
  cn: 'Common Name', sn: 'Last Name', givenname: 'First Name',
  displayname: 'Display Name', mail: 'Email', uid: 'User ID',
  telephonenumber: 'Phone', mobile: 'Mobile', facsimiletelephonenumber: 'Fax',
  homephone: 'Home Phone', pager: 'Pager',
  street: 'Street Address', l: 'City', st: 'State/Province',
  postalcode: 'Postal Code', postaladdress: 'Postal Address', co: 'Country',
  title: 'Job Title', description: 'Description', o: 'Organization',
  ou: 'Organizational Unit', dc: 'Domain Component',
  preferredlanguage: 'Preferred Language', labeleduri: 'URL',
  jpegphoto: 'Photo', userpassword: 'Password',
  employeenumber: 'Employee Number', employeetype: 'Employee Type',
  departmentnumber: 'Department Number', roomnumber: 'Room Number',
  manager: 'Manager', secretary: 'Secretary',
  initials: 'Initials', c: 'Country Code',
}

function guessLabel(attrName: string) {
  const known = ATTR_LABELS[attrName.toLowerCase()]
  if (known) return known
  // Split camelCase / snake_case into words and title-case them
  return attrName
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[_-]/g, ' ')
    .replace(/\b\w/g, c => c.toUpperCase())
}

async function addObjectClass() {
  if (!ocToAdd.value) return
  profile.value.objectClassNames.push(ocToAdd.value)
  // Load schema attributes for this OC
  try {
    const { data } = await getObjectClass(selectedDirId.value, ocToAdd.value)
    const required = data.requiredAttributes || data.required || []
    const optional = data.optionalAttributes || data.optional || []
    // Track schema-required attributes and cache for RDN picker
    for (const attr of required) schemaRequiredAttrs.value.add(attr.toLowerCase())
    ocSchemaCache.value[ocToAdd.value] = { required: [...required], optional: [...optional] }
    // Auto-add only schema-required attributes; optional ones can be added via the picker
    for (const attr of required) {
      if (!profile.value.attributeConfigs.find(a => a.attributeName.toLowerCase() === attr.toLowerCase())) {
        const isObjClass = attr.toLowerCase() === 'objectclass'
        profile.value.attributeConfigs.push({
          attributeName: attr, customLabel: isObjClass ? '' : guessLabel(attr), inputType: isObjClass ? 'HIDDEN_FIXED' : 'TEXT',
          requiredOnCreate: true, editableOnCreate: !isObjClass,
          editableOnUpdate: !isObjClass, selfServiceEdit: !isObjClass && isSelfServiceEditable(attr),
          selfRegistrationEdit: !isObjClass && isSelfServiceEditable(attr),
          defaultValue: '', computedExpression: '', validationRegex: '',
          validationMessage: '', allowedValues: '', minLength: null,
          maxLength: null, sectionName: '', columnSpan: 6, hidden: isObjClass,
          registrationSectionName: null, registrationColumnSpan: null, registrationDisplayOrder: null,
          selfServiceSectionName: null, selfServiceColumnSpan: null, selfServiceDisplayOrder: null
        })
      }
    }
  } catch { /* schema lookup optional */ }
  ocToAdd.value = ''
}
function removeObjectClass(name: string) {
  profile.value.objectClassNames = profile.value.objectClassNames.filter(n => n !== name)
  // Rebuild schema-required set from remaining OCs
  rebuildSchemaRequired()
}

async function rebuildSchemaRequired() {
  schemaRequiredAttrs.value = new Set()
  for (const ocName of profile.value.objectClassNames) {
    const cached = ocSchemaCache.value[ocName]
    if (cached) {
      for (const attr of cached.required) schemaRequiredAttrs.value.add(attr.toLowerCase())
    }
  }
}

function dirName(dirId: string) {
  const d = directories.value.find(d => d.id === dirId)
  return d ? d.displayName : dirId
}

const availableObjectClasses = computed(() => {
  const added = new Set(profile.value.objectClassNames.map(n => n.toLowerCase()))
  return objectClasses.value.filter(oc => !added.has(oc.toLowerCase()))
})

// RDN attribute candidates: all attributes from selected object classes
const rdnCandidates = computed(() => {
  const attrs = new Set<string>()
  for (const ocName of profile.value.objectClassNames) {
    const cached = ocSchemaCache.value[ocName]
    if (cached) {
      for (const a of [...cached.required, ...cached.optional]) {
        if (a.toLowerCase() !== 'objectclass') attrs.add(a)
      }
    }
  }
  // Also include any configured attribute names
  for (const a of profile.value.attributeConfigs) {
    if (a.attributeName.toLowerCase() !== 'objectclass') attrs.add(a.attributeName)
  }
  return [...attrs].sort()
})

// Helper: check if an attribute is the RDN attribute
function isRdnAttribute(attr: AttributeConfig) {
  return attr.attributeName === profile.value.rdnAttribute
}

// Helper: check if an attribute is schema-required
function isSchemaRequired(attr: AttributeConfig) {
  return schemaRequiredAttrs.value.has(attr.attributeName.toLowerCase())
}

// Helper: check if an attribute can be removed (required attributes cannot be removed)
function canRemoveAttribute(attr: AttributeConfig) {
  return !isRdnAttribute(attr) && !isSchemaRequired(attr) && !attr.requiredOnCreate
}

// Available attributes from selected object classes that haven't been added yet
const showAttrPicker = ref(false)
const attrPickerSelection = ref<string[]>([])

const availableAttributes = computed(() => {
  const added = new Set(profile.value.attributeConfigs.map(a => a.attributeName.toLowerCase()))
  const attrs = []
  for (const ocName of profile.value.objectClassNames) {
    const cached = ocSchemaCache.value[ocName]
    if (!cached) continue
    for (const attr of [...cached.required, ...cached.optional]) {
      if (attr.toLowerCase() !== 'objectclass' && !added.has(attr.toLowerCase())) {
        attrs.push(attr)
        added.add(attr.toLowerCase()) // dedupe across OCs
      }
    }
  }
  return attrs.sort()
})

function toggleAttrPickerSelection(attr: string) {
  const idx = attrPickerSelection.value.indexOf(attr)
  if (idx >= 0) attrPickerSelection.value.splice(idx, 1)
  else attrPickerSelection.value.push(attr)
}

function toggleAttrPicker() {
  attrPickerSelection.value = []
  showAttrPicker.value = !showAttrPicker.value
}

function addSelectedAttributes() {
  for (const name of attrPickerSelection.value) {
    profile.value.attributeConfigs.push({
      attributeName: name, customLabel: guessLabel(name), inputType: 'TEXT',
      requiredOnCreate: schemaRequiredAttrs.value.has(name.toLowerCase()), editableOnCreate: true,
      editableOnUpdate: true, selfServiceEdit: false,
      selfRegistrationEdit: false,
      defaultValue: '', computedExpression: '', validationRegex: '',
      validationMessage: '', allowedValues: '', minLength: null,
      maxLength: null, sectionName: '', columnSpan: 6, hidden: false,
      registrationSectionName: null, registrationColumnSpan: null, registrationDisplayOrder: null,
      selfServiceSectionName: null, selfServiceColumnSpan: null, selfServiceDisplayOrder: null
    })
  }
  attrPickerSelection.value = []
  showAttrPicker.value = false
}

// When emailPasswordToUser is enabled, ensure 'mail' is present and required
watch(() => profile.value.emailPasswordToUser, (enabled) => {
  if (!enabled) return
  const existing = profile.value.attributeConfigs.find(
    a => a.attributeName.toLowerCase() === 'mail'
  )
  if (existing) {
    existing.requiredOnCreate = true
    existing.hidden = false
  } else {
    profile.value.attributeConfigs.push({
      attributeName: 'mail', customLabel: 'Email', inputType: 'TEXT',
      requiredOnCreate: true, editableOnCreate: true,
      editableOnUpdate: true, selfServiceEdit: true,
      selfRegistrationEdit: true,
      defaultValue: '', computedExpression: '', validationRegex: '',
      validationMessage: '', allowedValues: '', minLength: null,
      maxLength: null, sectionName: '', columnSpan: 6, hidden: false,
      registrationSectionName: null, registrationColumnSpan: null, registrationDisplayOrder: null,
      selfServiceSectionName: null, selfServiceColumnSpan: null, selfServiceDisplayOrder: null
    })
  }
})

// When requiredOnCreate is set, ensure hidden is cleared (unless attribute has a computed expression)
watch(() => profile.value.attributeConfigs.map(a => a.requiredOnCreate), () => {
  for (const attr of profile.value.attributeConfigs) {
    if (attr.requiredOnCreate && attr.hidden && !attr.computedExpression) attr.hidden = false
  }
})

// Helper: determine which fields to show based on input type
function showFieldFor(inputType: string, fieldName: string) {
  const rules: Record<string, string[]> = {
    defaultValue:       ['TEXT', 'TEXTAREA', 'PASSWORD', 'DATE', 'DATETIME', 'MULTI_VALUE', 'HIDDEN_FIXED', 'SELECT'],
    allowedValues:      ['SELECT'],
    computedExpression: ['TEXT', 'TEXTAREA', 'PASSWORD', 'MULTI_VALUE', 'DATE', 'DATETIME', 'DN_LOOKUP'],
    validationRegex:    ['TEXT', 'TEXTAREA', 'PASSWORD', 'MULTI_VALUE'],
  }
  return (rules[fieldName] || []).includes(inputType)
}

// Ensure RDN attribute is always marked as required
watch(() => profile.value.rdnAttribute, (rdnAttr) => {
  if (!rdnAttr) return
  const attr = profile.value.attributeConfigs.find(a => a.attributeName === rdnAttr)
  if (attr) attr.requiredOnCreate = true
})

// Attribute configs with RDN flag for the layout designer
const layoutAttributeConfigs = computed<LayoutRow[]>({
  get() {
    return profile.value.attributeConfigs.map(a => ({
      ...a,
      rdn: a.attributeName === profile.value.rdnAttribute,
    }))
  },
  set(val: LayoutRow[]) {
    profile.value.attributeConfigs = val.map(({ rdn, ...rest }) => rest)
  }
})

// Registration layout: self-registration-enabled fields, defaulting to admin layout values.
const registrationAttributeConfigs = computed<LayoutRow[]>({
  get() {
    return profile.value.attributeConfigs
      .filter(a => a.selfRegistrationEdit && !a.hidden && a.inputType !== 'HIDDEN_FIXED')
      .map(a => ({
        ...a,
        rdn: a.attributeName === profile.value.rdnAttribute,
        sectionName: a.registrationSectionName ?? a.sectionName ?? '',
        columnSpan: a.registrationColumnSpan ?? a.columnSpan ?? 6,
      }))
  },
  set(val: LayoutRow[]) {
    const lookup = new Map(
      val.map((v, i): [string, LayoutRow & { displayOrder: number }] =>
        [v.attributeName, { ...v, displayOrder: i }]),
    )
    profile.value.attributeConfigs = profile.value.attributeConfigs.map(a => {
      const updated = lookup.get(a.attributeName)
      if (updated) {
        return {
          ...a,
          registrationSectionName: updated.sectionName ?? '',
          registrationColumnSpan: updated.columnSpan ?? 6,
          registrationDisplayOrder: updated.displayOrder,
        }
      }
      return a
    })
  }
})

// Self-service layout: self-service-editable fields, defaulting to admin layout values.
const selfServiceAttributeConfigs = computed<LayoutRow[]>({
  get() {
    return profile.value.attributeConfigs
      .filter(a => a.selfServiceEdit && !a.hidden && a.inputType !== 'HIDDEN_FIXED')
      .map(a => ({
        ...a,
        rdn: a.attributeName === profile.value.rdnAttribute,
        sectionName: a.selfServiceSectionName ?? a.sectionName ?? '',
        columnSpan: a.selfServiceColumnSpan ?? a.columnSpan ?? 6,
      }))
  },
  set(val: LayoutRow[]) {
    const lookup = new Map(
      val.map((v, i): [string, LayoutRow & { displayOrder: number }] =>
        [v.attributeName, { ...v, displayOrder: i }]),
    )
    profile.value.attributeConfigs = profile.value.attributeConfigs.map(a => {
      const updated = lookup.get(a.attributeName)
      if (updated) {
        return {
          ...a,
          selfServiceSectionName: updated.sectionName ?? '',
          selfServiceColumnSpan: updated.columnSpan ?? 6,
          selfServiceDisplayOrder: updated.displayOrder,
        }
      }
      return a
    })
  }
})

const layoutMode = ref('admin')

// Reset layout mode if self-registration is turned off while viewing that layout
watch(() => profile.value.selfRegistrationAllowed, (allowed) => {
  if (!allowed && layoutMode.value === 'registration') layoutMode.value = 'admin'
})

// Fixed modal height based on attribute count so switching tabs doesn't resize
const modalHeight = computed(() => {
  const count = profile.value.attributeConfigs.length
  if (count <= 6) return '50vh'
  if (count <= 12) return '60vh'
  return '70vh'
})

const modalTabs = [
  { id: 'general', label: 'General' },
  { id: 'attributes', label: 'Attributes' },
  { id: 'layout', label: 'Forms' },
  { id: 'groups', label: 'Groups' },
  { id: 'policy', label: 'Policy' },
]

function toggleApprover(accountId: string) {
  const idx = profileApprovers.value.indexOf(accountId)
  if (idx >= 0) profileApprovers.value.splice(idx, 1)
  else profileApprovers.value.push(accountId)
}
</script>

<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Provisioning Profiles</h1>
        <p class="text-sm text-gray-500 mt-1">Configure provisioning profiles and attribute mappings</p>
      </div>
      <button class="btn-primary" @click="openCreate">+ Create Profile</button>
    </div>

    <DataTable :columns="profileCols" :rows="profiles" :loading="loading" row-key="id" empty-text="No provisioning profiles configured.">
      <template #cell-name="{ row }">
        <span class="font-medium">{{ (row as ProfileRow).name }}</span>
      </template>
      <template #cell-targetOuDn="{ value }">
        <span class="text-gray-600 truncate block max-w-xs" :title="value">{{ value }}</span>
      </template>
      <template #cell-objectClassNames="{ value }">
        <span class="text-gray-600">{{ value.join(', ') }}</span>
      </template>
      <template #cell-enabled="{ value }">
        <span :class="value ? 'text-green-600' : 'text-gray-500'">{{ value ? 'Enabled' : 'Disabled' }}</span>
      </template>
      <template #actions="{ row }">
        <ActionMenu :items="[
          { label: 'Clone',  onClick: () => openClone(row as ProfileRow) },
          { label: 'Delete', onClick: () => confirmDelete(row as ProfileRow), danger: true },
        ]">
          <template #primary>
            <button @click="openEdit(row as ProfileRow)" class="btn-secondary btn-compact">Edit</button>
          </template>
        </ActionMenu>
      </template>
    </DataTable>

    <!-- Create/Edit Modal -->
    <AppModal v-model="showModal" size="xl" :fixedHeight="modalHeight">
      <template #title>
        <span>{{ editing ? 'Edit Profile' : 'Create Profile' }}</span>
        <span v-if="editing && profile.name" class="text-gray-500 font-normal"> — </span>
        <span v-if="editing && profile.name" class="text-blue-600">{{ profile.name }}</span>
      </template>
      <!-- Loading overlay while openEdit's schema + approval fetches
           run. The modal pops immediately on click so the operator
           sees something rather than wondering whether the click
           registered. -->
      <div v-if="loadingEdit" class="flex flex-col items-center justify-center py-16 text-gray-500">
        <svg class="animate-spin h-8 w-8 mb-3 text-blue-500" viewBox="0 0 24 24" fill="none">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"></path>
        </svg>
        <p class="text-sm">Loading profile…</p>
      </div>
      <div v-else class="space-y-4">
        <!-- Tab Navigation -->
        <div class="flex border-b gap-1">
          <button v-for="tab in modalTabs" :key="tab.id"
            :class="['px-4 py-2 text-sm font-medium border-b-2 -mb-px whitespace-nowrap',
              modalTab === tab.id ? 'border-blue-600 text-blue-600' : 'border-transparent text-gray-500 hover:text-gray-700']"
            @click="modalTab = tab.id">
            {{ tab.label }}
          </button>
        </div>

        <!-- General Tab -->
        <div v-if="modalTab === 'general'" class="space-y-4">
          <div v-if="!editing">
            <label for="sp-directory" class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
            <select id="sp-directory" v-model="selectedDirId" class="input w-full">
              <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
            </select>
          </div>
          <div>
            <label for="sp-name" class="block text-sm font-medium text-gray-700 mb-1">Name</label>
            <input id="sp-name" v-model="profile.name" class="input w-full" placeholder="e.g. Full-Time Engineer" />
          </div>
          <div>
            <label for="sp-description" class="block text-sm font-medium text-gray-700 mb-1">Description</label>
            <textarea id="sp-description" v-model="profile.description" class="input w-full" rows="2"></textarea>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Target OU DN</label>
            <DnPicker v-model="profile.targetOuDn" :directory-id="selectedDirId ?? ''"
              placeholder="e.g. ou=engineers,ou=people,dc=corp" />
            <!-- Target-OU warning banner: surfaces when the probe says
                 the DN doesn't resolve in the directory. Doesn't
                 block save by itself — the operator can acknowledge
                 and continue (passes force=true on the save). -->
            <div v-if="targetOuProbeState === 'missing'"
                 class="mt-2 rounded-md border border-yellow-200 bg-yellow-50 p-3 text-sm text-yellow-900 flex items-start gap-2">
              <span aria-hidden="true" class="mt-0.5">⚠</span>
              <div class="flex-1">
                <div class="font-medium">This OU isn't present in the directory.</div>
                <div class="text-xs mt-0.5">
                  User creation will fail with <code>NO_SUCH_OBJECT</code> until the OU exists.
                  Saving anyway is allowed (the form will submit with <code>force=true</code>)
                  if you're pre-staging the profile.
                </div>
              </div>
            </div>
            <div v-else-if="targetOuProbeState === 'exists'"
                 class="mt-2 text-xs text-green-700 inline-flex items-center gap-1">
              <span aria-hidden="true">✓</span> OU resolves in the directory.
            </div>
            <div v-else-if="targetOuProbeState === 'checking'"
                 class="mt-2 text-xs text-gray-500">Checking…</div>
          </div>
          <div class="grid grid-cols-3 gap-4 items-end">
            <div class="col-span-2">
              <label class="block text-sm font-medium text-gray-700 mb-1">Object Classes</label>
              <div v-if="profile.objectClassNames.length" class="flex gap-2 mb-2 flex-wrap">
                <span v-for="oc in profile.objectClassNames" :key="oc"
                  class="inline-flex items-center gap-1 px-2 py-1 bg-blue-100 text-blue-700 rounded text-xs">
                  {{ oc }}
                  <button @click="removeObjectClass(oc)" aria-label="Remove object class" class="text-blue-400 hover:text-red-600">&times;</button>
                </span>
              </div>
              <div class="flex gap-2">
                <select v-model="ocToAdd" aria-label="Add object class" class="input flex-1">
                  <option value="">Select object class…</option>
                  <option v-for="oc in availableObjectClasses" :key="oc" :value="oc">{{ oc }}</option>
                </select>
                <button class="btn-primary text-xs" @click="addObjectClass" :disabled="!ocToAdd">Add</button>
              </div>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">
                RDN Attribute <span class="text-red-500">*</span>
              </label>
              <select v-model="profile.rdnAttribute" aria-label="RDN attribute" class="input w-full"
                :disabled="profile.objectClassNames.length === 0">
                <option value="">{{ profile.objectClassNames.length === 0 ? 'Add an object class first' : 'Select RDN attribute…' }}</option>
                <option v-for="attr in rdnCandidates" :key="attr" :value="attr">{{ attr }}</option>
              </select>
            </div>
          </div>
          <div class="flex gap-6">
            <label class="flex items-center gap-2 text-sm">
              <input type="checkbox" v-model="profile.enabled" /> Profile is enabled
            </label>
            <label class="flex items-center gap-2 text-sm">
              <input type="checkbox" v-model="profile.selfRegistrationAllowed" /> Self-registration is enabled for this profile
            </label>
          </div>

        </div>

        <!-- Attributes Tab -->
        <div v-if="modalTab === 'attributes'" class="space-y-3">
          <div class="flex items-center gap-2">
            <button class="btn-primary text-sm" :disabled="availableAttributes.length === 0" @click="toggleAttrPicker">
              {{ showAttrPicker ? 'Cancel' : 'Add Attributes' }}
            </button>
            <!-- Seed defaults: server-side bulk add of a curated
                 inetOrgPerson set with sections + sensible column
                 widths + required flags. Only meaningful when the
                 profile exists server-side AND has no configs yet —
                 the endpoint refuses 409 otherwise, and the button
                 hides the same way client-side. -->
            <button
              v-if="editing && profile.attributeConfigs.length === 0"
              class="btn-secondary text-sm"
              :disabled="seeding"
              @click="doSeedDefaults"
            >
              {{ seeding ? 'Seeding…' : 'Seed inetOrgPerson defaults' }}
            </button>
          </div>
            <div v-if="showAttrPicker" class="mt-2 border rounded-lg p-3 space-y-2 bg-gray-50">
              <div v-if="availableAttributes.length === 0" class="text-gray-500 text-sm">
                All attributes from the selected object classes have been added.
              </div>
              <template v-else>
                <div class="text-xs text-gray-500 mb-1">Select attributes to add:</div>
                <div class="max-h-48 overflow-y-auto space-y-1">
                  <label v-for="attr in availableAttributes" :key="attr"
                    class="flex items-center gap-2 text-sm p-1 hover:bg-white rounded cursor-pointer">
                    <input type="checkbox"
                      :checked="attrPickerSelection.includes(attr)"
                      @change="toggleAttrPickerSelection(attr)" />
                    <span class="font-mono text-xs">{{ attr }}</span>
                  </label>
                </div>
                <button class="btn-primary text-sm mt-2" :disabled="attrPickerSelection.length === 0" @click="addSelectedAttributes">
                  Add {{ attrPickerSelection.length }} attribute{{ attrPickerSelection.length !== 1 ? 's' : '' }}
                </button>
              </template>
            </div>
          </div>
          <div v-if="profile.attributeConfigs.length === 0" class="text-gray-500 text-sm">
            Add object classes in the General tab to populate attributes.
          </div>
          <div v-for="(attr, i) in profile.attributeConfigs" :key="i"
            class="border border-gray-300 rounded-lg p-3 space-y-2">
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-2">
                <span class="font-medium text-sm">{{ attr.attributeName }}</span>
                <span v-if="isRdnAttribute(attr)"
                  class="text-[10px] bg-amber-100 text-amber-700 rounded px-1.5 py-0.5 font-medium">RDN</span>
                <span v-if="isSchemaRequired(attr)"
                  class="text-[10px] bg-blue-50 text-blue-600 rounded px-1.5 py-0.5 font-medium">schema required</span>
              </div>
              <button v-if="canRemoveAttribute(attr)"
                class="text-red-500 text-xs hover:underline"
                @click="profile.attributeConfigs.splice(i, 1)">Remove</button>
              <span v-else class="text-xs text-gray-500 italic">cannot remove</span>
            </div>
            <div class="grid grid-cols-3 gap-3 text-sm">
              <div>
                <label :for="`sp-attr-${i}-customLabel`" class="block text-xs text-gray-500">Custom Label</label>
                <input :id="`sp-attr-${i}-customLabel`" v-model="attr.customLabel" class="input w-full text-sm" />
              </div>
              <div>
                <label :for="`sp-attr-${i}-inputType`" class="block text-xs text-gray-500">Input Type</label>
                <select :id="`sp-attr-${i}-inputType`" v-model="attr.inputType" class="input w-full text-sm">
                  <option v-for="t in ['TEXT','TEXTAREA','PASSWORD','BOOLEAN','DATE','DATETIME','MULTI_VALUE','DN_LOOKUP','SELECT','HIDDEN_FIXED']"
                    :key="t" :value="t">{{ t }}</option>
                </select>
              </div>
              <div v-if="showFieldFor(attr.inputType, 'defaultValue')">
                <label :for="`sp-attr-${i}-defaultValue`" class="block text-xs text-gray-500">Default Value</label>
                <input :id="`sp-attr-${i}-defaultValue`" v-model="attr.defaultValue" class="input w-full text-sm" />
              </div>
              <div v-if="showFieldFor(attr.inputType, 'computedExpression')">
                <label :for="`sp-attr-${i}-computedExpression`" class="block text-xs text-gray-500">Computed Expression</label>
                <input :id="`sp-attr-${i}-computedExpression`" v-model="attr.computedExpression" class="input w-full text-sm"
                  placeholder="${givenName}.${sn}@corp.com" />
              </div>
              <div v-if="showFieldFor(attr.inputType, 'validationRegex')">
                <label :for="`sp-attr-${i}-validationRegex`" class="block text-xs text-gray-500">Validation Regex</label>
                <input :id="`sp-attr-${i}-validationRegex`" v-model="attr.validationRegex" class="input w-full text-sm" />
              </div>
              <div v-if="showFieldFor(attr.inputType, 'allowedValues')">
                <label :for="`sp-attr-${i}-allowedValues`" class="block text-xs text-gray-500">Allowed Values (JSON array)</label>
                <input :id="`sp-attr-${i}-allowedValues`" v-model="attr.allowedValues" class="input w-full text-sm"
                  placeholder='["Eng","Finance","HR"]' />
              </div>
            </div>
            <div class="flex gap-4 text-xs">
              <label class="flex items-center gap-1">
                <input type="checkbox" v-model="attr.requiredOnCreate"
                  :disabled="isRdnAttribute(attr) || isSchemaRequired(attr)" /> Required
              </label>
              <label class="flex items-center gap-1"><input type="checkbox" v-model="attr.editableOnCreate" /> Editable (create)</label>
              <label class="flex items-center gap-1"><input type="checkbox" v-model="attr.editableOnUpdate" /> Editable (update)</label>
              <label class="flex items-center gap-1"><input type="checkbox" v-model="attr.selfServiceEdit" /> Self-service</label>
              <label v-if="profile.selfRegistrationAllowed" class="flex items-center gap-1"><input type="checkbox" v-model="attr.selfRegistrationEdit" /> Self-registration</label>
              <label class="flex items-center gap-1">
                <input type="checkbox" v-model="attr.hidden"
                  :disabled="((attr.requiredOnCreate || isSchemaRequired(attr)) && !attr.computedExpression) || isRdnAttribute(attr)" /> Hidden
              </label>
            </div>
          </div>
        </div>

        <!-- Layout Tab -->
        <div v-if="modalTab === 'layout'" class="space-y-3">
          <!-- Segmented control -->
          <div class="inline-flex rounded-md border border-gray-300 text-sm">
            <button v-for="mode in [
              { id: 'admin', label: 'Admin' },
              { id: 'self-service', label: 'Self-service' },
              ...(profile.selfRegistrationAllowed ? [{ id: 'registration', label: 'Self-registration' }] : [])
            ]" :key="mode.id"
              :class="['px-4 py-1.5 font-medium transition-colors first:rounded-l-md last:rounded-r-md',
                layoutMode === mode.id
                  ? 'bg-blue-600 text-white border-blue-600'
                  : 'text-gray-600 hover:bg-gray-50']"
              @click="layoutMode = mode.id">
              {{ mode.label }}
            </button>
          </div>

          <!-- Admin layout -->
          <FormLayoutDesigner
            v-if="layoutMode === 'admin'"
            v-model:attributeConfigs="layoutAttributeConfigs"
            v-model:showDnField="profile.showDnField"
          />

          <!-- Self-service layout -->
          <template v-else-if="layoutMode === 'self-service'">
            <div v-if="selfServiceAttributeConfigs.length === 0" class="text-gray-500 text-sm py-4">
              No self-service attributes configured. Mark attributes as "Self-service" on the Attributes tab to include them here.
            </div>
            <FormLayoutDesigner
              v-else
              v-model:attributeConfigs="selfServiceAttributeConfigs"
              :showDnField="false"
              :hideDnToggle="true"
            />
          </template>

          <!-- Self-registration layout -->
          <template v-else-if="layoutMode === 'registration'">
            <div v-if="registrationAttributeConfigs.length === 0" class="text-gray-500 text-sm py-4">
              No self-registration attributes configured. Mark attributes as "Self-registration" on the Attributes tab to include them here.
            </div>
            <FormLayoutDesigner
              v-else
              v-model:attributeConfigs="registrationAttributeConfigs"
              :showDnField="false"
              :hideDnToggle="true"
            />
          </template>
        </div>

        <!-- Groups Tab -->
        <div v-if="modalTab === 'groups'" class="space-y-5">
          <!-- Group inclusion settings -->
          <fieldset class="border border-gray-300 rounded-lg p-4 space-y-2">
            <legend class="text-sm font-semibold text-gray-700 px-1">Group Inclusion</legend>
            <label class="flex items-center gap-2 text-sm">
              <input type="checkbox" v-model="profile.autoIncludeGroups" @change="onAutoIncludeToggle" />
              Automatically include with other profiles
              <span class="text-gray-500 text-xs">(this profile's groups will be added to users provisioned by any other profile in this directory)</span>
            </label>
            <label v-if="!profile.autoIncludeGroups" class="flex items-center gap-2 text-sm">
              <input type="checkbox" v-model="profile.excludeAutoIncludes" />
              Exclude auto-included groups
              <span class="text-gray-500 text-xs">(users provisioned by this profile will not receive groups from auto-included profiles)</span>
            </label>
          </fieldset>

          <!-- Own group assignments -->
          <fieldset class="border border-gray-300 rounded-lg p-4 space-y-3">
            <legend class="text-sm font-semibold text-gray-700 px-1">Own Group Assignments</legend>
            <p class="text-sm text-gray-600">Groups users will be automatically added to on creation.</p>
            <div v-for="(g, i) in profile.groupAssignments" :key="i" class="flex gap-2 items-end">
              <div class="flex-1">
                <label class="block text-xs text-gray-500">Group DN</label>
                <DnPicker v-model="g.groupDn" :directory-id="selectedDirId ?? ''" scope="group" />
              </div>
              <div class="w-40">
                <label :for="`sp-group-${i}-memberAttr`" class="block text-xs text-gray-500">Member Attribute</label>
                <select :id="`sp-group-${i}-memberAttr`" v-model="g.memberAttribute" class="input w-full text-sm">
                  <option>member</option>
                  <option>uniqueMember</option>
                  <option>memberUid</option>
                </select>
              </div>
              <button class="text-red-500 hover:underline text-sm pb-1" @click="removeGroupAssignment(i)">Remove</button>
            </div>
            <button class="btn-secondary text-sm" @click="addGroupAssignment">Add Group</button>
          </fieldset>

          <!-- Additional profiles (hidden for auto-include profiles to prevent cascading) -->
          <fieldset v-if="!profile.autoIncludeGroups" class="border border-gray-300 rounded-lg p-4 space-y-3">
            <legend class="text-sm font-semibold text-gray-700 px-1">Additional Profiles</legend>
            <p class="text-sm text-gray-600">Select other profiles whose group assignments should also be applied to users provisioned with this profile.</p>
            <div v-if="availableAdditionalProfiles.length === 0" class="text-sm text-gray-500 italic">
              No other profiles available in this directory.
            </div>
            <div v-else class="flex flex-wrap gap-2">
              <label v-for="ap in availableAdditionalProfiles" :key="ap.id"
                class="flex items-center gap-1.5 text-sm border rounded px-3 py-1.5 cursor-pointer"
                :class="profile.additionalProfileIds.includes(ap.id) ? 'bg-blue-50 border-blue-300' : 'bg-white border-gray-200 hover:border-gray-300'">
                <input type="checkbox" :checked="profile.additionalProfileIds.includes(ap.id)"
                  @change="toggleAdditionalProfile(ap.id)" class="accent-blue-600" />
                {{ ap.name }}
              </label>
            </div>
          </fieldset>

          <!-- Auto-included profiles (read-only) -->
          <fieldset v-if="autoIncludedProfiles.length > 0 && !profile.excludeAutoIncludes" class="border border-gray-300 rounded-lg p-4 space-y-2">
            <legend class="text-sm font-semibold text-gray-700 px-1">Auto-included Profiles</legend>
            <p class="text-sm text-gray-500">These profiles have "Automatically include with other profiles" enabled and their groups are included automatically.</p>
            <div class="flex flex-wrap gap-2">
              <span v-for="ap in autoIncludedProfiles" :key="ap.id"
                class="inline-flex items-center text-sm bg-green-50 border border-green-200 rounded px-3 py-1">
                {{ ap.name }}
              </span>
            </div>
          </fieldset>

          <!-- Effective groups summary -->
          <fieldset v-if="editing && effectiveGroups.length > 0" class="border border-gray-300 rounded-lg p-4 space-y-2">
            <legend class="text-sm font-semibold text-gray-700 px-1">Effective Group Set</legend>
            <p class="text-sm text-gray-500">The combined set of groups that will be assigned on provisioning (own + additional + auto-included).</p>
            <div class="space-y-1">
              <div v-for="g in effectiveGroups" :key="g.groupDn" class="text-sm text-gray-700 bg-gray-50 px-2 py-1 rounded">
                {{ g.groupDn }} <span class="text-gray-500">({{ g.memberAttribute }})</span>
              </div>
            </div>
          </fieldset>

          <!-- Group membership compliance check -->
          <fieldset v-if="editing && effectiveGroups.length > 0" class="border border-gray-300 rounded-lg p-4 space-y-3">
            <legend class="text-sm font-semibold text-gray-700 px-1">Membership Compliance</legend>
            <p class="text-sm text-gray-500">Check which users in this profile's OU are missing group memberships from the effective group set.</p>

            <button @click="checkCompliance" :disabled="complianceLoading" class="btn-secondary text-sm">
              {{ complianceLoading ? 'Checking...' : 'Check Compliance' }}
            </button>

            <template v-if="complianceChecked">
              <div v-if="complianceRows.length === 0" class="text-sm text-green-700 bg-green-50 rounded-lg px-4 py-3">
                All users are members of all effective groups.
              </div>

              <template v-else>
                <div class="flex items-center justify-between">
                  <p class="text-sm text-gray-600">{{ complianceRows.length }} missing membership(s) found</p>
                  <div class="flex items-center gap-3">
                    <label class="flex items-center gap-1.5 text-xs text-gray-500 cursor-pointer">
                      <input type="checkbox" @change="toggleAllCompliance(($event.target as HTMLInputElement).checked)"
                             :checked="complianceSelectedCount === complianceRows.length && complianceRows.length > 0"
                             class="rounded" />
                      Select all
                    </label>
                    <button @click="applySelectedCompliance" :disabled="complianceSelectedCount === 0 || applyingGroupChanges"
                            class="btn-primary text-xs">
                      {{ applyingGroupChanges ? 'Applying...' : `Add ${complianceSelectedCount} to Groups` }}
                    </button>
                  </div>
                </div>

                <div class="max-h-64 overflow-y-auto border border-gray-200 rounded-lg">
                  <table class="w-full text-sm">
                    <thead class="bg-gray-50 sticky top-0">
                      <tr>
                        <th class="px-3 py-2 text-left text-xs font-semibold text-gray-500 w-10"></th>
                        <th class="px-3 py-2 text-left text-xs font-semibold text-gray-500">User</th>
                        <th class="px-3 py-2 text-left text-xs font-semibold text-gray-500">Missing Group</th>
                      </tr>
                    </thead>
                    <tbody class="divide-y divide-gray-50">
                      <tr v-for="(row, i) in complianceRows" :key="i" :class="row.selected ? 'bg-blue-50/50' : ''">
                        <td class="px-3 py-1.5">
                          <input type="checkbox" v-model="row.selected" :aria-label="`Select ${row.userDn}`" class="rounded" />
                        </td>
                        <td class="px-3 py-1.5 text-gray-700 truncate max-w-xs" :title="row.userDn">{{ row.userDn }}</td>
                        <td class="px-3 py-1.5 text-gray-600 truncate max-w-xs" :title="row.groupDn">{{ row.groupDn }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </template>
            </template>
          </fieldset>
        </div>

        <!-- Policy Tab -->
        <div v-if="modalTab === 'policy'" class="space-y-4">
          <!-- Password Generation Settings -->
          <fieldset class="border border-gray-300 rounded-lg p-3 space-y-3">
            <legend class="text-sm font-semibold text-gray-800 px-1">Password Generation</legend>
            <div class="grid grid-cols-6 gap-3">
              <div class="col-span-2">
                <label for="sp-pw-length" class="block text-xs text-gray-500 mb-1">Length</label>
                <input id="sp-pw-length" type="number" v-model.number="profile.passwordLength" min="8" max="128"
                  class="input w-full text-sm" />
              </div>
              <div class="col-span-4 flex flex-wrap gap-4 items-end pb-1">
                <label class="flex items-center gap-1 text-sm">
                  <input type="checkbox" v-model="profile.passwordUppercase" /> A-Z
                </label>
                <label class="flex items-center gap-1 text-sm">
                  <input type="checkbox" v-model="profile.passwordLowercase" /> a-z
                </label>
                <label class="flex items-center gap-1 text-sm">
                  <input type="checkbox" v-model="profile.passwordDigits" /> 0-9
                </label>
                <label class="flex items-center gap-1 text-sm">
                  <input type="checkbox" v-model="profile.passwordSpecial" /> Special
                </label>
              </div>
            </div>
            <div v-if="profile.passwordSpecial">
              <label for="sp-pw-special" class="block text-xs text-gray-500 mb-1">Special Characters</label>
              <input id="sp-pw-special" v-model="profile.passwordSpecialChars" class="input w-full text-sm font-mono"
                placeholder="!@#$%^&*" />
            </div>
            <label class="flex items-center gap-2 text-sm">
              <input type="checkbox" v-model="profile.emailPasswordToUser" />
              Email generated password to user on creation
            </label>
          </fieldset>

          <!-- Approvals -->
          <fieldset class="border border-gray-300 rounded-lg p-3 space-y-3">
            <legend class="text-sm font-semibold text-gray-800 px-1">Approvals</legend>
            <label class="flex items-center gap-2 text-sm font-medium">
              <input type="checkbox" v-model="approval.requireApproval" /> Require approval for user creation
            </label>
            <div v-if="approval.requireApproval" class="space-y-4">
              <div>
                <label for="sp-approver-mode" class="block text-sm font-medium text-gray-700 mb-1">Approver Mode</label>
                <select id="sp-approver-mode" v-model="approval.approverMode" class="input w-full">
                  <option value="DATABASE">Individual users (select approvers below)</option>
                  <option value="LDAP_GROUP">LDAP Group</option>
                </select>
              </div>
              <div v-if="approval.approverMode === 'LDAP_GROUP'">
                <label class="block text-sm font-medium text-gray-700 mb-1">Approver Group DN</label>
                <DnPicker v-model="approval.approverGroupDn" :directory-id="selectedDirId ?? ''" scope="group" />
              </div>
              <div v-if="approval.approverMode === 'DATABASE'">
                <label class="block text-sm font-medium text-gray-700 mb-2">Approvers</label>
                <div class="space-y-1 max-h-48 overflow-y-auto border rounded p-2">
                  <label v-for="admin in admins.filter(a => a.role === 'ADMIN')" :key="admin.id"
                    class="flex items-center gap-2 text-sm p-1 hover:bg-gray-50 rounded cursor-pointer">
                    <input type="checkbox"
                      :checked="profileApprovers.includes(admin.id)"
                      @change="toggleApprover(admin.id)" />
                    {{ admin.username }}
                    <span class="text-gray-500" v-if="admin.email">({{ admin.email }})</span>
                  </label>
                </div>
              </div>
            </div>
          </fieldset>

          <!-- Per-profile IVIA exemption — self-gates on addon presence
               + the directory having IVIA enabled. Edit mode only (needs
               a persisted profile id). The component renders its own
               fieldset with the IVIA Integration legend so it slots in
               next to Password Generation and Approvals here. -->
          <IsvaProfileOverrideControl
            v-if="editing"
            :directory-id="selectedDirId ?? ''"
            :profile-id="editing ?? ''"
          />
        </div>
      </div>

      <template #footer>
        <div class="flex justify-end gap-3">
          <button class="btn-neutral" @click="showModal = false">Cancel</button>
          <button class="btn-primary" @click="save" :disabled="saving">
            {{ saving ? 'Saving…' : (editing ? 'Update' : 'Create') }}
          </button>
        </div>
      </template>
    </AppModal>

    <ConfirmDialog v-model="showDeleteConfirm"
      :message="`Delete profile '${deleteTarget?.name}'? This cannot be undone.`"
      confirmLabel="Delete" :danger="true" @confirm="doDelete" />

    <!-- Group change preview dialog removed — compliance check is now inline on the Groups tab -->

    <!-- Clone modal -->
    <AppModal v-model="showCloneModal" title="Clone Profile" size="sm">
      <div class="space-y-3">
        <p class="text-sm text-gray-600">Create a copy of <strong>{{ cloneTarget?.name }}</strong> with a new name.</p>
        <div>
          <label for="sp-clone-name" class="block text-sm font-medium text-gray-700 mb-1">New Profile Name</label>
          <input id="sp-clone-name" v-model="cloneName" class="input w-full" placeholder="Profile name"
                 @keydown.enter="doClone" />
        </div>
      </div>
      <template #footer>
        <button @click="showCloneModal = false" class="btn-neutral">Cancel</button>
        <button @click="doClone" :disabled="!cloneName.trim()" class="btn-primary">Clone</button>
      </template>
    </AppModal>
  </div>
</template>

<style scoped>
@reference "tailwindcss";
</style>
