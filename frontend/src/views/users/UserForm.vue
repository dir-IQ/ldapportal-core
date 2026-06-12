<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div ref="formRootEl">
    <!-- Validation summary — surfaced at the top so an error on a field that's
         scrolled out of view is never missed. -->
    <FormValidationSummary v-if="showSummary" :errors="validationErrors" />

    <!-- Identity header — only meaningful in edit mode (create mode
         has no DN yet). Sits above the tab strip so it stays visible
         across Attributes / Groups / IVIA tabs. -->
    <UserIdentityHeader
      v-if="isEdit && local.dn"
      :dn="local.dn"
      :attributes="local.attributes || {}"
      :profile-name="userTemplateConfig?.name ?? null"
      :enabled="headerEnabled"
      :ivia="iviaStatus"
    />

    <!-- Tabs (shown in both create and edit modes) -->
    <div class="flex border-b border-gray-200 mb-4 gap-1">
      <button
        @click="activeTab = 'attributes'"
        class="px-5 py-3 text-base font-semibold border-b-[3px] -mb-px transition-colors"
        :class="activeTab === 'attributes' ? 'border-blue-600 text-blue-700' : 'border-transparent text-gray-500 hover:text-gray-800'"
      >Attributes</button>
      <button
        @click="activeTab = 'groups'"
        class="px-5 py-3 text-base font-semibold border-b-[3px] -mb-px transition-colors"
        :class="activeTab === 'groups' ? 'border-blue-600 text-blue-700' : 'border-transparent text-gray-500 hover:text-gray-800'"
      >Groups</button>
      <!-- IVIA tab — visible only in edit mode, when the addon is on
           the build, and when this directory has IVIA enabled. The
           per-directory check happens on the parent so the tab itself
           is hidden when not applicable (instead of flashing 'Loading'
           then disappearing). -->
      <button
        v-if="isEdit && iviaTabVisible"
        @click="activeTab = 'ivia'"
        class="px-5 py-3 text-base font-semibold border-b-[3px] -mb-px transition-colors"
        :class="activeTab === 'ivia' ? 'border-blue-600 text-blue-700' : 'border-transparent text-gray-500 hover:text-gray-800'"
      >{{ IVIA_ABBR }} Account</button>
    </div>

    <!-- ═══ Attributes tab ═══ -->
    <div v-show="activeTab === 'attributes'">

      <!-- ── Create mode ── -->
      <div v-if="!isEdit" class="space-y-2">
        <!-- Fallback RDN + DN row when the profile has no attribute
             template (either no userTemplateConfig at all, or its
             attributeConfigs array is empty — both mean we have no
             dynamic fields to render). -->
        <div v-if="!userTemplateConfig?.attributeConfigs?.length" class="grid grid-cols-6 gap-2">
          <FormField label="RDN Attribute" v-model="local.rdnAttribute" placeholder="uid" required />
          <div class="col-span-4">
            <FormField
              label="DN"
              :model-value="effectiveDn"
              @update:model-value="onDnInput"
              placeholder="uid=jsmith,ou=people,dc=example,dc=com"
              required
              :error="dnError"
            />
            <button v-if="dnEdited" type="button" class="mt-1 text-xs text-blue-600 hover:underline" @click="resetDn">
              Reset to computed
            </button>
          </div>
        </div>

        <!-- RDN Value when using fallback (no dynamic attribute template) -->
        <FormField v-if="!userTemplateConfig?.attributeConfigs?.length" label="RDN Value" v-model="local.rdnValue" placeholder="jsmith" required />

        <!-- Dynamic fields from user form config (all attributes in layout order) -->
        <template v-if="userTemplateConfig?.attributeConfigs?.length">
          <!-- Standalone DN row when the RDN attribute is hidden (a computed
               RDN, e.g. cn derived from other fields). The usual DN field
               renders beside the RDN field, which doesn't exist here. -->
          <div v-if="rdnIsHidden && showDnField" class="grid grid-cols-6 gap-2">
            <div class="col-span-6">
              <FormField
                label="DN"
                :model-value="effectiveDn"
                @update:model-value="onDnInput"
                placeholder="uid=jsmith,ou=people,dc=example,dc=com"
                required
                :error="dnError"
              />
              <button v-if="dnEdited" type="button" class="mt-1 text-xs text-blue-600 hover:underline" @click="resetDn">
                Reset to computed
              </button>
            </div>
          </div>
          <template v-for="(section, sIdx) in createSections" :key="sIdx">
            <fieldset v-if="section.fields.length" class="space-y-2">
              <legend v-if="section.name" class="text-base font-semibold text-gray-900 pb-1.5 border-b-2 border-gray-200 w-full mb-3">{{ section.name }}</legend>
              <div class="grid grid-cols-6 gap-2">
                <template
                  v-for="attr in section.fields"
                  :key="attr.id || attr.attributeName"
                >
                  <!-- RDN field -->
                  <div v-if="attr.rdn" :style="{ gridColumn: showDnField ? 'span 2' : `span ${attr.columnSpan || 6}` }">
                    <FormField
                      :label="(attr.customLabel || attr.attributeName) + ' (RDN)'"
                      v-model="local.rdnValue"
                      :type="mapInputType(attr.inputType)"
                      required
                      :placeholder="attr.attributeName"
                      :field-key="attr.attributeName"
                      :error="fieldErrors[attr.attributeName]"
                    />
                  </div>
                  <!-- Editable DN (seeded from the computed default / template).
                       Position + width come from the designer-configured layout. -->
                  <div v-else-if="attr.isDn" :style="{ gridColumn: `span ${attr.columnSpan || 4}` }">
                    <FormField
                      label="DN"
                      :model-value="effectiveDn"
                      @update:model-value="onDnInput"
                      placeholder="uid=jsmith,ou=people,dc=example,dc=com"
                      required
                      :error="dnError"
                    />
                    <button v-if="dnEdited" type="button" class="mt-1 text-xs text-blue-600 hover:underline" @click="resetDn">
                      Reset to computed
                    </button>
                  </div>
                  <!-- Password field with generate/show/copy (create mode only).
                       Hidden entirely when the profile auto-generates it
                       server-side — see hidePasswordField. -->
                  <div
                    v-if="!attr.rdn && attr.inputType === 'PASSWORD' && !hidePasswordField"
                    :style="{ gridColumn: `span ${effectiveColumnSpan(attr)}` }"
                  >
                    <label :for="`uf-pw-${attr.attributeName}`" class="block text-sm font-medium text-gray-700 mb-1">
                      {{ attr.customLabel || attr.attributeName }}
                      <span v-if="attr.requiredOnCreate" class="text-red-500">*</span>
                    </label>
                    <div class="flex gap-1">
                      <div class="relative flex-1">
                        <input
                          :id="`uf-pw-${attr.attributeName}`"
                          :data-field="attr.attributeName"
                          :type="passwordVisible ? 'text' : 'password'"
                          :value="local.attributes[attr.attributeName]"
                          @input="local.attributes[attr.attributeName] = ($event.target as HTMLInputElement).value"
                          :required="attr.requiredOnCreate"
                          :disabled="!attr.editableOnCreate"
                          :aria-invalid="fieldErrors[attr.attributeName] ? 'true' : undefined"
                          class="block w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:bg-gray-100 pr-8"
                        />
                        <button v-if="local.attributes[attr.attributeName]" type="button"
                          class="absolute right-2 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
                          @mousedown.prevent="passwordVisible = true"
                          @mouseup.prevent="passwordVisible = false"
                          @mouseleave="passwordVisible = false"
                          @touchstart.prevent="passwordVisible = true"
                          @touchend.prevent="passwordVisible = false"
                          title="Hold to show password">
                          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path v-if="!passwordVisible" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                            <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
                          </svg>
                        </button>
                      </div>
                      <button v-if="profileId" type="button" @click="doGeneratePassword(attr.attributeName)"
                        :disabled="generatingPassword"
                        class="px-2 py-1 text-xs rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50 whitespace-nowrap"
                        title="Generate password">
                        <svg class="w-4 h-4 inline" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z" />
                        </svg>
                      </button>
                      <button v-if="local.attributes[attr.attributeName]" type="button"
                        @click="copyPassword(attr.attributeName)"
                        class="px-2 py-1 text-xs rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 whitespace-nowrap"
                        title="Copy to clipboard">
                        <svg class="w-4 h-4 inline" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 5H6a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2v-1M8 5a2 2 0 002 2h2a2 2 0 002-2M8 5a2 2 0 012-2h2a2 2 0 012 2m0 0h2a2 2 0 012 2v3m2 4H10m0 0l3-3m-3 3l3 3" />
                        </svg>
                      </button>
                    </div>
                    <p v-if="fieldErrors[attr.attributeName]" class="mt-1 text-xs text-red-500">{{ fieldErrors[attr.attributeName] }}</p>
                  </div>
                  <!-- Regular field. Excludes PASSWORD so a hidden (auto-
                       generated) password renders nothing rather than falling
                       through to a plain text input. -->
                  <div
                    v-else-if="!attr.rdn && !attr.isDn && attr.inputType !== 'PASSWORD'"
                    :style="{ gridColumn: `span ${effectiveColumnSpan(attr)}` }"
                  >
                    <!-- DN Lookup: use DnPicker instead of text input -->
                    <template v-if="attr.inputType === 'DN_LOOKUP'">
                      <label class="block text-sm font-medium text-gray-700 mb-1">{{ attr.customLabel || attr.attributeName }}</label>
                      <DnPicker
                        :model-value="local.attributes[attr.attributeName]"
                        @update:model-value="v => { local.attributes[attr.attributeName] = v }"
                        :directory-id="dirId ?? undefined"
                        :placeholder="'Select a DN'"
                        :superadmin="false"
                      />
                      <p v-if="fieldErrors[attr.attributeName]" class="mt-1 text-xs text-red-500">{{ fieldErrors[attr.attributeName] }}</p>
                    </template>
                    <FormField
                      v-else
                      :label="attr.customLabel || attr.attributeName"
                      :model-value="attr.computedExpression ? computedAttrValues[attr.attributeName] : local.attributes[attr.attributeName]"
                      @update:model-value="v => { if (!attr.computedExpression) local.attributes[attr.attributeName] = v }"
                      :type="mapInputType(attr.inputType)"
                      :options="attr.inputType === 'SELECT' ? parseOptions(attr.allowedValues) : undefined"
                      :required="attr.requiredOnCreate"
                      :disabled="!attr.editableOnCreate"
                      :rows="attr.inputType === 'TEXTAREA' || attr.inputType === 'MULTI_VALUE' ? 3 : undefined"
                      :hint="attr.inputType === 'MULTI_VALUE' ? 'One value per line' : undefined"
                      :field-key="attr.attributeName"
                      :error="fieldErrors[attr.attributeName]"
                    />
                  </div>
                </template>
              </div>
            </fieldset>
          </template>
        </template>

        <!-- Fallback: hardcoded inetOrgPerson minimum when no
             attribute template — either no config row or
             attributeConfigs empty. -->
        <template v-if="!userTemplateConfig?.attributeConfigs?.length">
          <FormField label="cn (Common Name)" v-model="local.attributes.cn" required />
          <FormField label="sn (Surname)" v-model="local.attributes.sn" />
          <FormField label="mail" v-model="local.attributes.mail" />
          <FormField label="userPassword" type="password" v-model="local.attributes.userPassword" />
        </template>
      </div>

      <!-- ── Edit mode ── -->
      <!-- DN appears in UserIdentityHeader above the tab strip — no
           need to repeat it here. -->
      <div v-else class="space-y-2">

        <!-- When user form config is available, render structured fields -->
        <template v-if="userTemplateConfig?.attributeConfigs?.length">
          <template v-for="(section, sIdx) in editSections" :key="sIdx">
            <fieldset v-if="section.fields.length" class="space-y-2">
              <legend v-if="section.name" class="text-base font-semibold text-gray-900 pb-1.5 border-b-2 border-gray-200 w-full mb-3">{{ section.name }}</legend>
              <div class="grid grid-cols-6 gap-2">
                <template
                  v-for="attr in section.fields"
                  :key="attr.id || attr.attributeName"
                >
                  <!-- RDN field in edit mode -->
                  <div v-if="attr.rdn" :style="{ gridColumn: showDnField ? 'span 2' : `span ${attr.columnSpan || 6}` }">
                    <FormField
                      :label="attr.customLabel || attr.attributeName"
                      v-model="local.attributes[attr.attributeName]"
                      :type="mapInputType(attr.inputType)"
                      :required="attr.requiredOnCreate"
                      disabled
                      :rows="attr.inputType === 'TEXTAREA' || attr.inputType === 'MULTI_VALUE' ? 3 : undefined"
                      :hint="attr.inputType === 'MULTI_VALUE' ? 'One value per line' : undefined"
                    />
                  </div>
                  <!-- DN field (shown after RDN when enabled, edit mode) -->
                  <div v-if="attr.rdn && showDnField" class="col-span-4">
                    <FormField
                      label="DN"
                      :model-value="local.dn"
                      disabled
                    />
                  </div>
                  <!-- Regular field -->
                  <div
                    v-if="!attr.rdn"
                    :style="{ gridColumn: `span ${effectiveColumnSpan(attr)}` }"
                  >
                    <!-- DN Lookup: use DnPicker instead of text input -->
                    <template v-if="attr.inputType === 'DN_LOOKUP'">
                      <label class="block text-sm font-medium text-gray-700 mb-1">{{ attr.customLabel || attr.attributeName }}</label>
                      <DnPicker
                        v-model="local.attributes[attr.attributeName]"
                        :directory-id="dirId ?? undefined"
                        :placeholder="'Select a DN'"
                        :superadmin="false"
                        :disabled="!attr.editableOnUpdate"
                      />
                      <p v-if="fieldErrors[attr.attributeName]" class="mt-1 text-xs text-red-500">{{ fieldErrors[attr.attributeName] }}</p>
                    </template>
                    <FormField
                      v-else
                      :label="attr.customLabel || attr.attributeName"
                      v-model="local.attributes[attr.attributeName]"
                      :type="mapInputType(attr.inputType)"
                      :options="attr.inputType === 'SELECT' ? parseOptions(attr.allowedValues) : undefined"
                      :required="attr.requiredOnCreate"
                      :disabled="!attr.editableOnUpdate"
                      :rows="attr.inputType === 'TEXTAREA' || attr.inputType === 'MULTI_VALUE' ? 3 : undefined"
                      :hint="attr.inputType === 'MULTI_VALUE' ? 'One value per line' : undefined"
                      :field-key="attr.attributeName"
                      :error="fieldErrors[attr.attributeName]"
                    />
                  </div>
                </template>
              </div>
            </fieldset>
          </template>

          <!-- Other attributes not in the form config -->
          <div v-if="Object.keys(extraEditAttributes).length">
            <button @click="showExtraAttrs = !showExtraAttrs"
                    class="flex items-center gap-1 text-xs font-medium text-gray-500 hover:text-gray-700 mt-2">
              <svg :class="['w-3 h-3 transition-transform', showExtraAttrs && 'rotate-90']"
                   viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.29a.75.75 0 111.04-1.08l4.5 4.25a.75.75 0 010 1.08l-4.5 4.25a.75.75 0 01-1.06-.02z" clip-rule="evenodd"/>
              </svg>
              Other Attributes ({{ Object.keys(extraEditAttributes).length }})
            </button>
            <div v-if="showExtraAttrs" class="space-y-2 mt-3 pl-3 border-l-2 border-gray-100">
              <template v-for="(_, key) in extraEditAttributes" :key="key">
                <FormField :label="key" v-model="local.attributes[key]" type="textarea" :rows="2" hint="One value per line" />
              </template>
            </div>
          </div>
        </template>

        <!-- Fallback: raw attribute editing when no form config -->
        <template v-else>
          <template v-for="(_, key) in editableAttributes" :key="key">
            <FormField :label="key" v-model="local.attributes[key]" type="textarea" :rows="2" hint="One value per line" />
          </template>
        </template>

        <!-- IVIA enrichment attributes: read-only here. They're merged from the
             paired secUser and managed via the actions on the IVIA Account tab,
             so they're shown disabled and never written back on save. -->
        <div v-if="Object.keys(iviaDisplayAttributes).length" class="pt-2">
          <p class="text-xs font-medium text-gray-500">
            {{ IVIA_ABBR }} attributes (read-only — manage on the {{ IVIA_ABBR }} Account tab)
          </p>
          <div class="space-y-2 mt-3 pl-3 border-l-2 border-gray-100">
            <template v-for="(val, key) in iviaDisplayAttributes" :key="key">
              <FormField :label="iviaAttrLabel(key)" :model-value="val" type="textarea" :rows="1" disabled />
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- ═══ Groups tab ═══ -->
    <div v-show="activeTab === 'groups'">
      <p v-if="!isEdit" class="text-xs text-gray-500 mb-3">Select groups for the new user. Memberships will be created after the user is saved.</p>

      <!-- Truncation notice: the directory has more groups than the load cap,
           so the membership list and copy-from-user may be incomplete. -->
      <div v-if="isEdit && groupsTruncated" class="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
        Showing the first {{ GROUP_LOAD_LIMIT.toLocaleString() }} groups — in a directory this large, the membership list and “copy from another user” may be incomplete. Use search to find specific groups.
      </div>

      <!-- Copy memberships from another user (edit mode). Pre-stages the
           source user's groups as additions; applied with the rest on Save. -->
      <div v-if="isEdit" class="mb-4 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2">
        <button type="button" @click="showCopyFrom = !showCopyFrom"
                class="flex items-center gap-1.5 text-sm font-medium text-blue-600 hover:text-blue-800">
          <svg :class="['w-3.5 h-3.5 transition-transform', showCopyFrom && 'rotate-90']" viewBox="0 0 20 20" fill="currentColor">
            <path fill-rule="evenodd" d="M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.29a.75.75 0 111.04-1.08l4.5 4.25a.75.75 0 010 1.08l-4.5 4.25a.75.75 0 01-1.06-.02z" clip-rule="evenodd"/>
          </svg>
          Copy groups from another user
        </button>
        <div v-if="showCopyFrom" class="mt-3 space-y-2">
          <p class="text-xs text-gray-500">Stage this user to join every group the selected user belongs to. Groups already shared are skipped; nothing is written until you Save.</p>
          <div class="flex gap-2">
            <div class="flex-1 min-w-0">
              <DnPicker
                v-model="copySourceDn"
                :directory-id="dirId ?? undefined"
                :superadmin="false"
                placeholder="Select or paste a user DN"
                title="Select source user"
              />
            </div>
            <button type="button" @click="copyFromSelectedUser"
                    :disabled="!copySourceDn.trim()"
                    class="btn-primary text-xs shrink-0">Copy</button>
          </div>
        </div>
      </div>

      <!-- Two-column layout: left = current/pending memberships,
           right = search + add. Stacks vertically on narrow screens.
           Identity DN appears in the header above the tab strip — no
           need to repeat it inside the tab content. -->
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">

        <!-- LEFT: existing memberships -->
        <div>
          <!-- Current + staged memberships (edit mode only). Changes are
               staged locally and applied as one batch on Save. -->
          <div v-if="isEdit">
            <div class="flex items-center justify-between mb-2 gap-2">
              <h3 class="text-sm font-semibold text-gray-800">Groups</h3>
              <span v-if="hasPendingMembershipChanges" class="text-xs font-medium text-amber-600 shrink-0">Unsaved — applied on Save</span>
            </div>
            <div v-if="loadingGroups" class="text-sm text-gray-500 py-3 text-center">Loading…</div>
            <ul v-else-if="displayedMemberships.length" class="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden">
              <li v-for="row in displayedMemberships" :key="row.group.dn"
                  class="flex items-center justify-between px-3 py-2 text-sm hover:bg-gray-50"
                  :class="row.state === 'removing' ? 'bg-red-50' : row.state === 'adding' ? 'bg-green-50' : ''">
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-2">
                    <span class="font-medium truncate"
                          :class="row.state === 'removing' ? 'line-through text-gray-400' : 'text-gray-800'">{{ row.group.cn }}</span>
                    <span v-if="row.state === 'adding'" class="badge-green">Adding</span>
                    <span v-else-if="row.state === 'removing'" class="badge-red">Removing</span>
                  </div>
                  <code class="text-xs text-gray-500 block truncate" :title="row.group.dn">{{ row.group.dn }}</code>
                </div>
                <button @click="onMembershipAction(row)"
                        class="ml-2 text-xs font-medium"
                        :class="row.state === 'member' ? 'text-red-500 hover:text-red-700' : 'text-gray-600 hover:text-gray-800'">
                  {{ row.state === 'member' ? 'Remove' : 'Undo' }}
                </button>
              </li>
            </ul>
            <p v-else class="text-sm text-gray-500 py-3 text-center border border-gray-200 rounded-lg">Not a member of any groups</p>
          </div>

          <!-- Pending groups (create mode only) -->
          <div v-if="!isEdit">
            <h3 class="text-sm font-semibold text-gray-800 mb-2">Groups to Join</h3>
            <ul v-if="pendingGroups.length" class="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden">
              <li v-for="g in pendingGroups" :key="g.dn" class="flex items-center justify-between px-3 py-2 text-sm hover:bg-gray-50">
                <div class="min-w-0 flex-1">
                  <div class="font-medium text-gray-800 truncate">{{ g.cn }}</div>
                  <code class="text-xs text-gray-500 block truncate" :title="g.dn">{{ g.dn }}</code>
                </div>
                <button @click="removePendingGroup(g)" class="ml-2 text-red-500 hover:text-red-700 text-xs font-medium">Remove</button>
              </li>
            </ul>
            <p v-else class="text-sm text-gray-500 py-3 text-center border border-gray-200 rounded-lg">No groups selected yet — pick from the right.</p>
          </div>
        </div>

        <!-- RIGHT: add to group -->
        <div>
          <h3 class="text-sm font-semibold text-gray-800 mb-2">Add to Group</h3>
          <div class="flex gap-2 mb-2">
            <input
              v-model="groupFilter"
              placeholder="Search groups…"
              aria-label="Search groups"
              @keyup.enter="searchAvailableGroups"
              class="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <button @click="searchAvailableGroups" class="btn-primary text-xs">Search</button>
          </div>
          <div v-if="loadingGroups" class="text-sm text-gray-500 py-3 text-center">Loading…</div>
          <p v-else-if="!groupFilter.trim() && availableGroups.length === 0" class="text-xs text-gray-500 py-3 text-center">Type a group name and click Search.</p>
          <ul v-else-if="availableGroups.length" class="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden max-h-72 overflow-y-auto">
            <li v-for="g in availableGroups" :key="g.dn" class="flex items-center justify-between px-3 py-2 text-sm hover:bg-gray-50">
              <div class="min-w-0 flex-1">
                <div class="font-medium text-gray-800 truncate">{{ g.cn }}</div>
                <code class="text-xs text-gray-500 block truncate" :title="g.dn">{{ g.dn }}</code>
              </div>
              <button @click="addToGroup(g)" class="ml-2 text-blue-600 hover:text-blue-800 text-xs font-medium">Add</button>
            </li>
          </ul>
          <p v-else class="text-xs text-gray-500 py-3 text-center">No matches.</p>
        </div>

      </div>
    </div>

    <!-- ═══ IVIA Account tab ═══ -->
    <div v-show="activeTab === 'ivia'" v-if="isEdit && iviaTabVisible">
      <IsvaAccountPanel
        :dir-id="dirId || ''"
        :dn="local.dn || ''"
        :ivia-config-enabled="iviaTabVisible"
        @status-changed="iviaStatus = $event"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch, nextTick, computed, onMounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import FormField from '@/components/FormField.vue'
