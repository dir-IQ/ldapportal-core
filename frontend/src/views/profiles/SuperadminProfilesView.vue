<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import {
  listAllProfiles, createProfile, updateProfile, deleteProfile, cloneProfile,
  getApprovalConfig, setApprovalConfig, getApprovers, setApprovers,
  evaluateGroupChanges, applySelectiveGroupChanges, probeTargetOu
} from '@/api/profiles'
import { listDirectories } from '@/api/directories'
import { listObjectClasses, getObjectClass } from '@/api/schema'
import { permittedAttrSet, unsupportedAttrs } from '@/utils/schemaPermitted'
import { parseLeadingRdn } from '@/utils/dn'
import { listAdmins } from '@/api/adminManagement'
import type { components } from '@/api/openapi'
import AppModal from '@/components/AppModal.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import FormLayoutDesigner from '@/components/FormLayoutDesigner.vue'
import DnPicker from '@/components/DnPicker.vue'
import DataTable from '@/components/DataTable.vue'
import IsvaProfileOverrideControl from '@/components/profiles/IsvaProfileOverrideControl.vue'
import { setIsvaProfileOverride } from '@/api/isvaConfig'
import { useConfirm } from '@/composables/useConfirm'

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
  themeColor: string
  targetUserDn: string
  targetGroupDn: string
  objectClassNames: string[]
  rdnAttribute: string
  showDnField: boolean
  dnTemplate: string
  dnColumnSpan: number | undefined
  dnSectionName: string | undefined
  dnDisplayOrder: number | undefined
  enabled: boolean
  selfRegistrationAllowed: boolean
  passwordLength: number
  passwordUppercase: boolean
  passwordLowercase: boolean
  passwordDigits: boolean
  passwordSpecial: boolean
  passwordSpecialChars: string
  emailPasswordToUser: boolean
  passwordDisposition: string
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
  themeColor?: string | null
  directoryId: string
  directoryName?: string
  targetUserDn: string
  targetGroupDn?: string | null
  objectClassNames: string[]
  rdnAttribute: string
  showDnField: boolean
  dnTemplate?: string | null
  dnColumnSpan?: number | null
  dnSectionName?: string | null
  dnDisplayOrder?: number | null
  enabled: boolean
  selfRegistrationAllowed: boolean
  passwordLength?: number | null
  passwordUppercase?: boolean | null
  passwordLowercase?: boolean | null
  passwordDigits?: boolean | null
  passwordSpecial?: boolean | null
  passwordSpecialChars?: string | null
  emailPasswordToUser?: boolean | null
  passwordDisposition?: string | null
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
// the rdn marker it renders and the naming marker (attribute appears in the
// DN template's leading RDN or is the designated default).
type LayoutRow = AttributeConfig & { rdn: boolean; naming?: boolean }

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
  { key: 'targetUserDn', label: 'Target User' },
  { key: 'objectClassNames', label: 'Object Classes' },
  { key: 'enabled', label: 'Status' },
]

const notif = useNotificationStore()
const auth = useAuthStore()
const confirm = useConfirm()

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
    name: '', description: '', themeColor: '', targetUserDn: '', targetGroupDn: '',
    objectClassNames: [], rdnAttribute: '',
    showDnField: true, dnTemplate: '',
    dnColumnSpan: undefined, dnSectionName: undefined, dnDisplayOrder: undefined,
    enabled: true, selfRegistrationAllowed: false,
    passwordLength: 16, passwordUppercase: true, passwordLowercase: true,
    passwordDigits: true, passwordSpecial: true, passwordSpecialChars: '!@#$%^&*',
    emailPasswordToUser: false,
    passwordDisposition: 'OPERATOR_ENTERED',
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
  pendingIviaOverride.value = 'INHERIT'  // staged value for the create flow; persisted after profile POST
  showModal.value = true
}

// IVIA per-profile override staged during create. Edit mode persists
// directly via IsvaProfileOverrideControl; create mode captures the
// chosen value here and writes it after the profile-create POST
// returns (no profileId to PUT against until then).
const pendingIviaOverride = ref<'INHERIT' | 'FORCE_OFF'>('INHERIT')

