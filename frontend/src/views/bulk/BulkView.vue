<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <h1 class="text-2xl font-bold text-gray-900 mb-4">Bulk Operations</h1>
    <p class="text-sm text-gray-500 mt-1 mb-4">Import, export, and delete users and groups via CSV</p>

    <!-- Entity type selector -->
    <div class="flex gap-2 mb-4">
      <button @click="entityType = 'users'"
        :class="['px-4 py-1.5 text-sm rounded-lg border transition-colors',
          entityType === 'users' ? 'bg-blue-50 border-blue-300 text-blue-700 font-medium' : 'border-gray-200 text-gray-600 hover:bg-gray-50']">
        Users
      </button>
      <button @click="entityType = 'groups'"
        :class="['px-4 py-1.5 text-sm rounded-lg border transition-colors',
          entityType === 'groups' ? 'bg-blue-50 border-blue-300 text-blue-700 font-medium' : 'border-gray-200 text-gray-600 hover:bg-gray-50']">
        Groups
      </button>
    </div>

    <!-- Tabs -->
    <div class="flex border-b border-gray-200 mb-0">
      <button @click="activeTab = 'import'"
        class="px-5 py-2.5 text-sm font-medium -mb-px"
        :class="activeTab === 'import' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-500 hover:text-gray-700'">
        Import
      </button>
      <button @click="activeTab = 'export'"
        class="px-5 py-2.5 text-sm font-medium -mb-px"
        :class="activeTab === 'export' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-500 hover:text-gray-700'">
        Export
      </button>
      <!-- Delete is users-only and entitlement-gated; the destructive verb
           reads in red so it's distinct from the Import/Export tabs. -->
      <button v-if="entityType === 'users' && canBulkDelete" @click="activeTab = 'delete'"
        class="px-5 py-2.5 text-sm font-medium -mb-px"
        :class="activeTab === 'delete' ? 'border-b-2 border-red-600 text-red-600' : 'text-gray-500 hover:text-red-600'">
        Delete
      </button>
    </div>

    <!-- Import tab — Users -->
    <section v-if="activeTab === 'import' && entityType === 'users'" class="bg-white border border-gray-200 border-t-0 rounded-b-xl p-6">
      <h2 class="text-lg font-semibold mb-3">Import Users from CSV</h2>
      <div class="space-y-2">
        <!-- 12-column grid: Active profile (4) + Template wrapper (4) + CSV File (4).
             items-start so the Active-profile field's DN sub-line can hang below
             without dragging the other controls down. -->
        <div class="grid grid-cols-12 gap-2 items-start">
          <ActiveProfileField class="col-span-4"
            :name="activeProfile?.name" :color="activeProfile?.themeColor" :dn="activeUserTargetDn" />

          <!-- Template picker + actions dropdown -->
          <div class="col-span-4 flex gap-2 items-end">
            <div class="flex-1">
              <label for="bulk-import-template" class="block text-sm font-medium text-gray-700 mb-1">Import Template <span class="text-red-500">*</span></label>
              <select id="bulk-import-template" v-model="selectedTemplateId" class="input w-full" @change="onTemplateSelected">
                <option value="">— Select a template —</option>
                <option v-for="t in templates" :key="t.id" :value="t.id">{{ t.name }}</option>
              </select>
            </div>
            <div class="relative" ref="menuRef">
              <button @click="showTemplateMenu = !showTemplateMenu" class="btn-primary whitespace-nowrap flex items-center gap-1">
                Template <span class="text-xs">&#9660;</span>
              </button>
              <div v-if="showTemplateMenu" class="absolute right-0 mt-1 w-44 bg-white border border-gray-200 rounded-lg shadow-lg z-10 py-1">
                <button @click="menuAction('add')" class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-50">
                  Add Template
                </button>
                <button @click="menuAction('edit')" :disabled="!selectedTemplate"
                  class="w-full text-left px-4 py-2 text-sm hover:bg-gray-50"
                  :class="selectedTemplate ? 'text-gray-700' : 'text-gray-300 cursor-not-allowed'">
                  Edit Template
                </button>
                <button @click="menuAction('delete')" :disabled="!selectedTemplate"
                  class="w-full text-left px-4 py-2 text-sm hover:bg-red-50"
                  :class="selectedTemplate ? 'text-red-600' : 'text-gray-300 cursor-not-allowed'">
                  Delete Template
                </button>
              </div>
            </div>
          </div>

          <div class="col-span-4">
            <label class="block text-sm font-medium text-gray-700 mb-1">CSV File <span class="text-red-500">*</span></label>
            <!-- Custom file picker: bordered .input wrapper with the
                 filename on the left and a borderless chip-style 'Choose
                 File' button on the right. h-[38px] locks the field
                 height to match sibling inputs/selects (.input renders
                 at ~38–39px; without an explicit height, the chip's
                 padding pushes this control taller than its siblings).
                 !py-0 lets the chip vertically center inside the fixed
                 height; !pr-1 trims right padding so the chip's edge
                 lines up cleanly with the field's border. -->
            <label class="csv-file-picker input flex items-center gap-2 w-full cursor-pointer !py-0 !pr-1 hover:border-gray-400 transition-colors bg-white">
              <span class="flex-1 truncate text-sm"
                    :class="importFile ? 'text-gray-900 font-medium' : 'text-gray-500'">
                {{ importFile?.name || 'No file chosen' }}
              </span>
              <span class="px-3 py-1 rounded-md bg-blue-50 text-blue-700 text-xs font-medium hover:bg-blue-100 whitespace-nowrap">
                Choose File
              </span>
              <input type="file" accept=".csv,text/csv" @change="onFileChange" aria-label="CSV File" class="sr-only" />
            </label>
          </div>
        </div>

        <!-- Template-driven read-only fields. 12-col grid:
             Object Class (3) + RDN Attribute (2) + Other Attributes (4)
             + Conflict Handling (3) = 12. -->
        <div v-if="selectedTemplate" class="grid grid-cols-12 gap-2">
          <div class="col-span-3">
            <label class="block text-sm font-medium text-gray-700 mb-1">Object Class</label>
            <div class="input w-full bg-gray-50 text-gray-500 min-h-[38px]">
              <span v-if="!selectedTemplate.objectClass">—</span>
              <span v-else class="flex flex-wrap gap-1">
                <span v-for="oc in selectedTemplate.objectClass.split(',')" :key="oc"
                  class="inline-block bg-blue-100 text-blue-700 text-xs px-1.5 py-0.5 rounded">{{ oc }}</span>
              </span>
            </div>
          </div>
          <div class="col-span-2">
            <label for="bulk-template-rdn-attribute" class="block text-sm font-medium text-gray-700 mb-1">RDN Attribute</label>
            <input id="bulk-template-rdn-attribute" :value="selectedTemplate.targetKeyAttribute" disabled class="input w-full bg-gray-50 text-gray-500" />
          </div>
          <div class="col-span-4">
            <label for="bulk-template-other-attributes" class="block text-sm font-medium text-gray-700 mb-1">Other Attributes</label>
            <input id="bulk-template-other-attributes" :value="otherTemplateAttrs || '—'" disabled
              :title="otherTemplateAttrs"
              class="input w-full bg-gray-50 text-gray-500" />
          </div>
          <div class="col-span-3">
            <label for="bulk-template-conflict-handling" class="block text-sm font-medium text-gray-700 mb-1">Conflict Handling</label>
            <input id="bulk-template-conflict-handling" :value="conflictLabel(selectedTemplate.conflictHandling)" disabled class="input w-full bg-gray-50 text-gray-500" />
          </div>
        </div>

        <p v-if="selectedTemplate && selectedTemplate.dnSourceColumn" class="text-xs text-gray-600 -mt-1">
          DN is read from CSV column
          <span class="font-mono text-gray-800">{{ selectedTemplate.dnSourceColumn }}</span>
          (must fall within the parent container).
        </p>

        <button @click="doPreview" :disabled="!canImport || previewing" class="btn-primary">
          {{ previewing ? 'Loading preview…' : 'Preview Import' }}
        </button>
      </div>

      <!-- Preview section -->
      <div v-if="previewResult" class="mt-4">
        <div class="p-4 rounded-lg bg-blue-50 border border-blue-200 text-sm mb-3">
          <p class="font-medium text-blue-800 mb-2">Preview: {{ previewResult.totalRows }} rows to import</p>
          <!-- Summary banner: surface schema validation issues (rows missing
               required attribute values) up-front so the user doesn't have
               to scan the table to spot them. The import isn't blocked —
               those rows would still error during the LDAP add — but
               warning at preview time saves a confirm-then-fail round-trip. -->
          <p v-if="previewWarningCount && importBlocked" class="text-xs text-red-700 bg-red-50 border border-red-200 rounded px-2 py-1 mb-2">
            Import blocked: {{ previewWarningCount }} {{ previewWarningCount === 1 ? 'row is' : 'rows are' }}
            missing required attributes. This template is set to block the import until every row is valid —
            fix the rows (or switch the template to “Skip rows with errors”) and preview again.
          </p>
          <p v-else-if="previewWarningCount" class="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1 mb-2">
            {{ previewWarningCount }} {{ previewWarningCount === 1 ? 'row is' : 'rows are' }}
            missing required attributes — they will fail at import.
          </p>
          <div class="max-h-[60vh] overflow-auto">
            <table class="w-full text-xs">
              <thead class="bg-blue-100 sticky top-0">
                <tr>
                  <th class="px-2 py-1 text-left font-medium text-blue-700">#</th>
                  <th class="px-2 py-1 text-left font-medium text-blue-700">DN</th>
                  <th v-for="attr in userPreviewAttrCols" :key="attr"
                      class="px-2 py-1 text-left font-medium text-blue-700 whitespace-nowrap">{{ attr }}</th>
                  <th class="px-2 py-1 text-left font-medium text-blue-700">Issues</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-blue-100">
                <tr v-for="row in previewResult.rows" :key="row.rowNumber"
                    :class="row.missingRequired?.length ? 'bg-amber-50' : ''">
                  <td class="px-2 py-1 text-gray-600">{{ row.rowNumber }}</td>
                  <td class="px-2 py-1 font-mono text-[13px] text-gray-800">{{ row.computedDn || '(no DN)' }}</td>
                  <td v-for="attr in userPreviewAttrCols" :key="attr"
                      class="px-2 py-1 font-mono text-[13px] text-gray-700 break-all">{{ row.attributes?.[attr] ?? '' }}</td>
                  <td class="px-2 py-1 text-amber-700">
                    <span v-if="row.missingRequired?.length"
                          :title="`Missing required: ${row.missingRequired.join(', ')}`">
                      missing: {{ row.missingRequired.join(', ') }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <!-- Typed confirmation: the operator types the active profile's name
               to arm the import (the explicit "which environment" gate). -->
          <p class="text-sm text-gray-700 mt-3 mb-1">
            Type the profile name
            <code class="font-mono bg-gray-100 px-1 rounded">{{ activeProfile?.name }}</code>
            to confirm this import.
          </p>
          <div class="flex gap-2">
            <input v-model="importConfirmText" class="input w-56"
                   :placeholder="activeProfile?.name || ''"
                   aria-label="Type the profile name to confirm" />
            <button @click="doConfirmImport" :disabled="importing || importBlocked || !importArmed" class="btn-primary"
                    :title="importBlocked ? 'Resolve the errored rows before importing (template blocks on errors)' : ''">
              {{ importing ? 'Importing…' : 'Perform Import' }}
            </button>
            <button @click="previewResult = null" class="btn-neutral">Cancel</button>
          </div>
        </div>
      </div>

      <!-- Import result -->
      <div v-if="importResult" class="mt-4 p-4 rounded-lg bg-gray-50 border border-gray-200 text-sm">
        <div class="grid grid-cols-4 gap-2 mb-3">
          <div class="text-center"><p class="text-2xl font-bold text-green-600">{{ importResult.created }}</p><p class="text-xs text-gray-500">Created</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-blue-600">{{ importResult.updated }}</p><p class="text-xs text-gray-500">Updated</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-yellow-600">{{ importResult.skipped }}</p><p class="text-xs text-gray-500">Skipped</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-red-600">{{ importResult.errors }}</p><p class="text-xs text-gray-500">Errors</p></div>
        </div>
        <ul v-if="importResult.rows?.filter(r => r.status === 'ERROR').length" class="space-y-1">
          <li v-for="r in importResult.rows.filter(r => r.status === 'ERROR')" :key="r.rowNumber" class="text-red-600 text-xs">
            Row {{ r.rowNumber }}: {{ r.message }}
          </li>
        </ul>
      </div>
    </section>

    <!-- Export tab — Users -->
    <section v-if="activeTab === 'export' && entityType === 'users'" class="bg-white border border-gray-200 border-t-0 rounded-b-xl p-6">
      <h2 class="text-lg font-semibold mb-3">Export Users to CSV</h2>
      <div class="space-y-2">
        <!-- 12-column grid: LDAP Filter (4) + Base DN (4) + Attributes (4). -->
        <div class="grid grid-cols-12 gap-2 items-end">
          <FormField class="col-span-4 !mb-0" label="LDAP Filter (optional)" v-model="exportForm.filter" placeholder="(objectClass=inetOrgPerson)" />
          <div class="col-span-4">
            <label class="block text-sm font-medium text-gray-700 mb-1">Base DN (optional)</label>
            <DnPicker v-model="exportForm.baseDn" :directoryId="dirId" :superadmin="false" placeholder="dc=example,dc=com" />
          </div>
          <FormField class="col-span-4 !mb-0" label="Attributes (comma-separated)" v-model="exportForm.attributes" placeholder="cn,mail,uid,sn" />
        </div>
        <button @click="doExport" :disabled="exporting" class="btn-primary">
          {{ exporting ? 'Exporting…' : 'Download CSV' }}
        </button>
      </div>
    </section>

    <!-- Delete tab — Users -->
    <BulkDeleteSection
      v-if="activeTab === 'delete' && entityType === 'users' && canBulkDelete"
      :dir-id="dirId"
      :active-profile="activeProfile"
    />

    <!-- Import tab — Groups -->
    <section v-if="activeTab === 'import' && entityType === 'groups'" class="bg-white border border-gray-200 border-t-0 rounded-b-xl p-6">
      <h2 class="text-lg font-semibold mb-3">Import Groups from CSV</h2>
      <div class="space-y-2">
        <!-- 12-column grid: Parent DN (3) + Object Class (3)
             + Conflict Handling (3) + CSV File (3) = 12.
             Member Attribute is fully derived from Object Class
             (groupOfNames → member, groupOfUniqueNames → uniqueMember,
             posixGroup → memberUid) so it's resolved silently in the
             groupMemberAttr computed and doesn't need a UI field. -->
        <div class="grid grid-cols-12 gap-2 items-start">
          <ActiveProfileField class="col-span-3"
            :name="activeProfile?.name" :color="activeProfile?.themeColor" :dn="activeGroupTargetDn" />
          <div class="col-span-3">
            <label for="bulk-group-object-class" class="block text-sm font-medium text-gray-700 mb-1">Object Class</label>
            <select id="bulk-group-object-class" v-model="groupImportForm.objectClass" class="input w-full">
              <option value="groupOfNames">groupOfNames</option>
              <option value="groupOfUniqueNames">groupOfUniqueNames</option>
              <option value="posixGroup">posixGroup</option>
            </select>
          </div>
          <div class="col-span-3">
            <label for="bulk-group-conflict-handling" class="block text-sm font-medium text-gray-700 mb-1">Conflict Handling</label>
            <select id="bulk-group-conflict-handling" v-model="groupImportForm.conflictHandling" class="input w-full">
              <option value="SKIP">Skip existing</option>
              <option value="OVERWRITE">Overwrite existing</option>
            </select>
          </div>
          <div class="col-span-3">
            <label class="block text-sm font-medium text-gray-700 mb-1">CSV File <span class="text-red-500">*</span></label>
            <label class="csv-file-picker input flex items-center gap-2 w-full cursor-pointer !py-0 !pr-1 hover:border-gray-400 transition-colors bg-white">
              <span class="flex-1 truncate text-sm"
                    :class="groupImportFile ? 'text-gray-900 font-medium' : 'text-gray-500'">
                {{ groupImportFile?.name || 'No file chosen' }}
              </span>
              <span class="px-3 py-1 rounded-md bg-blue-50 text-blue-700 text-xs font-medium hover:bg-blue-100 whitespace-nowrap">
                Choose File
              </span>
              <input type="file" accept=".csv,text/csv" @change="onGroupFileChange" aria-label="CSV File" class="sr-only" />
            </label>
          </div>
        </div>
        <p class="text-xs text-gray-500">
          CSV columns: <code>cn</code> (required), <code>description</code>, <code>owner</code>, <code>members</code> (pipe-separated DNs).
          First row must be a header row.
        </p>
        <button @click="doGroupPreview" :disabled="!canGroupImport || groupPreviewing" class="btn-primary">
          {{ groupPreviewing ? 'Loading preview…' : 'Preview Import' }}
        </button>
      </div>

      <!-- Preview -->
      <div v-if="groupPreviewResult" class="mt-4">
        <div class="p-4 rounded-lg bg-blue-50 border border-blue-200 text-sm mb-3">
          <p class="font-medium text-blue-800 mb-2">Preview: {{ groupPreviewResult.totalRows }} groups to import</p>
          <p v-if="groupPreviewWarningCount" class="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1 mb-2">
            {{ groupPreviewWarningCount }} {{ groupPreviewWarningCount === 1 ? 'row is' : 'rows are' }}
            missing required attributes — they will fail at import.
          </p>
          <div class="max-h-[60vh] overflow-auto">
            <table class="w-full text-xs">
              <thead class="bg-blue-100 sticky top-0">
                <tr>
                  <th class="px-2 py-1 text-left font-medium text-blue-700">#</th>
                  <th class="px-2 py-1 text-left font-medium text-blue-700">DN</th>
                  <th class="px-2 py-1 text-left font-medium text-blue-700">Attributes</th>
                  <th class="px-2 py-1 text-left font-medium text-blue-700">Issues</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-blue-100">
                <tr v-for="row in groupPreviewResult.rows" :key="row.rowNumber"
                    :class="row.missingRequired?.length ? 'bg-amber-50' : ''">
                  <td class="px-2 py-1 text-gray-600">{{ row.rowNumber }}</td>
                  <td class="px-2 py-1 font-mono text-[13px] text-gray-800">{{ row.computedDn || '(no DN)' }}</td>
                  <td class="px-2 py-1 text-gray-600">{{ formatAttrs(row.attributes) }}</td>
                  <td class="px-2 py-1 text-amber-700">
                    <span v-if="row.missingRequired?.length"
                          :title="`Missing required: ${row.missingRequired.join(', ')}`">
                      missing: {{ row.missingRequired.join(', ') }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="text-sm text-gray-700 mt-3 mb-1">
            Type the profile name
            <code class="font-mono bg-gray-100 px-1 rounded">{{ activeProfile?.name }}</code>
            to confirm this import.
          </p>
          <div class="flex gap-2">
            <input v-model="groupImportConfirmText" class="input w-56"
                   :placeholder="activeProfile?.name || ''"
                   aria-label="Type the profile name to confirm" />
            <button @click="doGroupConfirmImport" :disabled="groupImporting || !groupImportArmed" class="btn-primary">
              {{ groupImporting ? 'Importing…' : 'Perform Import' }}
            </button>
            <button @click="groupPreviewResult = null" class="btn-neutral">Cancel</button>
          </div>
        </div>
      </div>

      <!-- Import result -->
      <div v-if="groupImportResult" class="mt-4 p-4 rounded-lg bg-gray-50 border border-gray-200 text-sm">
        <div class="grid grid-cols-4 gap-2 mb-3">
          <div class="text-center"><p class="text-2xl font-bold text-green-600">{{ groupImportResult.created }}</p><p class="text-xs text-gray-500">Created</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-blue-600">{{ groupImportResult.updated }}</p><p class="text-xs text-gray-500">Updated</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-yellow-600">{{ groupImportResult.skipped }}</p><p class="text-xs text-gray-500">Skipped</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-red-600">{{ groupImportResult.errors }}</p><p class="text-xs text-gray-500">Errors</p></div>
        </div>
        <ul v-if="groupImportResult.rows?.filter(r => r.status === 'ERROR').length" class="space-y-1">
          <li v-for="r in groupImportResult.rows.filter(r => r.status === 'ERROR')" :key="r.rowNumber" class="text-red-600 text-xs">
            Row {{ r.rowNumber }}: {{ r.message }}
          </li>
        </ul>
      </div>
    </section>

    <!-- Export tab — Groups -->
    <section v-if="activeTab === 'export' && entityType === 'groups'" class="bg-white border border-gray-200 border-t-0 rounded-b-xl p-6">
      <h2 class="text-lg font-semibold mb-3">Export Groups to CSV</h2>
      <div class="space-y-2">
        <!-- 12-column grid:
             LDAP Filter (3) + Base DN (3) + Attributes (4)
             + Member Attribute (2) = 12. -->
        <div class="grid grid-cols-12 gap-2 items-end">
          <FormField class="col-span-3 !mb-0" label="LDAP Filter (optional)" v-model="groupExportForm.filter" placeholder="(objectClass=groupOfNames)" />
          <div class="col-span-3">
            <label class="block text-sm font-medium text-gray-700 mb-1">Base DN (optional)</label>
            <DnPicker v-model="groupExportForm.baseDn" :directoryId="dirId" :superadmin="false" placeholder="ou=groups,dc=example,dc=com" />
          </div>
          <FormField class="col-span-4 !mb-0" label="Attributes (comma-separated)" v-model="groupExportForm.attributes" placeholder="cn,description,owner" />
          <div class="col-span-2">
            <label for="bulk-group-member-attribute" class="block text-sm font-medium text-gray-700 mb-1">Member Attribute</label>
            <select id="bulk-group-member-attribute" v-model="groupExportForm.memberAttribute" class="input w-full">
              <option value="member">member</option>
              <option value="uniqueMember">uniqueMember</option>
              <option value="memberUid">memberUid</option>
            </select>
          </div>
        </div>
        <button @click="doGroupExport" :disabled="groupExporting" class="btn-primary">
          {{ groupExporting ? 'Exporting…' : 'Download CSV' }}
        </button>
      </div>
    </section>

    <!-- Template create/edit modal -->
    <AppModal v-model="showTemplateModal" :title="editTemplate ? 'Edit Template' : 'New Template'" size="xl">
      <form @submit.prevent="saveTemplate" class="space-y-2 flex flex-col min-h-0 flex-1">
        <!-- Two-col grid: scalar fields on the left; the Object Class dual-list
             picker on the right. Lists use a fixed height (h-72) sized to about
             the left column so the panel reads balanced without dead space. -->
        <div class="grid grid-cols-2 gap-2 items-start shrink-0">
          <div class="space-y-2">
            <FormField label="Template Name" v-model="templateForm.name" required />
            <div>
              <label for="bulk-template-dn-source" class="block text-sm font-medium text-gray-700 mb-1">DN Source</label>
              <select id="bulk-template-dn-source" v-model="dnSourceMode" class="input w-full">
                <option value="rdn">Build DN from RDN + parent DN</option>
                <option value="column">Read DN from CSV column</option>
              </select>
            </div>
            <!-- One field in this slot, driven by the DN Source picker above. -->
            <FormField v-if="dnSourceMode === 'rdn'" label="RDN Attribute"
                       v-model="templateForm.targetKeyAttribute" placeholder="uid" />
            <FormField v-else label="DN column" v-model="templateForm.dnSourceColumn" placeholder="dn" />
            <div>
              <label for="bulk-template-form-conflict-handling" class="block text-sm font-medium text-gray-700 mb-1">Conflict Handling</label>
              <select id="bulk-template-form-conflict-handling" v-model="templateForm.conflictHandling" class="input w-full">
                <option value="SKIP">Skip existing</option>
                <option value="OVERWRITE">Overwrite existing</option>
                <option value="PROMPT">Prompt (treat as skip)</option>
              </select>
            </div>
            <div>
              <label for="bulk-template-form-error-handling" class="block text-sm font-medium text-gray-700 mb-1">On CSV Errors</label>
              <select id="bulk-template-form-error-handling" v-model="templateForm.errorHandling" class="input w-full">
                <option value="SKIP_ERRORS">Skip rows with errors</option>
                <option value="ABORT_ON_ERROR">Block import until errors are resolved</option>
              </select>
            </div>
            <label class="flex items-center gap-2 text-sm text-gray-700 cursor-pointer">
              <input type="checkbox" v-model="templateForm.skipHeaderRow" class="rounded text-blue-600" />
              CSV first row is header (skip on import)
            </label>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Object Class <span class="text-red-500">*</span></label>
            <div class="flex items-stretch gap-0">
              <!-- Selected list. h-72 sizes the lists to roughly the left
                   column's height so the panel has no large dead space; they
                   scroll internally (the Available list has many classes). -->
              <div class="flex-1 min-w-0">
                <div class="text-xs text-gray-500 mb-1">Selected</div>
                <div class="border border-gray-300 rounded-l-lg h-72 overflow-y-auto">
                  <div v-for="oc in templateForm.objectClasses" :key="oc"
                    @click="selectedOcHighlight = oc"
                    class="px-2 py-1 text-sm cursor-pointer truncate"
                    :class="selectedOcHighlight === oc ? 'bg-blue-100 text-blue-800' : 'hover:bg-gray-50'">
                    {{ oc }}
                  </div>
                  <p v-if="templateForm.objectClasses.length === 0" class="text-xs text-gray-500 text-center py-4">None</p>
                </div>
              </div>
              <!-- Add / Remove buttons -->
              <div class="flex flex-col items-center justify-center gap-1 px-2">
                <button type="button" @click="addObjectClass" :disabled="!availableOcHighlight"
                  class="w-8 h-8 flex items-center justify-center rounded border border-gray-300 text-sm hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed">◀</button>
                <button type="button" @click="removeObjectClass" :disabled="!selectedOcHighlight"
                  class="w-8 h-8 flex items-center justify-center rounded border border-gray-300 text-sm hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed">▶</button>
              </div>
              <!-- Available list -->
              <div class="flex-1 min-w-0">
                <div class="text-xs text-gray-500 mb-1">Available</div>
                <div class="border border-gray-300 rounded-r-lg h-72 overflow-y-auto">
                  <div v-for="oc in availableObjectClasses" :key="oc"
                    @click="availableOcHighlight = oc"
                    class="px-2 py-1 text-sm cursor-pointer truncate"
                    :class="availableOcHighlight === oc ? 'bg-blue-100 text-blue-800' : 'hover:bg-gray-50'">
                    {{ oc }}
                  </div>
                  <p v-if="availableObjectClasses.length === 0" class="text-xs text-gray-500 text-center py-4">None</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="flex flex-col min-h-0 flex-1">
          <div class="flex items-center justify-between mb-2 shrink-0">
            <label class="text-sm font-medium text-gray-700">Column Mappings</label>
            <span v-if="loadingOcAttrs" class="text-xs text-gray-500">Loading attributes…</span>
          </div>
          <div v-if="templateForm.entries.length === 0 && !loadingOcAttrs" class="text-sm text-gray-500 text-center py-3">
            Select an object class to populate attribute mappings.
          </div>
          <div v-else class="space-y-2 min-h-0 flex-1 overflow-y-auto pr-2">
            <div v-for="(e, i) in templateForm.entries" :key="i" class="flex gap-2 items-center">
              <input v-model="e.csvColumn" placeholder="CSV column" :aria-label="`CSV column for ${e.ldapAttribute}`" class="input flex-1 text-xs" :class="{ 'border-red-300': e._required && !e.csvColumn }" />
              <span class="text-gray-500">→</span>
              <input :value="e.ldapAttribute" disabled :aria-label="`LDAP attribute ${e.ldapAttribute}`" class="input flex-1 text-xs bg-gray-50 text-gray-500" />
              <div class="w-8 flex-shrink-0 flex justify-center">
                <span v-if="e._required" class="text-red-500 text-sm font-bold">*</span>
                <button v-else type="button" @click="removeTemplateEntry(i)" aria-label="Remove mapping" class="text-red-400 hover:text-red-600 text-lg leading-none">&times;</button>
              </div>
            </div>
          </div>
        </div>

        <div class="flex justify-end gap-2 pt-2 shrink-0">
          <button type="button" @click="showTemplateModal = false" class="btn-neutral">Cancel</button>
          <button type="submit" :disabled="templateSaving || !canSaveTemplate" class="btn-primary">{{ templateSaving ? 'Saving…' : 'Save' }}</button>
        </div>
      </form>
    </AppModal>

    <ConfirmDialog
      v-if="deleteTemplateTarget"
      :model-value="true"
      :message="`Delete template '${deleteTemplateTarget.name}'?`"
      danger
      confirm-label="Delete"
      @confirm="doDeleteTemplate"
      @update:model-value="deleteTemplateTarget = null"
    />
  </PageContainer>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { useProfilePickerStore } from '@/stores/profilePicker'
import { listProfiles } from '@/api/profiles'
import PageContainer from '@/components/PageContainer.vue'
import ActiveProfileField from './ActiveProfileField.vue'
import {
  importCsv, exportCsv, previewCsv,
  listCsvTemplates, createCsvTemplate, updateCsvTemplate, deleteCsvTemplate,
  previewGroupCsv, importGroupCsv, exportGroupCsv,
  checkContainerExists, createContainer,
} from '@/api/csvTemplates'
import { listObjectClasses, getObjectClassesBulk } from '@/api/schema'
import { downloadBlob } from '@/composables/useApi'
import { useConfirm } from '@/composables/useConfirm'
import FormField from '@/components/FormField.vue'
import DnPicker from '@/components/DnPicker.vue'
import AppModal from '@/components/AppModal.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import BulkDeleteSection from './BulkDeleteSection.vue'

interface TemplateEntry {
  csvColumn: string
  ldapAttribute: string
  ignored: boolean
  _required?: boolean
}
interface CsvTemplate {
  id: string
  name: string
  objectClass?: string
  targetKeyAttribute: string
  conflictHandling: string
  // How the import reacts to rows with errors: SKIP_ERRORS / ABORT_ON_ERROR.
  errorHandling?: string
  skipHeaderRow?: boolean
  // When set, the DN is read from this CSV column instead of RDN + parent DN.
  dnSourceColumn?: string | null
  entries?: TemplateEntry[]
}
interface ExportForm { filter: string, baseDn: string, attributes: string }
interface GroupImportForm { objectClass: string, conflictHandling: string }
/** Provisioning profile as consumed by the import target + theme banner. */
interface ProfileLite {
  id: string
  name: string
  themeColor?: string | null
  targetUserDn?: string | null
  targetGroupDn?: string | null
  objectClassNames?: string[]
  rdnAttribute?: string
}
interface GroupExportForm { filter: string, baseDn: string, attributes: string, memberAttribute: string }
interface TemplateForm {
  name: string
  objectClasses: string[]
  targetKeyAttribute: string
  conflictHandling: string
  errorHandling: string
  skipHeaderRow: boolean
  // CSV column holding the full DN; '' means construct from RDN + parent DN.
  dnSourceColumn: string
  entries: TemplateEntry[]
}
interface PreviewRow {
  rowNumber: number
  computedDn?: string
  attributes?: Record<string, string>
  missingRequired?: string[]
}
interface PreviewResult { totalRows: number, rows: PreviewRow[] }
interface ImportRowResult { rowNumber: number, dn?: string, status: string, message?: string }
interface ImportResult {
  totalRows: number
  created: number
  updated: number
  skipped: number
  errors: number
  rows?: ImportRowResult[]
  approvalId?: string
}
type ApiError = { response?: { data?: { detail?: string }, status?: number }, message?: string }

/** Extracts a user-facing message from an axios-style error of unknown type. */
function errMsg(e: unknown): string {
  const err = e as ApiError
  return err.response?.data?.detail || err.message || 'Request failed'
}

const route = useRoute()
const notif = useNotificationStore()
const confirm = useConfirm()
const dirId = route.params.dirId as string

const entityType    = ref<'users' | 'groups'>('users')
const activeTab     = ref<'import' | 'export' | 'delete'>('import')
const importing     = ref(false)
const previewing    = ref(false)
const exporting     = ref(false)
const importFile    = ref<File | null>(null)
const importResult  = ref<ImportResult | null>(null)
const previewResult = ref<PreviewResult | null>(null)

const importConfirmText = ref('')
const exportForm = ref<ExportForm>({ filter: '', baseDn: '', attributes: 'cn,mail,uid' })

// ── Group bulk state ─────────────────────────────────────────────────────────
const groupImporting    = ref(false)
const groupPreviewing   = ref(false)
const groupExporting    = ref(false)
const groupImportFile   = ref<File | null>(null)
const groupImportResult = ref<ImportResult | null>(null)
const groupPreviewResult = ref<PreviewResult | null>(null)
const groupImportForm = ref<GroupImportForm>({
  objectClass: 'groupOfNames',
  conflictHandling: 'SKIP',
})
const groupImportConfirmText = ref('')
const groupExportForm = ref<GroupExportForm>({
  filter: '',
  baseDn: '',
  attributes: 'cn,description,owner',
  memberAttribute: 'member',
})

// ── Template actions dropdown ─────────────────────────────────────────────────

const showTemplateMenu = ref(false)
const menuRef = ref<HTMLElement | null>(null)

function onClickOutside(e: MouseEvent) {
  if (menuRef.value && !menuRef.value.contains(e.target as Node)) {
    showTemplateMenu.value = false
  }
}
onMounted(() => document.addEventListener('click', onClickOutside))
onBeforeUnmount(() => document.removeEventListener('click', onClickOutside))

// Authorized-OU restriction on the Parent DN pickers.
//
// Admins are restricted to importing under the target OUs of profiles
// they're authorized for. The backend enforces this on both the
// preview and the run paths via requireDnWithinScope, but the
// frontend DnPicker should match — exposing the full directory tree
// to an admin who can only act under one branch is confusing and
// surfaces OUs they aren't meant to see exist.
//
// Superadmin keeps the full tree (empty array → DnPicker falls back
// to its default "browse from directory base DN" behaviour).
const auth = useAuthStore()

// Bulk delete is users-only and gated on the bulk.delete feature. When the
// admin switches to Groups while on the Delete tab, fall back to Import so
// the panel never renders blank.
const canBulkDelete = computed(() => auth.hasFeature('bulk.delete'))
watch(entityType, v => {
  if (v === 'groups' && activeTab.value === 'delete') activeTab.value = 'import'
})

// Admin bulk operations are scoped by the sidebar profile picker — there is no
// separate target selector on these forms. We read the picked profile from the
// shared store and look up its full record (theme colour, target OUs) in the
// directory's profile list. The active profile's theme colour is shown as a
// band so the operator always sees which environment they're changing.
const profilePicker = useProfilePickerStore()
const profiles = ref<ProfileLite[]>([])
onMounted(async () => {
  try {
    const { data } = await listProfiles(dirId)
    profiles.value = data as ProfileLite[]
  } catch (e) {
    console.warn('Failed to load profiles for bulk operations:', e)
  }
})

const activeProfile = computed<ProfileLite | null>(() =>
  profiles.value.find(p => p.id === profilePicker.selectedId) ?? null,
)
const activeUserTargetDn = computed(() => activeProfile.value?.targetUserDn ?? null)
const activeGroupTargetDn = computed(() =>
  activeProfile.value?.targetGroupDn || activeProfile.value?.targetUserDn || null,
)

// An import is armed only once the operator types the active profile's name
// (case-insensitive) — the explicit "which environment" confirmation gate.
const importArmed = computed(() => {
  const name = activeProfile.value?.name?.trim().toLowerCase()
  return !!name && importConfirmText.value.trim().toLowerCase() === name
})
const groupImportArmed = computed(() => {
  const name = activeProfile.value?.name?.trim().toLowerCase()
  return !!name && groupImportConfirmText.value.trim().toLowerCase() === name
})

function menuAction(action: string) {
  showTemplateMenu.value = false
  if (action === 'add') openCreateTemplate()
  else if (action === 'edit' && selectedTemplate.value) openEditTemplate(selectedTemplate.value)
  else if (action === 'delete' && selectedTemplate.value) confirmDeleteTemplate(selectedTemplate.value)
}

// ── Templates ─────────────────────────────────────────────────────────────────

const templatesLoading    = ref(false)
const templates           = ref<CsvTemplate[]>([])
const selectedTemplateId  = ref('')
const showTemplateModal   = ref(false)
const editTemplate        = ref<CsvTemplate | null>(null)
const templateSaving      = ref(false)
const deleteTemplateTarget = ref<CsvTemplate | null>(null)
const templateForm = ref<TemplateForm>({
  name: '', objectClasses: [], targetKeyAttribute: 'uid', conflictHandling: 'SKIP',
  errorHandling: 'SKIP_ERRORS', skipHeaderRow: true, dnSourceColumn: '', entries: []
})
// Whether the template reads the DN from a CSV column (vs constructing it from
// the RDN attribute + parent DN). Kept separate so toggling off preserves the
// typed column name until save.
const dnFromColumn = ref(false)
// Picker proxy over dnFromColumn: 'rdn' builds the DN from RDN + parent DN,
// 'column' reads it from a CSV column. Backed by the existing boolean so
// save/load logic (and the dnSourceColumn payload) stays unchanged.
const dnSourceMode = computed<'rdn' | 'column'>({
  get: () => (dnFromColumn.value ? 'column' : 'rdn'),
  set: (v) => { dnFromColumn.value = v === 'column' },
})

// ObjectClass picker state
const objectClasses       = ref<string[]>([])
const loadingOcAttrs      = ref(false)
const selectedOcHighlight = ref<string | null>(null)
const availableOcHighlight = ref<string | null>(null)

const availableObjectClasses = computed(() =>
  objectClasses.value.filter(oc => !templateForm.value.objectClasses.includes(oc))
)

function addObjectClass() {
  if (!availableOcHighlight.value) return
  templateForm.value.objectClasses.push(availableOcHighlight.value)
  availableOcHighlight.value = null
  onObjectClassChange()
}

function removeObjectClass() {
  if (!selectedOcHighlight.value) return
  const idx = templateForm.value.objectClasses.indexOf(selectedOcHighlight.value)
  if (idx >= 0) templateForm.value.objectClasses.splice(idx, 1)
  selectedOcHighlight.value = null
  onObjectClassChange()
}

const selectedTemplate = computed(() => {
  if (!selectedTemplateId.value) return null
  return templates.value.find(t => t.id === selectedTemplateId.value) || null
})

/**
 * Comma-joined list of the template's mapped LDAP attributes minus the
 * RDN/key attribute (which already has its own field). The backend only
 * persists entries with a non-blank csvColumn, so the list is exactly
 * what the import will populate per row, in declaration order.
 */
const otherTemplateAttrs = computed(() => {
  const t = selectedTemplate.value
  if (!t) return ''
  const rdn = (t.targetKeyAttribute || '').toLowerCase()
  return (t.entries || [])
    .filter(e => e.ldapAttribute && e.ldapAttribute.toLowerCase() !== rdn)
    .map(e => e.ldapAttribute)
    .join(', ')
})

const canImport = computed(() => {
  return selectedTemplateId.value && importFile.value && !!activeProfile.value
})

/** Number of preview rows missing any required attribute — drives the
 *  amber "N rows will fail" banner above the preview table. */
const previewWarningCount = computed(() =>
  (previewResult.value?.rows || []).filter(r => r.missingRequired?.length).length
)

/** When the selected template blocks on errors (errorHandling = ABORT_ON_ERROR)
 *  and the preview surfaced any errored rows, the import is blocked client-side
 *  — the backend enforces the same rule authoritatively. */
const importBlocked = computed(() =>
  selectedTemplate.value?.errorHandling === 'ABORT_ON_ERROR' && previewWarningCount.value > 0
)

/** Ordered union of the non-DN attribute names across all preview rows, so the
 *  user-import preview renders each attribute in its own column (rather than a
 *  single comma-delimited cell). First-seen order keeps columns stable. */
const userPreviewAttrCols = computed<string[]>(() => {
  const cols: string[] = []
  const seen = new Set<string>()
  for (const row of previewResult.value?.rows || []) {
    for (const key of Object.keys(row.attributes || {})) {
      if (!seen.has(key)) { seen.add(key); cols.push(key) }
    }
  }
  return cols
})

/** Same idea, for the group import preview. */
const groupPreviewWarningCount = computed(() =>
  (groupPreviewResult.value?.rows || []).filter(r => r.missingRequired?.length).length
)

const canSaveTemplate = computed(() => {
  const f = templateForm.value
  if (!f.name || f.objectClasses.length === 0) return false
  return f.entries.filter(e => e._required).every(e => e.csvColumn && e.csvColumn.trim())
})

function conflictLabel(val: string) {
  const map: Record<string, string> = { SKIP: 'Skip existing', OVERWRITE: 'Overwrite existing', PROMPT: 'Prompt (treat as skip)' }
  return map[val] || val
}

function formatAttrs(attrs?: Record<string, string>) {
  if (!attrs) return ''
  return Object.entries(attrs).map(([k, v]) => `${k}=${v}`).join(', ')
}

async function loadTemplates() {
  templatesLoading.value = true
  try {
    const { data } = await listCsvTemplates(dirId)
    templates.value = data
  } catch (e) { console.warn('Failed to load CSV templates:', e) }
  finally { templatesLoading.value = false }
}

async function loadObjectClasses() {
  try {
    const { data } = await listObjectClasses(dirId)
    objectClasses.value = (data as Array<string | { name: string }>)
      .map(oc => typeof oc === 'string' ? oc : oc.name)
  } catch (e) { console.warn('Failed to load objectClasses:', e) }
}

onMounted(() => {
  loadTemplates()
  loadObjectClasses()
})

function onTemplateSelected() {
  previewResult.value = null
  importResult.value = null
}

function openCreateTemplate() {
  editTemplate.value = null
  selectedOcHighlight.value = null
  availableOcHighlight.value = null
  templateForm.value = {
    name: '', objectClasses: [], targetKeyAttribute: 'uid', conflictHandling: 'SKIP',
    errorHandling: 'SKIP_ERRORS', skipHeaderRow: true, dnSourceColumn: '', entries: []
  }
  dnFromColumn.value = false
  showTemplateModal.value = true
}

function openEditTemplate(t: CsvTemplate) {
  editTemplate.value = t
  selectedOcHighlight.value = null
  availableOcHighlight.value = null
  templateForm.value = {
    name: t.name,
    objectClasses: t.objectClass ? t.objectClass.split(',') : [],
    targetKeyAttribute: t.targetKeyAttribute,
    conflictHandling: t.conflictHandling,
    errorHandling: t.errorHandling ?? 'SKIP_ERRORS',
    skipHeaderRow: t.skipHeaderRow !== false,
    dnSourceColumn: t.dnSourceColumn ?? '',
    entries: (t.entries ?? []).map(e => ({ ...e, _required: false })),
  }
  dnFromColumn.value = !!t.dnSourceColumn
  showTemplateModal.value = true
}

async function onObjectClassChange() {
  const ocs = templateForm.value.objectClasses
  if (ocs.length === 0) {
    templateForm.value.entries = []
    return
  }
  loadingOcAttrs.value = true
  try {
    const { data } = await getObjectClassesBulk(dirId, ocs)
    // Preserve existing csvColumn values where the ldapAttribute still exists
    const existingMap: Record<string, string> = {}
    for (const e of templateForm.value.entries) {
      if (e.csvColumn) existingMap[e.ldapAttribute] = e.csvColumn
    }
    const entries: TemplateEntry[] = []
    for (const attr of (data.required || [])) {
      if (attr.toLowerCase() === 'objectclass') continue
      entries.push({ csvColumn: existingMap[attr] || '', ldapAttribute: attr, ignored: false, _required: true })
    }
    for (const attr of (data.optional || [])) {
      if (attr.toLowerCase() === 'objectclass') continue
      entries.push({ csvColumn: existingMap[attr] || '', ldapAttribute: attr, ignored: false, _required: false })
    }
    templateForm.value.entries = entries
  } catch (e) {
    notif.error('Failed to load objectClass attributes: ' + errMsg(e))
  } finally {
    loadingOcAttrs.value = false
  }
}

function removeTemplateEntry(i: number) { templateForm.value.entries.splice(i, 1) }

async function saveTemplate() {
  templateSaving.value = true
  try {
    const payload = {
      name: templateForm.value.name,
      objectClass: templateForm.value.objectClasses.join(','),
      targetKeyAttribute: templateForm.value.targetKeyAttribute,
      conflictHandling: templateForm.value.conflictHandling,
      errorHandling: templateForm.value.errorHandling,
      skipHeaderRow: templateForm.value.skipHeaderRow,
      dnSourceColumn: dnFromColumn.value
        ? (templateForm.value.dnSourceColumn.trim() || null)
        : null,
      entries: templateForm.value.entries
        .filter(e => e.csvColumn && e.csvColumn.trim())
        .map(e => ({ csvColumn: e.csvColumn, ldapAttribute: e.ldapAttribute, ignored: false })),
    }
    if (editTemplate.value) {
      await updateCsvTemplate(dirId, editTemplate.value.id, payload)
      notif.success('Template updated')
    } else {
      await createCsvTemplate(dirId, payload)
      notif.success('Template created')
    }
    showTemplateModal.value = false
    await loadTemplates()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    templateSaving.value = false
  }
}

function confirmDeleteTemplate(t: CsvTemplate | null) {
  if (!t) return
  deleteTemplateTarget.value = t
}

async function doDeleteTemplate() {
  const target = deleteTemplateTarget.value
  deleteTemplateTarget.value = null
  if (!target) return
  try {
    await deleteCsvTemplate(dirId, target.id)
    notif.success('Template deleted')
    if (selectedTemplateId.value === target.id) {
      selectedTemplateId.value = ''
    }
    await loadTemplates()
  } catch (e) {
    notif.error(errMsg(e))
  }
}

// ── Import ────────────────────────────────────────────────────────────────────

function onFileChange(e: Event) {
  importFile.value = (e.target as HTMLInputElement).files?.[0] ?? null
  previewResult.value = null
  importResult.value = null
}

function buildImportRequest() {
  const t = selectedTemplate.value!
  return {
    templateId: t.id,
    // The active (sidebar-selected) profile is authoritative for the target OU.
    profileId: activeProfile.value?.id,
    targetKeyAttribute: t.targetKeyAttribute,
    conflictHandling: t.conflictHandling,
    skipHeaderRow: t.skipHeaderRow !== false,
    // Without this the backend gets an empty mapping and falls through
    // to "CSV header IS the attribute name" passthrough — which means
    // a column named `username` mapped to `uid` never reaches the
    // attribute map as `uid`, and the RDN-presence check fails on
    // every row with "missing rdn attribute". Send the same shape as
    // saveTemplate's mappings: only the entries the operator filled in
    // (non-blank csvColumn) get sent; the rest are unmapped and the
    // backend's passthrough still handles them.
    columnMappings: (t.entries || [])
      .filter(e => e.csvColumn && e.csvColumn.trim())
      .map(e => ({
        csvColumn: e.csvColumn,
        ldapAttribute: e.ldapAttribute,
        ignored: false,
      })),
  }
}

async function doPreview() {
  if (!canImport.value) return
  previewing.value = true
  previewResult.value = null
  importResult.value = null
  importConfirmText.value = ''
  try {
    const { data } = await previewCsv(dirId, importFile.value, buildImportRequest())
    previewResult.value = data
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    previewing.value = false
  }
}

/**
 * Validates the parent DN exists before submitting an import. If absent
 * the user is asked once whether to create it as an organizationalUnit;
 * on confirm we create the container and resolve true. Returns false if
 * the user declined. Network/LDAP errors propagate so the caller can
 * surface them.
 *
 * Tolerant of backend version skew: if the existence-check endpoint
 * returns 404 (deployed backend predates this feature), fall through
 * and let the import proceed. Worst case the import surfaces row-level
 * NO_SUCH_OBJECT errors — i.e. the pre-feature behaviour — rather than
 * silently blocking with a user-confusing "no static resource" message.
 */
async function ensureParentDnExists(parentDn: string) {
  let data
  try {
    const resp = await checkContainerExists(dirId, parentDn)
    data = resp.data
  } catch (e) {
    if ((e as ApiError).response?.status === 404) return true
    throw e
  }
  if (data.exists) return true
  const ok = await confirm({
    title: 'Parent DN does not exist',
    message: `The parent DN "${parentDn}" doesn't exist yet. Create it as an organizationalUnit and proceed with the import?`,
    confirmLabel: 'Create and import',
  })
  if (!ok) return false
  await createContainer(dirId, parentDn)
  notif.success(`Created parent container ${parentDn}`)
  return true
}

async function doConfirmImport() {
  if (!canImport.value || !importArmed.value) return
  const parentDn = activeUserTargetDn.value
  if (!parentDn) return
  try {
    if (!(await ensureParentDnExists(parentDn))) return
  } catch (e) {
    notif.error(errMsg(e))
    return
  }
  importing.value = true
  importResult.value = null
  try {
    const resp = await importCsv(dirId, importFile.value, buildImportRequest())
    const data = resp.data
    if (resp.status === 202 || data.approvalId) {
      // Import submitted for approval
      previewResult.value = null
      notif.success('Bulk import submitted for approval. An approver will review your request.')
    } else {
      importResult.value = data
      previewResult.value = null
      notif.success(`Import done: ${data.created} created, ${data.errors} errors`)
    }
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    importing.value = false
  }
}

// ── Export ────────────────────────────────────────────────────────────────────

async function doExport() {
  exporting.value = true
  try {
    const params = {
      filter:     exportForm.value.filter     || undefined,
      baseDn:     exportForm.value.baseDn     || undefined,
      attributes: exportForm.value.attributes || undefined,
    }
    const { data } = await exportCsv(dirId, params)
    downloadBlob(data, 'users.csv')
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    exporting.value = false
  }
}

// ── Group import / export ────────────────────────────────────────────────────

const MEMBER_ATTR_MAP: Record<string, string> = {
  groupOfNames: 'member',
  groupOfUniqueNames: 'uniqueMember',
  posixGroup: 'memberUid',
}

const groupMemberAttr = computed(() =>
  MEMBER_ATTR_MAP[groupImportForm.value.objectClass] || 'member'
)

const canGroupImport = computed(() =>
  groupImportFile.value && !!activeProfile.value
)

function onGroupFileChange(e: Event) {
  groupImportFile.value = (e.target as HTMLInputElement).files?.[0] ?? null
  groupPreviewResult.value = null
  groupImportResult.value = null
}

function buildGroupImportRequest() {
  return {
    // The active profile supplies the target group container (its targetGroupDn).
    profileId: activeProfile.value?.id,
    conflictHandling: groupImportForm.value.conflictHandling,
    skipHeaderRow: true,
    columnMappings: [],
  }
}

async function doGroupPreview() {
  if (!canGroupImport.value) return
  groupPreviewing.value = true
  groupPreviewResult.value = null
  groupImportResult.value = null
  groupImportConfirmText.value = ''
  try {
    const { data } = await previewGroupCsv(
      dirId, groupImportFile.value, buildGroupImportRequest(),
      groupMemberAttr.value, groupImportForm.value.objectClass)
    groupPreviewResult.value = data
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    groupPreviewing.value = false
  }
}

async function doGroupConfirmImport() {
  if (!canGroupImport.value || !groupImportArmed.value) return
  const parentDn = activeGroupTargetDn.value
  if (!parentDn) return
  try {
    if (!(await ensureParentDnExists(parentDn))) return
  } catch (e) {
    notif.error(errMsg(e))
    return
  }
  groupImporting.value = true
  groupImportResult.value = null
  try {
    const req = buildGroupImportRequest()
    const resp = await importGroupCsv(
      dirId, groupImportFile.value, req,
      groupMemberAttr.value, groupImportForm.value.objectClass
    )
    groupImportResult.value = resp.data
    groupPreviewResult.value = null
    notif.success(`Import done: ${resp.data.created} created, ${resp.data.errors} errors`)
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    groupImporting.value = false
  }
}

async function doGroupExport() {
  groupExporting.value = true
  try {
    const params = {
      filter:          groupExportForm.value.filter     || undefined,
      baseDn:          groupExportForm.value.baseDn     || undefined,
      attributes:      groupExportForm.value.attributes || undefined,
      memberAttribute: groupExportForm.value.memberAttribute,
    }
    const { data } = await exportGroupCsv(dirId, params)
    downloadBlob(data, 'groups.csv')
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    groupExporting.value = false
  }
}
</script>

<style scoped>
@reference "tailwindcss";

/* Lock the custom CSV file picker's height to match neighbouring .input
   fields/selects. The standard density renders .input at ~38px; the
   compact density (set via data-density on <html> by useDensity) shrinks
   .input to ~30px. The picker can't rely on .input's own height because
   the chip-style 'Choose File' span and !py-0 override decouple its
   intrinsic height from .input's padding. Without an explicit value the
   picker either matches the chip's content height (too short) or grows
   to whatever items it contains (too tall in compact mode). */
.csv-file-picker {
  height: 38px;
}
[data-density="compact"] .csv-file-picker {
  height: 30px;
}
</style>