import FormValidationSummary from '@/components/FormValidationSummary.vue'
import { useFormErrors } from '@/composables/useFormErrors'
import DnPicker from '@/components/DnPicker.vue'
import UserIdentityHeader from '@/components/users/UserIdentityHeader.vue'
import IsvaAccountPanel from '@/components/users/IsvaAccountPanel.vue'
import * as groupsApi from '@/api/groups'
import * as usersApi from '@/api/users'
import { generatePassword } from '@/api/profiles'
import { getIsvaConfig } from '@/api/isvaConfig'
import { IVIA_ABBR, isIviaAttr, iviaAttrLabel } from '@/constants/productNames'
import type { IsvaAccountStatus } from '@/api/isvaAccount'
import { validateAttributeValue, type AttributeRules } from '@/utils/attributeValidation'
import { useAttributeSyntaxStore } from '@/stores/attributeSyntax'

/** A single profile attribute config. Only `attributeName` is guaranteed;
 *  everything else is optional so a narrower caller shape stays assignable.
 *  `rdn` is augmented at runtime by the section builders. */
interface AttributeConfig {
  attributeName: string
  customLabel?: string | null
  inputType?: string | null
  requiredOnCreate?: boolean
  editableOnCreate?: boolean
  editableOnUpdate?: boolean
  hidden?: boolean
  minLength?: number | null
  maxLength?: number | null
  validationRegex?: string | null
  validationMessage?: string | null
  defaultValue?: string | null
  computedExpression?: string | null
  allowedValues?: string | null
  columnSpan?: number | null
  sectionName?: string | null
  id?: string | number | null
  rdn?: boolean
  /** Marks the synthetic DN field injected into the create layout. */
  isDn?: boolean
}