async function openEdit(p: ProfileRow) {
  editing.value = p.id
  selectedDirId.value = p.directoryId
  profile.value = {
    name: p.name, description: p.description || '', themeColor: p.themeColor || '',
    targetUserDn: p.targetUserDn,
    targetGroupDn: p.targetGroupDn || '',
    objectClassNames: [...p.objectClassNames], rdnAttribute: p.rdnAttribute,
    showDnField: p.showDnField, dnTemplate: p.dnTemplate || '',
    dnColumnSpan: p.dnColumnSpan ?? undefined, dnSectionName: p.dnSectionName ?? undefined, dnDisplayOrder: p.dnDisplayOrder ?? undefined,
    enabled: p.enabled,
    selfRegistrationAllowed: p.selfRegistrationAllowed,
    passwordLength: p.passwordLength ?? 16,
    passwordUppercase: p.passwordUppercase ?? true,
    passwordLowercase: p.passwordLowercase ?? true,
    passwordDigits: p.passwordDigits ?? true,
    passwordSpecial: p.passwordSpecial ?? true,
    passwordSpecialChars: p.passwordSpecialChars ?? '!@#$%^&*',
    emailPasswordToUser: p.emailPasswordToUser ?? false,
    passwordDisposition: p.passwordDisposition ?? 'OPERATOR_ENTERED',
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
  if (!profile.value.name || !profile.value.targetUserDn) {
    notif.error('Name and Target User DN are required')
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
  // A HIDDEN_FIXED field injects its Default Value server-side, so a blank one
  // would write an empty attribute. objectClass is exempt — it is system-managed
  // and its value comes from the object-class list, not the Default Value.
  const blankFixed = profile.value.attributeConfigs.find(
    a => a.inputType === 'HIDDEN_FIXED' && !isSystemFixedAttribute(a) && !(a.defaultValue || '').trim())
  if (blankFixed) {
    notif.error(`"${blankFixed.attributeName}" is HIDDEN_FIXED and needs a Default Value`)
    return
  }
  // Surface schema-invalid attributes before persisting: the directory will
  // reject any entry that sets a value for them, so an admin saving such a
  // profile should do it knowingly (custom/extended schemas may be the reason
  // the live lookup disagrees — hence confirm, not block).
  const unsupported = unsupportedAttrs(
    profile.value.attributeConfigs.map(a => a.attributeName),
    schemaPermittedAttrs.value)
  if (unsupported.length > 0) {
    const ok = await confirm({
      title: 'Attributes not in the directory schema',
      message: `${unsupported.join(', ')} ${unsupported.length === 1 ? 'is' : 'are'} not `
        + 'permitted by the selected object classes per the directory schema. Entries '
        + 'that set a value for them will be rejected by the directory. Save anyway?',
      confirmLabel: 'Save anyway',
    })
    if (!ok) return
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
      // Persist the staged IVIA override, if any. Only FORCE_OFF
      // requires a PUT — INHERIT is the documented default for an
      // unconfigured profile, so leaving the row absent is the same
      // outcome and saves a round-trip.
      if (pendingIviaOverride.value === 'FORCE_OFF' && selectedDirId.value) {
        try {
          await setIsvaProfileOverride(selectedDirId.value, data.id, 'FORCE_OFF')
        } catch (e) {
          // Profile is already created; surface the override-save
          // failure but don't reverse the create. Operator can fix
          // it via the editor.
          notif.error(`Profile created but IVIA override save failed: ${errMsg(e)}`)
        }
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

// ── inetOrgPerson seed defaults (client-side) ───────────────────
//
// Curated 27-attribute layout. Kept in sync with the backend
// ProvisioningProfileService.inetOrgPersonDefaults — both define
// the same Identity / Contact / Organization / Account sections,
// same columnSpans, same required flags. When this list changes,
// update both sides; a divergence would mean the UI seed and the
// API seed produce different shapes.
//
// Applied client-side so the button works in the create-profile
// flow (no profileId to PUT against yet). The backend endpoint
// stays useful for API consumers and for re-seeding without the
// editor open.
//
// The list is a curated SUPERSET: at seed time both sides filter it
// against the live directory schema (union of required+optional for
// the profile's object classes), so rows a given directory's chain
// doesn't permit (e.g. 'c'/countryName on standard inetOrgPerson)
// are skipped rather than producing configs the directory rejects.
type SeedRow = {
  attributeName: string
  sectionName: string
  columnSpan: number
  inputType: AttributeConfig['inputType']
  requiredOnCreate: boolean
  editableOnUpdate: boolean
}
const INETORGPERSON_SEED: ReadonlyArray<SeedRow> = [
  // Identity
  { attributeName: 'uid',                      sectionName: 'Identity',     columnSpan: 3, inputType: 'TEXT',      requiredOnCreate: true,  editableOnUpdate: false },
  { attributeName: 'cn',                       sectionName: 'Identity',     columnSpan: 3, inputType: 'TEXT',      requiredOnCreate: true,  editableOnUpdate: true  },
  { attributeName: 'givenName',                sectionName: 'Identity',     columnSpan: 3, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'sn',                       sectionName: 'Identity',     columnSpan: 3, inputType: 'TEXT',      requiredOnCreate: true,  editableOnUpdate: true  },
  { attributeName: 'displayName',              sectionName: 'Identity',     columnSpan: 6, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'initials',                 sectionName: 'Identity',     columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'employeeNumber',           sectionName: 'Identity',     columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'employeeType',             sectionName: 'Identity',     columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  // Contact
  { attributeName: 'mail',                     sectionName: 'Contact',      columnSpan: 6, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'telephoneNumber',          sectionName: 'Contact',      columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'mobile',                   sectionName: 'Contact',      columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'pager',                    sectionName: 'Contact',      columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'facsimileTelephoneNumber', sectionName: 'Contact',      columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'homePhone',                sectionName: 'Contact',      columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'postalAddress',            sectionName: 'Contact',      columnSpan: 6, inputType: 'TEXTAREA',  requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'street',                   sectionName: 'Contact',      columnSpan: 6, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'l',                        sectionName: 'Contact',      columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'st',                       sectionName: 'Contact',      columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'c',                        sectionName: 'Contact',      columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'postalCode',               sectionName: 'Contact',      columnSpan: 2, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  // Organization
  { attributeName: 'title',                    sectionName: 'Organization', columnSpan: 3, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'ou',                       sectionName: 'Organization', columnSpan: 3, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'o',                        sectionName: 'Organization', columnSpan: 3, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'departmentNumber',         sectionName: 'Organization', columnSpan: 3, inputType: 'TEXT',      requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'manager',                  sectionName: 'Organization', columnSpan: 6, inputType: 'DN_LOOKUP', requiredOnCreate: false, editableOnUpdate: true  },
  { attributeName: 'description',              sectionName: 'Organization', columnSpan: 6, inputType: 'TEXTAREA',  requiredOnCreate: false, editableOnUpdate: true  },
  // Account
  { attributeName: 'userPassword',             sectionName: 'Account',      columnSpan: 6, inputType: 'PASSWORD',  requiredOnCreate: true,  editableOnUpdate: false },
]

/**
 * Whether the inetOrgPerson seed is applicable to the current
 * profile — visibility gate for the Seed button. The seed is
 * schema-specific so it wouldn't make sense to surface for a
 * profile whose objectClass list doesn't include inetOrgPerson.
 */
const canSeedInetOrgPerson = computed(() =>
  profile.value.objectClassNames.some(
    oc => oc.toLowerCase() === 'inetorgperson'))

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
  const dn = profile.value.targetUserDn?.trim() ?? ''
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
watch(() => [profile.value.targetUserDn, selectedDirId.value],
  () => { scheduleTargetOuProbe() })

// ── Target Group DN probe / warning banner ──────────────────────
//
// Same debounced existence check as the user DN, but advisory only:
// the group container isn't validated server-side (group assignments
// reference explicit DNs), so a 'missing' result never blocks save.
const targetGroupProbeState = ref<TargetOuProbeState>('idle')
let targetGroupProbeTimer: ReturnType<typeof setTimeout> | null = null
let targetGroupProbeToken = 0

function scheduleTargetGroupProbe() {
  if (targetGroupProbeTimer) clearTimeout(targetGroupProbeTimer)
  const dn = profile.value.targetGroupDn?.trim() ?? ''
  const dirId = selectedDirId.value
  if (!dn || !dirId) {
    targetGroupProbeState.value = 'idle'
    return
  }
  targetGroupProbeState.value = 'checking'
  const myToken = ++targetGroupProbeToken
  targetGroupProbeTimer = setTimeout(async () => {
    try {
      const { data } = await probeTargetOu(dirId, dn)
      if (myToken !== targetGroupProbeToken) return
      targetGroupProbeState.value = data?.exists ? 'exists' : 'missing'
    } catch {
      if (myToken === targetGroupProbeToken) targetGroupProbeState.value = 'idle'
    }
  }, 400)
}

watch(() => [profile.value.targetGroupDn, selectedDirId.value],
  () => { scheduleTargetGroupProbe() })
/**
 * Apply the inetOrgPerson seed to the in-memory profile state.
 *
 * Works during create (no profileId yet) — purely client-side
 * state mutation; the seeded configs ride along on the next POST.
 * Also works during edit, including when attributeConfigs already
 * has content (confirms replace first). The change isn't
 * persisted until Save.
 *
 * Existing entries are replaced wholesale rather than merged —
 * a partial merge would leave the operator with an inconsistent
 * mix of auto-populated MUST attrs (no sections / span 6 / TEXT)
 * and seeded ones (sections / mixed spans / typed inputs).
 */
async function doSeedDefaults() {
  if (profile.value.attributeConfigs.length > 0) {
    const ok = await confirm({
      title: 'Replace existing attribute configs?',
      message: `This profile already has ${profile.value.attributeConfigs.length} `
        + 'attribute config(s). Seeding will replace them all with the '
        + 'inetOrgPerson defaults. The change isn\'t persisted until you Save.',
      confirmLabel: 'Replace and seed',
    })
    if (!ok) return
  }
  // Filter the static seed against the live schema: the list is a curated
  // superset of commonly useful attributes, and a given directory's
  // inetOrgPerson chain may not permit all of them (e.g. 'c'/countryName is
  // not allowed by the standard chain — entries setting it get rejected at
  // save). When schema isn't loaded, seed unfiltered as before.
  const permitted = schemaPermittedAttrs.value
  const skipped = unsupportedAttrs(INETORGPERSON_SEED.map(r => r.attributeName), permitted)
  const skippedSet = new Set(skipped.map(s => s.toLowerCase()))
  const rows = INETORGPERSON_SEED.filter(r => !skippedSet.has(r.attributeName.toLowerCase()))

  profile.value.attributeConfigs = rows.map(row => ({
    attributeName:             row.attributeName,
    // Friendly label from the ATTR_LABELS map (sn → 'Last Name',
    // mail → 'Email', etc), matching addObjectClass()'s auto-pop
    // behaviour. guessLabel falls back to camelCase / snake_case
    // splitting for anything not in the map.
    customLabel:               guessLabel(row.attributeName),
    inputType:                 row.inputType,
    requiredOnCreate:          row.requiredOnCreate,
    editableOnCreate:          true,
    editableOnUpdate:          row.editableOnUpdate,
    selfServiceEdit:           isSelfServiceEditable(row.attributeName),
    selfRegistrationEdit:      isSelfServiceEditable(row.attributeName),
    defaultValue:              '',
    computedExpression:        '',
    validationRegex:           '',
    validationMessage:         '',
    allowedValues:             '',
    minLength:                 null,
    maxLength:                 null,
    sectionName:               row.sectionName,
    columnSpan:                row.columnSpan,
    hidden:                    false,
    // Per-surface layout (self-service / registration) is left
    // unset by the seed; admins configure those independently via
    // the Self-Service / Registration tabs.
    registrationSectionName:   null,
    registrationColumnSpan:    null,
    registrationDisplayOrder:  null,
    selfServiceSectionName:    null,
    selfServiceColumnSpan:     null,
    selfServiceDisplayOrder:   null,
  }))
  notif.success(skipped.length
    ? `Seeded ${rows.length} attributes from inetOrgPerson defaults `
      + `(skipped ${skipped.join(', ')} — not permitted by this directory's schema)`
    : `Seeded ${rows.length} attributes from inetOrgPerson defaults`)
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

/**
 * Attribute names the live schema permits for the currently selected object
 * classes (union of required + optional from ocSchemaCache, lower-cased).
 * Null when no selected class has schema loaded — validation is then skipped
 * rather than flagging everything.
 */
const schemaPermittedAttrs = computed(() =>
  permittedAttrSet(ocSchemaCache.value, profile.value.objectClassNames))

/** Whether the live schema rejects this attribute for the selected classes. */
function isSchemaUnsupported(attr: AttributeConfig): boolean {
  const permitted = schemaPermittedAttrs.value
  return !!permitted && !permitted.has(attr.attributeName.toLowerCase())
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

// Helper: check if an attribute is the designated (default) RDN attribute
function isRdnAttribute(attr: AttributeConfig) {
  return attr.attributeName === profile.value.rdnAttribute
}

// True when a DN template is set: it then drives naming and locks the RDN picker.
const dnTemplateActive = computed(() => !!(profile.value.dnTemplate || '').trim())

// Naming attributes derived from the DN template's leading RDN. A template
// like "o=${o}+cn=${cn},ou=People,…" makes *those* attribute types name new
// entries (a multi-valued RDN), regardless of the designated RDN attribute.
const templateNamingAttrs = computed<Set<string>>(() => {
  const tpl = (profile.value.dnTemplate || '').trim()
  if (!tpl) return new Set<string>()
  return new Set(parseLeadingRdn(tpl).map(a => a.name.toLowerCase()))
})

// An attribute whose value names new entries. A DN template is authoritative
// when present — the entry is named by the template's leading RDN, so *those*
// attributes are the naming ones and the designated RDN picker is overridden
// (and locked). Without a template, the designated RDN attribute names entries.
// Naming attributes are forced required and can't be removed — they feed the DN.
function isNamingAttribute(attr: AttributeConfig): boolean {
  if ((profile.value.dnTemplate || '').trim()) {
    return templateNamingAttrs.value.has(attr.attributeName.toLowerCase())
  }
  return isRdnAttribute(attr)
}

// Helper: check if an attribute is schema-required
function isSchemaRequired(attr: AttributeConfig) {
  return schemaRequiredAttrs.value.has(attr.attributeName.toLowerCase())
}

// objectClass is a system-managed HIDDEN_FIXED attribute: it is auto-added and
// its value derives from the profile's object-class list, not operator input.
// Its input type must stay HIDDEN_FIXED (making it an editable field is
// incoherent), so the type selector is locked for it.
function isSystemFixedAttribute(attr: AttributeConfig) {
  return attr.attributeName.toLowerCase() === 'objectclass'
}

// True when the Hidden toggle is locked *only* because a required attribute has
// no computed expression — the one lock the operator can lift (by giving it a
// computed value). RDN and auto-generated-password locks are intentional and
// not resolvable here. Used to surface a hint next to the disabled checkbox.
function hiddenLockedPendingComputed(attr: AttributeConfig): boolean {
  return (attr.requiredOnCreate || isSchemaRequired(attr))
    && !attr.computedExpression
    && !isRdnAttribute(attr)
    && !isAutoGeneratedPasswordField(attr)
    && attr.inputType !== 'HIDDEN_FIXED'
}

// Helper: check if an attribute can be removed (naming and required attributes cannot)
function canRemoveAttribute(attr: AttributeConfig) {
  return !isNamingAttribute(attr) && !isSchemaRequired(attr) && !attr.requiredOnCreate
}

// Alphabetical view of the attributes for the Attributes tab, so a long list is
// quick to scan. This sorts a shallow copy of the *same* config objects, never
// profile.attributeConfigs itself — that array's order is load-bearing (it
// drives the saved displayOrder and the form layout), and v-model edits on the
// copied references still mutate the real configs.
const sortedAttributeConfigs = computed(() =>
  [...profile.value.attributeConfigs].sort((a, b) =>
    a.attributeName.localeCompare(b.attributeName, undefined, { sensitivity: 'base' })))

// Remove by object reference — the Attributes tab renders the sorted view, so a
// row's position there is not its index in profile.attributeConfigs.
function removeAttributeConfig(attr: AttributeConfig) {
  const idx = profile.value.attributeConfigs.indexOf(attr)
  if (idx >= 0) profile.value.attributeConfigs.splice(idx, 1)
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

// True when this attribute name is (or would be) a naming attribute: the
// designated RDN, or one referenced by the DN template's leading RDN.
function isNamingAttributeName(name: string): boolean {
  const lower = name.toLowerCase()
  return lower === (profile.value.rdnAttribute || '').toLowerCase()
    || templateNamingAttrs.value.has(lower)
}

// The RDN attribute must exist as a form field so its value can be supplied at
// create time. Without a DN template (which drives naming itself), designating
// an RDN adds it — required — if it isn't configured yet, and forces an existing
// one required unless it's computed (a computed RDN derives its own value).
// Empty-config profiles use the fallback create form and are left alone.
function ensureRdnAttributePresent() {
  const name = profile.value.rdnAttribute
  if (!name) return
  if ((profile.value.dnTemplate || '').trim()) return
  if (profile.value.attributeConfigs.length === 0) return
  const existing = profile.value.attributeConfigs.find(
    a => a.attributeName.toLowerCase() === name.toLowerCase())
  if (existing) {
    if (!existing.computedExpression) existing.requiredOnCreate = true
    return
  }
  profile.value.attributeConfigs.push({
    attributeName: name, customLabel: guessLabel(name), inputType: 'TEXT',
    requiredOnCreate: true, editableOnCreate: true,
    editableOnUpdate: true, selfServiceEdit: false, selfRegistrationEdit: false,
    defaultValue: '', computedExpression: '', validationRegex: '',
    validationMessage: '', allowedValues: '', minLength: null,
    maxLength: null, sectionName: '', columnSpan: 6, hidden: false,
    registrationSectionName: null, registrationColumnSpan: null, registrationDisplayOrder: null,
    selfServiceSectionName: null, selfServiceColumnSpan: null, selfServiceDisplayOrder: null,
  })
}

function onRdnAttributeChange() {
  ensureRdnAttributePresent()
}

function addSelectedAttributes() {
  for (const name of attrPickerSelection.value) {
    profile.value.attributeConfigs.push({
      attributeName: name, customLabel: guessLabel(name), inputType: 'TEXT',
      requiredOnCreate: isNamingAttributeName(name) || schemaRequiredAttrs.value.has(name.toLowerCase()), editableOnCreate: true,
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

// Delivering the password by email needs a required 'mail' attribute to send to.
function ensureRequiredMailAttribute() {
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
}

// Both email-delivery paths (the explicit flag, and the GENERATED_DELIVERED
// disposition) require a 'mail' attribute — mirror the server-side guard.
watch(() => profile.value.emailPasswordToUser, (enabled) => {
  if (enabled) ensureRequiredMailAttribute()
})
watch(() => profile.value.passwordDisposition, (disposition) => {
  if (disposition === 'GENERATED_DELIVERED') ensureRequiredMailAttribute()
  enforceAutoGeneratedPasswordConfig()
}, { immediate: true })

// When the password is auto-generated server-side, its attribute toggles are
// informational and fixed: required + hidden, everything else off. Enforce that
// in the model so the saved config matches what the create form actually does
// (and so the toggles can be rendered read-only). Switching back to
// operator-entered restores a usable, visible field.
function enforceAutoGeneratedPasswordConfig() {
  const pw = profile.value.attributeConfigs.find(isPasswordField)
  if (!pw) return
  if (isAutoGeneratedPasswordField(pw)) {
    pw.requiredOnCreate = true
    pw.hidden = true
    pw.editableOnCreate = false
    pw.editableOnUpdate = false
    pw.selfServiceEdit = false
    pw.selfRegistrationEdit = false
  } else if (pw.hidden || !pw.editableOnCreate) {
    pw.hidden = false
    pw.editableOnCreate = true
  }
}

// When requiredOnCreate is set, ensure hidden is cleared (unless attribute has a
// computed expression, or it's the auto-generated password which is meant to be
// hidden + required).
watch(() => profile.value.attributeConfigs.map(a => a.requiredOnCreate), () => {
  for (const attr of profile.value.attributeConfigs) {
    if (attr.requiredOnCreate && attr.hidden && !attr.computedExpression
        && !isAutoGeneratedPasswordField(attr)) attr.hidden = false
  }
})

// Helper: determine which fields to show based on input type
function showFieldFor(inputType: string, fieldName: string) {
  const rules: Record<string, string[]> = {
    defaultValue:       ['TEXT', 'TEXTAREA', 'PASSWORD', 'DATE', 'DATETIME', 'MULTI_VALUE', 'HIDDEN_FIXED', 'SELECT'],
    allowedValues:      ['SELECT'],
    computedExpression: ['TEXT', 'TEXTAREA', 'PASSWORD', 'MULTI_VALUE', 'DATE', 'DATETIME', 'DN_LOOKUP', 'DN'],
    validationRegex:    ['TEXT', 'TEXTAREA', 'PASSWORD', 'MULTI_VALUE'],
  }
  return (rules[fieldName] || []).includes(inputType)
}

// Naming attributes are always required — their values feed the entry's DN.
// Covers the designated RDN attribute and every attribute the DN template's
// leading RDN references (a multi-valued-RDN template marks several).
watch(() => [profile.value.rdnAttribute, profile.value.dnTemplate], () => {
  for (const attr of profile.value.attributeConfigs) {
    if (isNamingAttribute(attr)) attr.requiredOnCreate = true
  }
})

// A DN template is authoritative for naming, so keep the designated RDN
// attribute reconciled to the template's leading RDN — otherwise the picker and
// the template disagree (two "RDN" badges), and the picker's value never names
// the entry. Only rewrites it when it genuinely diverges, so a profile whose
// picker already matches the template is left untouched.
watch(() => profile.value.dnTemplate, (tpl) => {
  const t = (tpl || '').trim()
  if (!t) return
  const leadingNames = parseLeadingRdn(t).map(a => a.name)
  if (!leadingNames.length) return
  const currentLower = (profile.value.rdnAttribute || '').toLowerCase()
  if (!leadingNames.some(n => n.toLowerCase() === currentLower)) {
    profile.value.rdnAttribute = leadingNames[0]
  }
})

// A HIDDEN_FIXED attribute is never shown to the end user — its value is applied
// server-side. Keep `hidden` in lock-step so the two representations can't drift:
// a HIDDEN_FIXED field left with hidden=false (older/migrated profiles, or the
// operator picking the type) would otherwise leak into the form-layout preview.
// Runs on load (immediate) and whenever an input type changes.
watch(() => profile.value.attributeConfigs.map(a => a.inputType), () => {
  for (const attr of profile.value.attributeConfigs) {
    if (attr.inputType === 'HIDDEN_FIXED') attr.hidden = true
  }
}, { immediate: true })

// Attribute configs with RDN flag for the layout designer
//
// When the profile auto-generates the password (GENERATED_* disposition), the
// password field is never shown to the operator, so it is filtered out of the
// admin form-layout designer. It is preserved in profile.attributeConfigs — the
// setter merges it back — so the (schema-required) password config is never
// dropped from the saved profile, just not laid out.
function isPasswordField(a: { inputType?: string | null; attributeName: string }): boolean {
  return a.inputType === 'PASSWORD' || a.attributeName.toLowerCase() === 'userpassword'
}

function isAutoGeneratedPasswordField(a: { inputType?: string | null; attributeName: string }): boolean {
  const generated = profile.value.passwordDisposition === 'GENERATED_DELIVERED'
    || profile.value.passwordDisposition === 'GENERATED_DISCARDED'
  return generated && isPasswordField(a)
}

// Fields the admin form-layout designer lays out. Excludes fields the operator
// never sees on the admin form: the auto-generated password and HIDDEN_FIXED
// attributes (server-applied constants such as objectClass). These are filtered
// out of the designer and merged back untouched by the setter, so they survive
// the wholesale replace and never surface in the layout preview.
function isAdminLayoutManaged(a: AttributeConfig): boolean {
  return !isAutoGeneratedPasswordField(a) && a.inputType !== 'HIDDEN_FIXED'
}

const layoutAttributeConfigs = computed<LayoutRow[]>({
  get() {
    return profile.value.attributeConfigs
      .filter(isAdminLayoutManaged)
      .map(a => ({
        ...a,
        rdn: a.attributeName === profile.value.rdnAttribute,
        naming: isNamingAttribute(a),
      }))
  },
  set(val: LayoutRow[]) {
    const laidOut = val.map(({ rdn, naming, ...rest }) => rest)
    // Re-append every field the designer doesn't emit, so it survives the
    // wholesale replace below: the auto-generated password and HIDDEN_FIXED
    // fields (filtered out of the designer), plus hidden fields (the designer
    // receives them to track un-hide positions but renders — and emits — only
    // visible ones, so without this they'd be dropped from the saved profile).
    // The designer never toggles `hidden`, so a hidden field can't also appear
    // in `laidOut`; there's no duplication.
    const preserved = profile.value.attributeConfigs.filter(a => !isAdminLayoutManaged(a) || a.hidden)
    profile.value.attributeConfigs = [...laidOut, ...preserved]
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
      <template #cell-targetUserDn="{ value }">
        <span class="cell-muted truncate block max-w-xs" :title="value">{{ value }}</span>
      </template>
      <template #cell-objectClassNames="{ value }">
        <span class="cell-muted">{{ value.join(', ') }}</span>
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
    <AppModal v-model="showModal" size="xl" movable resizable>
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
          <div>
            <label for="sp-name" class="block text-sm font-medium text-gray-700 mb-1">Name</label>
            <input id="sp-name" v-model="profile.name" class="input w-full" placeholder="e.g. Full-Time Engineer" />
          </div>
          <div>
            <label for="sp-description" class="block text-sm font-medium text-gray-700 mb-1">Description</label>
            <textarea id="sp-description" v-model="profile.description" class="input w-full" rows="2"></textarea>
          </div>
          <div>
            <label for="sp-theme-color" class="block text-sm font-medium text-gray-700 mb-1">Theme Color</label>
            <div class="flex items-center gap-2">
              <!-- A native colour input can't represent "unset" (it always holds
                   a value), so bind :value with a display fallback and commit on
                   @input; the hex field and Clear button own the unset state. -->
              <input id="sp-theme-color" type="color"
                :value="profile.themeColor || '#2563eb'"
                @input="profile.themeColor = ($event.target as HTMLInputElement).value"
                class="h-9 w-10 rounded border border-gray-300 cursor-pointer p-0.5 shrink-0"
                aria-label="Profile theme colour picker" />
              <input v-model="profile.themeColor" class="input w-40" maxlength="7"
                placeholder="#2563eb (unset)" aria-label="Theme colour hex" />
              <button v-if="profile.themeColor" type="button" class="btn-neutral text-xs"
                @click="profile.themeColor = ''">Clear</button>
            </div>
            <p class="mt-1 text-xs text-gray-500">
              Optional. When set, the user and group list headers (and the new/edit
              user and group modals) show a band of this colour instead of the
              profile name in blue. Leave blank for no theme.
            </p>
          </div>
          <div v-if="!editing">
            <label for="sp-directory" class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
            <select id="sp-directory" v-model="selectedDirId" class="input w-full">
              <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Target User DN</label>
            <DnPicker v-model="profile.targetUserDn" :directory-id="selectedDirId ?? ''"
              placeholder="e.g. ou=engineers,ou=people,dc=corp" />
            <!-- Target-User-DN warning banner: surfaces when the probe says
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
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Target Group DN</label>
            <DnPicker v-model="profile.targetGroupDn" :directory-id="selectedDirId ?? ''"
              placeholder="Defaults to the Target User DN" />
            <p class="mt-1 text-xs text-gray-500">
              Container the profile's groups live under. Scopes the group browser below.
              Leave blank to keep groups in the same subtree as users.
            </p>
            <!-- Advisory only: an absent group container doesn't block save
                 (group assignments still reference explicit DNs). -->
            <div v-if="targetGroupProbeState === 'missing'"
                 class="mt-2 rounded-md border border-yellow-200 bg-yellow-50 p-3 text-sm text-yellow-900 flex items-start gap-2">
              <span aria-hidden="true" class="mt-0.5">⚠</span>
              <div class="flex-1">
                <div class="font-medium">This container isn't present in the directory.</div>
                <div class="text-xs mt-0.5">
                  The group browser will show no entries under it until it exists.
                </div>
              </div>
            </div>
            <div v-else-if="targetGroupProbeState === 'exists'"
                 class="mt-2 text-xs text-green-700 inline-flex items-center gap-1">
              <span aria-hidden="true">✓</span> Container resolves in the directory.
            </div>
            <div v-else-if="targetGroupProbeState === 'checking'"
                 class="mt-2 text-xs text-gray-500">Checking…</div>
          </div>
          <div class="grid grid-cols-3 gap-4 items-start">
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
                :disabled="profile.objectClassNames.length === 0 || dnTemplateActive" @change="onRdnAttributeChange">
                <option value="">{{ profile.objectClassNames.length === 0 ? 'Add an object class first' : 'Select RDN attribute…' }}</option>
                <option v-for="attr in rdnCandidates" :key="attr" :value="attr">{{ attr }}</option>
              </select>
              <p v-if="dnTemplateActive" class="text-[11px] text-blue-600 mt-1">
                Defined by the DN template (Layout tab): its leading RDN names entries, so this
                is locked to match.
              </p>
              <p v-else class="text-[11px] text-gray-500 mt-1">
                Default naming attribute: seeds the DN as
                <code>&lt;attribute&gt;=&lt;value&gt;,&lt;target OU&gt;</code> and names self-service
                registrations. A DN template (Layout tab) overrides which attributes name
                admin-created entries — including multi-valued RDNs such as
                <code>o=${'{'}o{'}'}+cn=${'{'}cn{'}'}</code>.
              </p>
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
              v-if="canSeedInetOrgPerson"
              class="btn-secondary text-sm"
              @click="doSeedDefaults"
            >Seed inetOrgPerson defaults</button>
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
                    <span class="font-mono text-[13px]">{{ attr }}</span>
                  </label>
                </div>
                <button class="btn-primary text-sm mt-2" :disabled="attrPickerSelection.length === 0" @click="addSelectedAttributes">
                  Add {{ attrPickerSelection.length }} attribute{{ attrPickerSelection.length !== 1 ? 's' : '' }}
                </button>
              </template>
            </div>
          <div v-if="profile.attributeConfigs.length === 0" class="text-gray-500 text-sm">
            Add object classes in the General tab to populate attributes.
          </div>
          <div v-for="(attr, i) in sortedAttributeConfigs" :key="attr.attributeName"
            class="border border-gray-300 rounded-lg p-3 space-y-2">
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-2">
                <span class="font-mono text-sm font-medium bg-gray-100 text-gray-800 border border-gray-200 rounded px-1.5 py-0.5">{{ attr.attributeName }}</span>
                <span v-if="isNamingAttribute(attr)"
                  class="text-[10px] bg-amber-100 text-amber-700 rounded px-1.5 py-0.5 font-medium"
                  :title="templateNamingAttrs.has(attr.attributeName.toLowerCase())
                    ? 'Names new entries via the DN template leading RDN'
                    : 'Default naming attribute — seeds the default DN composition'">RDN</span>
                <span v-if="isSchemaRequired(attr)"
                  class="text-[10px] bg-blue-50 text-blue-600 rounded px-1.5 py-0.5 font-medium">schema required</span>
                <span v-if="isSchemaUnsupported(attr)"
                  class="text-[10px] bg-red-50 text-red-600 rounded px-1.5 py-0.5 font-medium"
                  title="The directory schema does not allow this attribute for the selected object classes — the directory will reject entries that set it.">not in schema</span>
              </div>
              <button v-if="canRemoveAttribute(attr)"
                class="text-red-500 text-xs hover:underline"
                @click="removeAttributeConfig(attr)">Remove</button>
              <span v-else class="text-xs text-gray-500 italic">cannot remove</span>
            </div>
            <div class="grid grid-cols-3 gap-3 text-sm">
              <div>
                <label :for="`sp-attr-${i}-customLabel`" class="block text-xs text-gray-500">Custom Label</label>
                <input :id="`sp-attr-${i}-customLabel`" v-model="attr.customLabel" class="input w-full text-sm" />
              </div>
              <div>
                <label :for="`sp-attr-${i}-inputType`" class="block text-xs text-gray-500">Input Type</label>
                <select :id="`sp-attr-${i}-inputType`" v-model="attr.inputType" class="input w-full text-sm"
                  :disabled="isSystemFixedAttribute(attr)"
                  :title="isSystemFixedAttribute(attr) ? 'objectClass is system-managed and always HIDDEN_FIXED.' : undefined">
                  <option v-for="t in ['TEXT','TEXTAREA','PASSWORD','BOOLEAN','DATE','DATETIME','MULTI_VALUE','DN_LOOKUP','DN','SELECT','HIDDEN_FIXED']"
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
            <!-- When the password is auto-generated, its toggles are fixed
                 (required + hidden, the rest off) and rendered read-only. -->
            <div class="flex gap-4 text-xs">
              <label class="flex items-center gap-1">
                <input type="checkbox" v-model="attr.requiredOnCreate"
                  :disabled="isNamingAttribute(attr) || isSchemaRequired(attr) || isAutoGeneratedPasswordField(attr)" /> Required
              </label>
              <label class="flex items-center gap-1"><input type="checkbox" v-model="attr.editableOnCreate" :disabled="isAutoGeneratedPasswordField(attr)" /> Editable (create)</label>
              <label class="flex items-center gap-1"><input type="checkbox" v-model="attr.editableOnUpdate" :disabled="isAutoGeneratedPasswordField(attr)" /> Editable (update)</label>
              <label class="flex items-center gap-1"><input type="checkbox" v-model="attr.selfServiceEdit" :disabled="isAutoGeneratedPasswordField(attr)" /> Self-service</label>
              <label v-if="profile.selfRegistrationAllowed" class="flex items-center gap-1"><input type="checkbox" v-model="attr.selfRegistrationEdit" :disabled="isAutoGeneratedPasswordField(attr)" /> Self-registration</label>
              <label class="flex items-center gap-1"
                :title="attr.inputType === 'HIDDEN_FIXED'
                  ? 'HIDDEN_FIXED attributes are applied server-side and never shown, so they are always hidden.'
                  : (hiddenLockedPendingComputed(attr) ? 'A required attribute can only be hidden once it has a computed expression, so the value can be set without operator input.' : undefined)">
                <input type="checkbox" v-model="attr.hidden"
                  :disabled="attr.inputType === 'HIDDEN_FIXED' || ((attr.requiredOnCreate || isSchemaRequired(attr)) && !attr.computedExpression) || isRdnAttribute(attr) || isAutoGeneratedPasswordField(attr)" /> Hidden
              </label>
              <span v-if="hiddenLockedPendingComputed(attr)" class="text-gray-400 italic">
                add a computed expression to allow hiding
              </span>
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
            v-model:dnTemplate="profile.dnTemplate"
            v-model:dnColumnSpan="profile.dnColumnSpan"
            v-model:dnSectionName="profile.dnSectionName"
            v-model:dnDisplayOrder="profile.dnDisplayOrder"
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
                <DnPicker v-model="g.groupDn" :directory-id="selectedDirId ?? ''" scope="group"
                  :base-dn="profile.targetGroupDn || ''" />
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
            <div>
              <label for="sp-pw-disposition" class="block text-xs text-gray-500 mb-1">Password handling</label>
              <select id="sp-pw-disposition" v-model="profile.passwordDisposition" class="input w-full md:w-1/2 text-sm">
                <option value="OPERATOR_ENTERED">Operator enters or generates it in the form</option>
                <option value="GENERATED_DELIVERED">Auto-generate and email it to the user</option>
                <option value="GENERATED_DISCARDED">Auto-generate, never shown (e.g. certificate-only login)</option>
              </select>
              <p class="text-xs text-gray-500 mt-1">
                <template v-if="profile.passwordDisposition === 'GENERATED_DISCARDED'">
                  The server writes a random throwaway to satisfy a schema-required password; the
                  password field is hidden on the create form and the value is surfaced nowhere.
                </template>
                <template v-else-if="profile.passwordDisposition === 'GENERATED_DELIVERED'">
                  The server generates the password at create time and emails it to the user; the
                  password field is hidden on the create form. Requires a required <code>mail</code> attribute.
                </template>
                <template v-else>
                  The operator types or generates the password in the visible field. The settings
                  below control the generator used by the “Generate” button.
                </template>
              </p>
            </div>
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
            <label v-if="profile.passwordDisposition === 'OPERATOR_ENTERED'" class="flex items-center gap-2 text-sm">
              <input type="checkbox" v-model="profile.emailPasswordToUser" />
              Email the password to the user on creation
            </label>
          </fieldset>

          <!-- Approvals. Hidden when approvals are globally disabled — the
               profile's saved approval config is preserved (we don't clear
               `approval`), just not editable while the master switch is off. -->
          <fieldset class="border border-gray-300 rounded-lg p-3 space-y-3">
            <legend class="text-sm font-semibold text-gray-800 px-1">Approvals</legend>
            <p v-if="!auth.isApprovalsEnabled" class="text-xs text-gray-500">
              Approvals are globally disabled in Settings → User/Group Edits. This
              profile's approval settings are preserved but inactive until approvals
              are re-enabled.
            </p>
            <template v-else>
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
            </template>
          </fieldset>

          <!-- Per-profile IVIA exemption — self-gates on addon presence
               + the directory having IVIA enabled. Works in both edit
               and create modes: edit mode persists toggles directly,
               create mode stages the value locally and the host
               persists it after the profile-create POST succeeds
               (see pendingIviaOverride + the save() handler). -->
          <IsvaProfileOverrideControl
            :directory-id="selectedDirId ?? ''"
            :profile-id="editing"
            @staged-change="pendingIviaOverride = $event"
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