interface UserTemplateConfig {
  name?: string | null
  rdnAttribute?: string | null
  showDnField?: boolean
  /**
   * Optional ${attr} expression that seeds the (editable) DN field on create.
   * Blank falls back to "<rdnAttribute>=<rdnValue>,<parentDn>".
   */
  dnTemplate?: string | null
  /** DN field layout (designer-configured); null reproduces the default (after the RDN, 2/3). */
  dnColumnSpan?: number | null
  dnSectionName?: string | null
  dnDisplayOrder?: number | null
  objectClassNames?: string[]
  attributeConfigs?: AttributeConfig[]
  /**
   * How the profile sources the password. The GENERATED_* dispositions have the
   * server generate it at create time, so the password field is hidden from the
   * operator here.
   */
  passwordDisposition?: string | null
}

interface UserFormData {
  dn?: string
  parentDn?: string
  rdnAttribute?: string
  rdnValue?: string
  attributes?: Record<string, unknown>
  _pendingGroups?: Array<{ dn: string, memberAttr: string }>
  [key: string]: unknown
}

interface GroupItem {
  dn: string
  cn: string
  members: string[]
  memberAttr: 'member' | 'uniqueMember' | 'memberUid'
}

interface Section {
  name: string
  fields: AttributeConfig[]
}

const props = withDefaults(defineProps<{
  data: UserFormData
  isEdit?: boolean
  userTemplateConfig?: UserTemplateConfig | null
  dirId?: string | null
  profileId?: string | null
}>(), {
  isEdit: false,
  userTemplateConfig: null,
  dirId: null,
  profileId: null,
})
const emit = defineEmits<{ update: [data: UserFormData] }>()

const local = reactive({
  ...props.data,
  // Values reach UserForm already string-coerced (the parent joins LDAP
  // multi-values with newlines before binding); typing them as strings lets
  // the FormField v-model bindings type-check.
  attributes: { ...(props.data.attributes || {}) } as Record<string, string>,
})

// Ensure SELECT fields have their defaultValue applied even if emptyForm() missed them
if (!props.isEdit && props.userTemplateConfig?.attributeConfigs) {
  for (const attr of props.userTemplateConfig.attributeConfigs) {
    if (attr.inputType === 'SELECT' && attr.defaultValue && !local.attributes[attr.attributeName]) {
      local.attributes[attr.attributeName] = attr.defaultValue
    }
  }
}

// Password generate / show / copy state
const passwordVisible = ref(false)
const generatingPassword = ref(false)

async function doGeneratePassword(attrName: string) {
  if (!props.profileId) return
  generatingPassword.value = true
  try {
    const { data } = await generatePassword(props.profileId)
    local.attributes[attrName] = data.password
    passwordVisible.value = true
  } catch {
    useNotificationStore().error('Failed to generate password')
  } finally {
    generatingPassword.value = false
  }
}

function copyPassword(attrName: string) {
  const val = local.attributes[attrName]
  if (val) navigator.clipboard.writeText(val)
}

const activeTab = ref<'attributes' | 'groups' | 'ivia'>('attributes')

// ── IVIA tab gating + cached status snapshot ──────────────────────
// The tab button is hidden unless: edit mode + addon present + the
// directory has IVIA enabled. The per-directory check happens here
// so the tab strip doesn't show a phantom button that hides after
// the panel mounts. The panel itself also self-gates so it stays
// usable in non-UserForm contexts.
const auth = useAuthStore()
const syntaxStore = useAttributeSyntaxStore()
const iviaTabVisible = ref(false)
const iviaStatus     = ref<IsvaAccountStatus | null>(null)

async function checkIviaTabVisibility() {
  if (!props.isEdit || !props.dirId || !auth.isIsvaIntegrationEnabled) {
    iviaTabVisible.value = false
    if (activeTab.value === 'ivia') activeTab.value = 'attributes'
    return
  }
  try {
    const cfg = await getIsvaConfig(props.dirId)
    iviaTabVisible.value = cfg.data?.enabled === true
  } catch {
    // 404 (no config row) / network failure / 403 → hide.
    iviaTabVisible.value = false
  }
  // Whenever the tab becomes hidden, reset away from it — otherwise
  // the tab strip removes the button while activeTab='ivia' renders
  // a blank content area with no visible tab marked active.
  if (!iviaTabVisible.value && activeTab.value === 'ivia') {
    activeTab.value = 'attributes'
  }
}

// 'enabled' attribute, if the backend exposed it as a virtual on the
// LDAP entry (same shape as the user-list row uses). Null when absent
// so the header can hide the badge entirely.
const headerEnabled = computed(() => {
  const v = local.attributes?.enabled
  if (v === undefined || v === null || v === '') return null
  if (typeof v === 'boolean') return v
  if (Array.isArray(v)) return v.length ? v[0] !== 'false' && v[0] !== false : null
  return v !== 'false'
})

// Membership detection (Current Groups) and copy-from-user both derive from
// this loaded set, so load up to the backend's hard cap and slim the payload
// to just the membership-relevant attributes. When the cap is hit the view may
// be incomplete — surfaced via groupsTruncated.
const GROUP_LOAD_LIMIT = 2000
const GROUP_LOAD_ATTRS  = 'cn,member,uniqueMember,memberUid'

const loadingGroups   = ref(false)
const memberGroups    = ref<GroupItem[]>([])
const availableGroups = ref<GroupItem[]>([])
const groupFilter     = ref('')
const allGroups       = ref<GroupItem[]>([])
const pendingGroups   = ref<GroupItem[]>([])
const groupsTruncated = ref(false)

// Edit-mode staged membership changes — accumulated locally and flushed as a
// single batch on Save (via applyMembershipChanges, exposed to the parent),
// rather than one API call per click. stagedAdditions holds groups to join;
// stagedRemovals holds the lowercased DNs of current memberships to drop.
const stagedAdditions = ref<GroupItem[]>([])
const stagedRemovals  = ref<string[]>([])

/** A current membership's DN is staged for removal. */
function isStagedForRemoval(dn: string): boolean {
  return stagedRemovals.value.includes(dn.toLowerCase())
}

/**
 * The membership rows to render in edit mode: existing memberships (flagged
 * 'removing' when staged to drop) followed by staged additions ('adding').
 */
interface MembershipRow { group: GroupItem, state: 'member' | 'removing' | 'adding' }
const displayedMemberships = computed<MembershipRow[]>(() => {
  const rows: MembershipRow[] = memberGroups.value.map(g => ({
    group: g,
    state: isStagedForRemoval(g.dn) ? 'removing' : 'member',
  }))
  for (const g of stagedAdditions.value) rows.push({ group: g, state: 'adding' })
  return rows
})

const hasPendingMembershipChanges = computed(() =>
  stagedAdditions.value.length > 0 || stagedRemovals.value.length > 0)

// "Copy groups from another user" (edit mode): pick a source user and stage
// every group they belong to that this user isn't already in.
const showCopyFrom = ref(false)
const copySourceDn = ref('')

const showExtraAttrs = ref(false)

const HIDDEN_EDIT_ATTRS = new Set(['objectclass', 'objectClass', 'userpassword', 'userPassword', 'unicodePwd', 'unicodepwd'])

/** Attributes to show in edit mode (excludes objectClass and IVIA enrichment). */
const editableAttributes = computed<Record<string, string>>(() => {
  const result: Record<string, string> = {}
  for (const key of Object.keys(local.attributes)) {
    if (!HIDDEN_EDIT_ATTRS.has(key) && !isIviaAttr(key)) {
      result[key] = local.attributes[key]
    }
  }
  return result
})

/**
 * IVIA (isva.*) enrichment attributes, surfaced read-only. They're merged from
 * the paired secUser on read and managed exclusively through the IVIA Account
 * tab actions (grant / suspend / restore / …), never edited as LDAP attributes
 * here — so they render disabled and are excluded from the editable buckets.
 */
const iviaDisplayAttributes = computed<Record<string, string>>(() => {
  const result: Record<string, string> = {}
  for (const key of Object.keys(local.attributes)) {
    if (isIviaAttr(key)) result[key] = local.attributes[key]
  }
  return result
})

/** Attributes from the form config to show in edit mode (excludes objectClass, password, and hidden; includes RDN). */
const editFormAttributes = computed(() => {
  if (!props.userTemplateConfig?.attributeConfigs) return []
  const rdnName = props.userTemplateConfig.rdnAttribute
  return props.userTemplateConfig.attributeConfigs
    .filter(a => !a.hidden && !HIDDEN_EDIT_ATTRS.has(a.attributeName) && !HIDDEN_EDIT_ATTRS.has(a.attributeName.toLowerCase()))
    .map(a => ({ ...a, rdn: a.attributeName === rdnName }))
})

/** Attributes present on the entry but NOT in the form config (edit mode overflow). */
const extraEditAttributes = computed<Record<string, string>>(() => {
  if (!props.userTemplateConfig?.attributeConfigs) return {}
  const configuredNames = new Set(
    props.userTemplateConfig.attributeConfigs.map(a => a.attributeName.toLowerCase())
  )
  const result: Record<string, string> = {}
  for (const key of Object.keys(local.attributes)) {
    if (!HIDDEN_EDIT_ATTRS.has(key) && !isIviaAttr(key) && !configuredNames.has(key.toLowerCase())) {
      result[key] = local.attributes[key]
    }
  }
  return result
})

const INPUT_TYPE_MAP: Record<string, string> = {
  TEXT: 'text',
  TEXTAREA: 'textarea',
  PASSWORD: 'password',
  BOOLEAN: 'checkbox',
  DATE: 'date',
  DATETIME: 'datetime-local',
  MULTI_VALUE: 'textarea',
  DN_LOOKUP: 'text',
  DN: 'text',
  SELECT: 'select',
  HIDDEN_FIXED: 'hidden',
}

function mapInputType(inputType?: string | null): string {
  return INPUT_TYPE_MAP[inputType ?? ''] || 'text'
}

/**
 * Grid-column width for an attribute. Three layers:
 *
 *   1. Widgets that structurally need horizontal room — PASSWORD (show /
 *      generate / copy controls), TEXTAREA + MULTI_VALUE (multi-line),
 *      DN_LOOKUP (DN picker + browse button) — always span the full row
 *      regardless of profile config. The admin can't usefully override
 *      this; the widget would break at narrower widths.
 *
 *   2. Profile config — `attr.columnSpan` set on ProfileAttributeConfig.
 *      Admin's deliberate choice for this attribute on this profile.
 *
 *   3. Fallback to 3 (two-column row) when neither rule applies.
 */
const FULL_WIDTH_INPUT_TYPES = new Set(['PASSWORD', 'TEXTAREA', 'MULTI_VALUE', 'DN_LOOKUP'])
function effectiveColumnSpan(attr: AttributeConfig): number {
  if (attr.inputType && FULL_WIDTH_INPUT_TYPES.has(attr.inputType)) return 6
  return attr.columnSpan || 3
}

/** Parse the allowedValues JSON string into FormField options. */
function parseOptions(allowedValues?: string | null): Array<{ value: string, label: string }> {
  if (!allowedValues) return []
  try {
    const arr = JSON.parse(allowedValues)
    if (!Array.isArray(arr)) return []
    return arr.map((v: unknown) => ({ value: String(v), label: String(v) }))
  } catch {
    return allowedValues.split(',').map(v => ({ value: v.trim(), label: v.trim() }))
  }
}

/** The attribute marked as RDN in the user form config. */
const rdnAttr = computed<AttributeConfig | null>(() => {
  if (!props.userTemplateConfig?.attributeConfigs) return null
  const rdnName = props.userTemplateConfig.rdnAttribute
  return props.userTemplateConfig.attributeConfigs.find(a => a.attributeName === rdnName) || null
})

/**
 * RFC 4514 escaping for an attribute value placed inside a DN/RDN. Reserved
 * characters (notably '+', the multi-valued-RDN separator, and ',') in a value
 * must be backslash-escaped or they corrupt the DN. Applied to ${attr}
 * substitutions when composing the DN — never to the template's literal text,
 * which is DN structure.
 */
function escapeRdnValue(v: string): string {
  if (!v) return v
  let out = ''
  for (const ch of v) {
    if (ch === '\\' || ch === '"' || ch === '+' || ch === ',' ||
        ch === ';' || ch === '<' || ch === '>') {
      out += '\\' + ch
    } else {
      out += ch
    }
  }
  // A leading '#' or space, or a trailing space, must also be escaped.
  return out.replace(/^([ #])/, '\\$1').replace(/ $/, '\\ ')
}

/**
 * Resolve a {@code ${attr}} reference for DN composition. Unlike the raw
 * attribute lookup, this also returns the values of *computed* attributes
 * (whose values live in {@link computedAttrValues}, not local.attributes), so a
 * DN template referencing e.g. a computed {@code cn} reflects it and reacts to
 * the fields that feed it.
 */
function resolveDnVar(name: string): string {
  const cfg = props.userTemplateConfig?.attributeConfigs?.find(a => a.attributeName === name)
  // A computed attribute's value lives in computedAttrValues — even when it is
  // also the RDN (local.rdnValue stays empty for a computed RDN, since no RDN
  // input renders).
  if (cfg?.computedExpression) return computedAttrValues.value[name] || ''
  if (name === local.rdnAttribute) return local.rdnValue || ''
  return local.attributes[name] || ''
}

/**
 * Whether the RDN attribute is hidden from the create form (i.e. a computed
 * RDN). The DN field then renders standalone, and the RDN value used for DN
 * composition comes from the computed expression rather than a typed field.
 */
const rdnIsHidden = computed(() => {
  const cfg = props.userTemplateConfig?.attributeConfigs?.find(
    a => a.attributeName === props.userTemplateConfig?.rdnAttribute)
  return !!cfg?.hidden
})

/** The RDN value for DN composition: computed when the RDN attr is computed. */
const rdnEffectiveValue = computed(() => {
  const name = rdnAttr.value?.attributeName || local.rdnAttribute || ''
  const cfg = props.userTemplateConfig?.attributeConfigs?.find(a => a.attributeName === name)
  if (cfg?.computedExpression) return computedAttrValues.value[name] || ''
  return local.rdnValue || ''
})

/**
 * Evaluate a DN template: literal text is treated as DN structure (kept as-is),
 * and each {@code ${attr}} placeholder is replaced with the attribute's value,
 * RFC 4514-escaped. Note this is intentionally simpler than
 * {@link evaluateExpression} — a DN template has no '+'/quote operators, since
 * in a DN those characters are literal structure.
 */
function evaluateDnTemplate(tpl: string): string {
  let out = ''
  let i = 0
  while (i < tpl.length) {
    if (tpl[i] === '$' && tpl[i + 1] === '{') {
      const end = tpl.indexOf('}', i + 2)
      if (end === -1) { out += tpl.slice(i); break }
      out += escapeRdnValue(resolveDnVar(tpl.substring(i + 2, end)))
      i = end + 1
    } else {
      out += tpl[i]
      i++
    }
  }
  return out
}

/**
 * The computed default DN. When the profile defines a {@code dnTemplate} it is
 * evaluated via {@link evaluateDnTemplate}; otherwise the DN is composed from
 * the RDN attribute, RDN value, and parent DN. Substituted values are
 * RFC 4514-escaped either way.
 */
const defaultDn = computed(() => {
  const tpl = props.userTemplateConfig?.dnTemplate
  if (tpl && tpl.trim()) {
    const dn = evaluateDnTemplate(tpl).trim()
    if (dn) return dn
  }
  const attr = rdnAttr.value?.attributeName || local.rdnAttribute || ''
  const val = rdnEffectiveValue.value
  const base = local.parentDn || ''
  if (!attr || !val || !base) return ''
  return `${attr}=${escapeRdnValue(val)},${base}`
})

const dnEdited = ref(false)

/**
 * The DN shown and submitted on create: the computed {@link defaultDn} until the
 * admin overrides it by typing (after which their value, held in local.dn,
 * wins). This is a *pure computed* — there is deliberately no watcher pushing
 * defaultDn into local.dn — so the field always reflects the latest values of
 * the fields the template references (including computed attributes), with no
 * update race against the parent round-trip. Edit mode shows the immutable DN.
 */
const effectiveDn = computed(() => {
  if (props.isEdit) return local.dn || ''
  return dnEdited.value ? (local.dn || '') : defaultDn.value
})

/** Admin typed into the DN field — stop tracking the computed default. */
function onDnInput(v: string) {
  dnEdited.value = true
  local.dn = v
}

/** Discard a manual override and fall back to the computed default. */
function resetDn() {
  dnEdited.value = false
  local.dn = defaultDn.value
}

/**
 * Client-side mirror of the server's profile-DIT check: the DN must stay within
 * the profile's target OU (parentDn). Purely advisory — the server
 * (ProvisioningProfileService.requireDnWithinProfileDit) is authoritative.
 */
const dnError = computed(() => {
  if (props.isEdit || !showDnField.value) return ''
  const dn = effectiveDn.value.trim()
  const base = (local.parentDn || '').trim()
  if (!dn) return dnEdited.value ? 'DN is required' : ''
  if (base && !dnWithinBase(dn, base)) return `DN must be within ${base}`
  return ''
})

/** Whether to show the DN field alongside the RDN. */
const showDnField = computed(() => props.userTemplateConfig?.showDnField !== false)

/**
 * Hide the password field when the profile auto-generates it server-side
 * (GENERATED_DELIVERED / GENERATED_DISCARDED) — the operator never sees or
 * enters it. OPERATOR_ENTERED (or an unset disposition) keeps the field.
 */
const hidePasswordField = computed(() => {
  const d = props.userTemplateConfig?.passwordDisposition
  return d === 'GENERATED_DELIVERED' || d === 'GENERATED_DISCARDED'
})

/** All non-hidden attributes (including RDN), preserving the order defined in the user form config. */
const allVisibleAttributes = computed(() => {
  if (!props.userTemplateConfig?.attributeConfigs) return []
  const rdnName = props.userTemplateConfig.rdnAttribute
  const list: AttributeConfig[] = props.userTemplateConfig.attributeConfigs
    .filter(a => !a.hidden && a.attributeName.toLowerCase() !== 'objectclass')
    .map(a => ({ ...a, rdn: a.attributeName === rdnName }))
  // Skip injection when the RDN is hidden — a standalone full-width DN row
  // handles that (computed-RDN) case separately.
  if (showDnField.value && !rdnIsHidden.value) insertDnPseudoField(list)
  return list
})

/**
 * Inject the synthetic, editable DN field into the create layout at its
 * designer-configured section / position / width. Null layout values reproduce
 * the default — immediately after the RDN at 2/3 width.
 */
function insertDnPseudoField(list: AttributeConfig[]): void {
  const cfg = props.userTemplateConfig
  const rdnIdx = list.findIndex(f => f.rdn)
  const rdnSection = rdnIdx >= 0 ? (list[rdnIdx].sectionName || '') : ''
  const targetSection = cfg?.dnSectionName != null ? cfg.dnSectionName : rdnSection
  const dn: AttributeConfig = {
    attributeName: '__dn__', isDn: true, rdn: false,
    columnSpan: cfg?.dnColumnSpan || 4, sectionName: targetSection,
  }
  const sectionIdxs: number[] = []
  list.forEach((f, i) => { if ((f.sectionName || '') === targetSection) sectionIdxs.push(i) })
  let insertAt: number
  if (cfg?.dnDisplayOrder != null && cfg.dnDisplayOrder >= 0) {
    const order = Math.min(cfg.dnDisplayOrder, sectionIdxs.length)
    insertAt = order < sectionIdxs.length
      ? sectionIdxs[order]
      : (sectionIdxs.length ? sectionIdxs[sectionIdxs.length - 1] + 1 : list.length)
  } else {
    insertAt = rdnIdx >= 0 ? rdnIdx + 1 : list.length
  }
  list.splice(insertAt, 0, dn)
}

/** Group all visible attributes into sections for create mode. */
const createSections = computed(() => groupIntoSections(allVisibleAttributes.value))

/** Group edit-mode attributes into sections. */
const editSections = computed(() => groupIntoSections(editFormAttributes.value))

function groupIntoSections(attrs: AttributeConfig[]): Section[] {
  const map = new Map<string, Section>()
  for (const attr of attrs) {
    const key = attr.sectionName || ''
    if (!map.has(key)) {
      map.set(key, { name: key, fields: [] })
    }
    map.get(key)!.fields.push(attr)
  }
  const result = Array.from(map.values())
  return result.length ? result : [{ name: '', fields: attrs }]
}

/**
 * Parse and evaluate a computed expression by tokenizing into variable
 * references (${attr}), quoted string literals, concatenation operators (+),
 * and literal text.  No regex used for the concatenation handling.
 */
function evaluateExpression(expr: string): string {
  const parts: string[] = []
  let i = 0
  while (i < expr.length) {
    if (expr[i] === '$' && expr[i + 1] === '{') {
      // Variable reference: ${attrName}
      const end = expr.indexOf('}', i + 2)
      if (end === -1) break
      const name = expr.substring(i + 2, end)
      if (name === local.rdnAttribute) {
        parts.push(local.rdnValue || '')
      } else {
        parts.push(local.attributes[name] || '')
      }
      i = end + 1
    } else if (expr[i] === '+') {
      // Concatenation operator — skip it
      i++
    } else if (expr[i] === '"' || expr[i] === "'") {
      // Quoted string literal
      const quote = expr[i]
      const end = expr.indexOf(quote, i + 1)
      if (end === -1) break
      parts.push(expr.substring(i + 1, end))
      i = end + 1
    } else {
      // Literal text (dots, @domain, or a lone '$'/operator char that didn't
      // start a ${...} reference). Scan from i+1 so the current char is always
      // consumed — otherwise a '$' not followed by '{' would never advance i
      // and the loop would spin forever (mirrors the server-side fix).
      let j = i + 1
      while (j < expr.length && expr[j] !== '$' && expr[j] !== '+' && expr[j] !== '"' && expr[j] !== "'") {
        j++
      }
      parts.push(expr.substring(i, j))
      i = j
    }
  }
  return parts.join('')
}

/**
 * RDN-boundary-aware "is dn within base" check for advisory client validation.
 * Normalizes case and whitespace around RDN separators, then requires an exact
 * match or a true descendant (",<base>" suffix) — mirroring the comma-boundary
 * rule the backend's PermissionService uses, without a full DN parser.
 */
function dnWithinBase(dn: string, base: string): boolean {
  const norm = (s: string) => s.toLowerCase().split(',').map(p => p.trim()).join(',')
  const d = norm(dn)
  const b = norm(base)
  return d === b || d.endsWith(',' + b)
}

/**
 * Computed map of all attribute values derived from computed expressions.
 * Vue tracks which reactive properties are read (e.g. local.attributes.givenName),
 * so this recomputes only when a referenced source attribute changes —
 * no manual watcher, no reentrancy flag, no per-keystroke issues.
 */
const computedAttrValues = computed<Record<string, string>>(() => {
  const result: Record<string, string> = {}
  if (!props.userTemplateConfig?.attributeConfigs || props.isEdit) return result
  for (const attr of props.userTemplateConfig.attributeConfigs) {
    if (!attr.computedExpression) continue
    try {
      result[attr.attributeName] = evaluateExpression(String(attr.computedExpression))
    } catch {
      // Skip failed expression evaluation
    }
  }
  return result
})

// ── Client-side attribute validation ─────────────────────────────────────────
// Mirrors ProvisioningProfileService's server-side rules (required / length /
// regex) for instant field-level feedback. The server re-validates
// authoritatively; this only gates the form submit and renders inline errors.
// Shared validation-error state: `fieldErrors` (key → message, rendered inline),
// `validationErrors` (summary for the top-of-form banner), and `report()` which
// reveals the banner and focuses/scrolls to the first failing field.
const {
  errors: fieldErrors,
  summary: validationErrors,
  showSummary,
  report: reportErrors,
} = useFormErrors({
  labelFor: (name: string): string => {
    if (name === 'dn') return 'DN'
    const c = props.userTemplateConfig?.attributeConfigs?.find(a => a.attributeName === name)
    return c?.customLabel || name
  },
})
const formRootEl = ref<HTMLElement | null>(null)

function rulesFor(attr: AttributeConfig, forCreate: boolean): AttributeRules {
  return {
    attributeName: attr.attributeName,
    label: attr.customLabel || attr.attributeName,
    required: forCreate ? !!attr.requiredOnCreate : false,
    minLength: attr.minLength ?? null,
    maxLength: attr.maxLength ?? null,
    validationRegex: attr.validationRegex ?? null,
    validationMessage: attr.validationMessage ?? null,
    // Mirror the server's syntax layer (DN / email / boolean). Driven by the
    // /attribute-syntax hints so well-known bare attributes (mail, manager) are
    // checked too, not just profile DN_LOOKUP/BOOLEAN fields.
    syntaxKind: syntaxStore.kindFor(attr.inputType, attr.attributeName),
  }
}

/**
 * Validate the visible, user-editable configured attributes. Populates
 * {@link fieldErrors} and returns true when the form is valid. Exposed to the
 * parent (UserListView) so it can gate the save. Returns true when the profile
 * has no attribute template (the fallback path relies on native `required`).
 */
function validate(): boolean {
  const ok = runValidation()
  // Field errors all live on the Attributes tab; surface them there, then show
  // the banner and jump focus to the first failing field.
  if (!ok) activeTab.value = 'attributes'
  reportErrors(formRootEl.value)
  return ok
}

/** Populates {@link fieldErrors}; returns true when the form is valid. */
function runValidation(): boolean {
  for (const k of Object.keys(fieldErrors)) delete fieldErrors[k]
  // The editable DN must stay within the profile's target OU on create. This
  // gate applies even on the fallback path (no attribute template).
  if (dnError.value) {
    fieldErrors.dn = dnError.value
    return false
  }
  const configs = props.userTemplateConfig?.attributeConfigs
  if (!configs) return true

  const forCreate = !props.isEdit
  const rdnName = props.userTemplateConfig?.rdnAttribute
  let ok = true
  for (const attr of configs) {
    if (attr.hidden || attr.computedExpression) continue
    const isRdn = attr.attributeName === rdnName
    // The RDN is immutable in edit mode (its field is disabled), so don't
    // validate it there — a length/regex rule on the RDN attribute must not
    // block edits to an existing entry whose RDN predates the rule.
    if (isRdn && !forCreate) continue
    // Skip fields the user can't edit in this mode (their value is fixed or
    // server-managed) — except the RDN, which is always entered on create.
    const editable = forCreate ? attr.editableOnCreate !== false : attr.editableOnUpdate !== false
    if (!editable && !isRdn) continue

    const value = isRdn && forCreate
      ? local.rdnValue
      : local.attributes[attr.attributeName]
    const err = validateAttributeValue(rulesFor(attr, forCreate), value)
    if (err) {
      fieldErrors[attr.attributeName] = err
      ok = false
    }
  }
  return ok
}

defineExpose({ validate, applyMembershipChanges, hasPendingMembershipChanges })

let syncing = false
watch(local, v => {
  if (syncing) return
  const data = JSON.parse(JSON.stringify(v))
  // Merge computed attribute values into the emitted data
  const cv = computedAttrValues.value
  for (const key in cv) {
    data.attributes[key] = cv[key]
    if (key === local.rdnAttribute) {
      data.rdnValue = cv[key]
    }
  }
  // Carry the live computed-or-overridden DN (escaped) so the parent always
  // submits the current value — local.dn alone is stale until an override.
  if (!props.isEdit) data.dn = effectiveDn.value
  emit('update', data)
}, { deep: true })
watch(() => props.data, v => {
  syncing = true
  Object.assign(local, v)
  Object.assign(local.attributes, v.attributes || {})
  nextTick(() => { syncing = false })
}, { deep: true })

// ── Group membership management ──────────────────────────────────────────────

async function loadGroups() {
  if (!props.dirId) return
  // In create mode, only load groups if user has typed a search query
  // to avoid fetching every group in the directory
  if (!props.isEdit && !groupFilter.value.trim()) {
    allGroups.value = []
    availableGroups.value = []
    return
  }
  loadingGroups.value = true
  try {
    const params: Record<string, string> = {
      limit: String(GROUP_LOAD_LIMIT),
      attributes: GROUP_LOAD_ATTRS,
    }
    if (groupFilter.value.trim()) {
      params.filter = `(cn=*${groupFilter.value.trim()}*)`
    }
    const { data } = await groupsApi.searchGroups(props.dirId, params)
    const entries = Array.isArray(data) ? data : (data?.entries || [])
    // The backend caps at GROUP_LOAD_LIMIT; hitting it means the membership
    // view (and copy-from-user) may be missing groups beyond the cap.
    groupsTruncated.value = entries.length >= GROUP_LOAD_LIMIT
    allGroups.value = entries.map((e: { dn: string, attributes?: Record<string, string[] | undefined> }): GroupItem => ({
      dn: e.dn,
      cn: e.attributes?.cn?.[0] || '—',
      members: e.attributes?.member || e.attributes?.uniqueMember || e.attributes?.memberUid || [],
      memberAttr: e.attributes?.member ? 'member'
        : e.attributes?.uniqueMember ? 'uniqueMember'
        : e.attributes?.memberUid ? 'memberUid'
        : 'member',
    }))
    refreshMemberships()
  } catch (e) { console.warn('Failed to load groups:', e) }
  finally { loadingGroups.value = false }
}

function refreshMemberships() {
  if (props.isEdit) {
    const userDn = local.dn || ''
    memberGroups.value = allGroups.value.filter(g =>
      g.members.some(m => m.toLowerCase() === userDn.toLowerCase())
    )
  }
  filterAvailableGroups()
}

function filterAvailableGroups() {
  const excludedDnSet = new Set()
  // Exclude groups user is already a member of (edit mode)
  for (const g of memberGroups.value) excludedDnSet.add(g.dn.toLowerCase())
  // Exclude groups already pending (create mode) or staged to add (edit mode)
  for (const g of pendingGroups.value) excludedDnSet.add(g.dn.toLowerCase())
  for (const g of stagedAdditions.value) excludedDnSet.add(g.dn.toLowerCase())

  const q = groupFilter.value.toLowerCase()
  availableGroups.value = allGroups.value.filter(g =>
    !excludedDnSet.has(g.dn.toLowerCase()) &&
    (!q || g.cn.toLowerCase().includes(q) || g.dn.toLowerCase().includes(q))
  )
}

function searchAvailableGroups() {
  filterAvailableGroups()
}

function addToGroup(group: GroupItem) {
  if (props.isEdit) {
    // Edit mode: stage the change; it's flushed as one batch on Save.
    // Re-adding a current member that's staged for removal just cancels
    // that removal instead of creating a duplicate addition.
    if (isStagedForRemoval(group.dn)) {
      stagedRemovals.value = stagedRemovals.value.filter(d => d !== group.dn.toLowerCase())
    } else if (!stagedAdditions.value.some(g => g.dn.toLowerCase() === group.dn.toLowerCase())) {
      stagedAdditions.value.push(group)
    }
    filterAvailableGroups()
  } else {
    // Create mode: queue for after save
    pendingGroups.value.push(group)
    filterAvailableGroups()
    emitPendingGroups()
  }
}

/** Stage a current membership for removal (edit mode). */
function stageRemoval(group: GroupItem) {
  if (!isStagedForRemoval(group.dn)) {
    stagedRemovals.value.push(group.dn.toLowerCase())
  }
}

/** Undo a staged removal, keeping the user a member (edit mode). */
function unstageRemoval(group: GroupItem) {
  stagedRemovals.value = stagedRemovals.value.filter(d => d !== group.dn.toLowerCase())
}

/** Drop a staged addition before it's applied (edit mode). */
function unstageAddition(group: GroupItem) {
  stagedAdditions.value = stagedAdditions.value.filter(g => g.dn.toLowerCase() !== group.dn.toLowerCase())
  filterAvailableGroups()
}

/**
 * Row action for the membership list — dispatches by the row's staged state:
 * an existing member stages a removal; a staged removal/addition is undone.
 */
function onMembershipAction(row: MembershipRow) {
  if (row.state === 'member') stageRemoval(row.group)
  else if (row.state === 'removing') unstageRemoval(row.group)
  else unstageAddition(row.group)
}

/** Short label for a source-user DN — the leading RDN value, e.g. "alice". */
function sourceLabel(dn: string): string {
  const rdn = dn.split(',')[0] || dn
  const eq = rdn.indexOf('=')
  return eq >= 0 ? rdn.slice(eq + 1) : rdn
}

/**
 * Stage every group the selected source user belongs to that this user isn't
 * already in. Source memberships are derived from the already-loaded group
 * list (same model as the Current Groups view), so no extra fetch — and the
 * same group-search limit applies. Groups already held or staged are skipped;
 * a group staged for removal is un-staged so the copy wins.
 */
function copyFromSelectedUser() {
  const sourceDn = copySourceDn.value.trim()
  const notif = useNotificationStore()
  if (!sourceDn) return
  if (sourceDn.toLowerCase() === (local.dn || '').toLowerCase()) {
    notif.info('Pick a different user — that is this user.')
    return
  }
  const sourceLower = sourceDn.toLowerCase()
  const sourceGroups = allGroups.value.filter(g =>
    g.members.some(m => m.toLowerCase() === sourceLower))
  const label = sourceLabel(sourceDn)
  if (!sourceGroups.length) {
    notif.info(`${label} has no groups to copy (or none are visible here).`)
    return
  }

  let added = 0
  let shared = 0
  for (const g of sourceGroups) {
    const lower = g.dn.toLowerCase()
    if (isStagedForRemoval(g.dn)) { unstageRemoval(g); added++; continue }
    if (memberGroups.value.some(m => m.dn.toLowerCase() === lower)) { shared++; continue }
    if (stagedAdditions.value.some(a => a.dn.toLowerCase() === lower)) continue
    stagedAdditions.value.push(g)
    added++
  }
  filterAvailableGroups()

  if (added) {
    notif.success(`Staged ${added} group(s) from ${label}${shared ? `; ${shared} already shared` : ''}`)
  } else {
    notif.info(`No new groups to add from ${label}${shared ? ` — ${shared} already shared` : ''}`)
  }
  showCopyFrom.value = false
  copySourceDn.value = ''
}

function removePendingGroup(group: GroupItem) {
  pendingGroups.value = pendingGroups.value.filter(g => g.dn !== group.dn)
  filterAvailableGroups()
  emitPendingGroups()
}

/** Best-effort display name for a group DN, for result messages. */
function cnForDn(dn: string): string {
  const lower = dn.toLowerCase()
  const hit = allGroups.value.find(g => g.dn.toLowerCase() === lower)
    || memberGroups.value.find(g => g.dn.toLowerCase() === lower)
    || stagedAdditions.value.find(g => g.dn.toLowerCase() === lower)
  return hit?.cn || dn.split(',')[0] || dn
}

interface MembershipResultItem { groupDn: string, status: string, message?: string }
interface MembershipResult {
  applied: number, queued: number, refused: number, blocked: number, errored: number,
  items: MembershipResultItem[]
}

/** Turn the batch summary into user-facing notifications. */
function notifyMembershipResult(summary: MembershipResult) {
  const notif = useNotificationStore()
  const okParts: string[] = []
  if (summary.applied) okParts.push(`${summary.applied} applied`)
  if (summary.queued) okParts.push(`${summary.queued} pending approval`)

  const failures = summary.items.filter(
    i => i.status === 'REFUSED' || i.status === 'BLOCKED' || i.status === 'ERROR')
  if (failures.length) {
    const first = failures[0]
    const detail = first.message ? `: ${first.message}` : ''
    notif.error(
      `${failures.length} membership change(s) failed (${cnForDn(first.groupDn)}${
        failures.length > 1 ? ' and others' : ''}${detail})`)
  }
  if (okParts.length) {
    notif.success(`Memberships — ${okParts.join(', ')}`)
  }
}

/**
 * Flush staged membership changes as a single batch (edit mode). Exposed to the
 * parent so it runs as part of Save, after the attribute update. Returns the
 * server summary, or null when there's nothing staged. On a non-error response
 * the staged state is cleared and memberships are reloaded to reflect server
 * truth; a thrown request leaves the staged state intact for retry.
 */
async function applyMembershipChanges(): Promise<MembershipResult | null> {
  if (!props.isEdit || !props.dirId || !local.dn) return null
  const changes: Array<{ groupDn: string, memberAttribute: string, op: 'ADD' | 'REMOVE' }> = []
  for (const g of stagedAdditions.value) {
    changes.push({ groupDn: g.dn, memberAttribute: g.memberAttr, op: 'ADD' })
  }
  for (const dnLower of stagedRemovals.value) {
    const g = memberGroups.value.find(m => m.dn.toLowerCase() === dnLower)
    if (g) changes.push({ groupDn: g.dn, memberAttribute: g.memberAttr, op: 'REMOVE' })
  }
  if (!changes.length) return null

  const { data } = await usersApi.applyMemberships(props.dirId, local.dn, { changes })
  notifyMembershipResult(data as MembershipResult)
  stagedAdditions.value = []
  stagedRemovals.value  = []
  await loadGroups()
  return data as MembershipResult
}

function emitPendingGroups() {
  emit('update', {
    ...JSON.parse(JSON.stringify(local)),
    _pendingGroups: pendingGroups.value.map(g => ({ dn: g.dn, memberAttr: g.memberAttr })),
  })
}

onMounted(() => {
  // Load the attribute-syntax hints once so DN/email/boolean checks mirror the
  // server. Best-effort — validation degrades to input-type-only if it fails.
  syntaxStore.ensureLoaded()
  // Initialize pending groups from profile group assignments passed via data
  if (props.data?._pendingGroups?.length) {
    pendingGroups.value = props.data._pendingGroups.map((g): GroupItem => ({
      dn: g.dn,
      cn: g.dn.split(',')[0] || g.dn,
      memberAttr: g.memberAttr as GroupItem['memberAttr'],
      members: [],
    }))
  }
  if (props.dirId) {
    loadGroups()
  }
  checkIviaTabVisibility()
})

// Reload groups when switching to edit mode with a new user
watch(() => props.data?.dn, () => {
  if (props.dirId) {
    activeTab.value = 'attributes'
    pendingGroups.value = []
    stagedAdditions.value = []
    stagedRemovals.value = []
    showCopyFrom.value = false
    copySourceDn.value = ''
    loadGroups()
    iviaStatus.value = null
    checkIviaTabVisibility()
  }
})

// Re-check IVIA visibility when the directory changes (rare but
// possible if the modal is reused).
watch(() => props.dirId, () => {
  iviaStatus.value = null
  checkIviaTabVisibility()
})
</script>
