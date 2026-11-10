<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <!-- Header. The active profile shows as a tag that spans the row between the
         title and the action buttons (neutral grey when the profile is unthemed). -->
    <div class="mb-6">
      <div class="flex items-center gap-3">
        <h1 class="text-2xl font-bold text-gray-900 whitespace-nowrap">Users</h1>
        <ProfilePill v-if="profileData?.name" class="flex-1 min-w-0" :name="profileData.name" :color="profileData.themeColor" />
        <div class="flex items-center gap-3 ml-auto shrink-0">
          <div v-if="allProfiles.length > 1" class="flex items-center gap-2">
          <label for="ul-profile" class="text-sm text-gray-600 font-medium">Profile:</label>
          <select id="ul-profile" v-model="selectedProfileId" @change="onProfileChange"
            class="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500">
            <option value="">All</option>
            <option v-for="p in allProfiles" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <!--
          Bulk Update only makes sense with ≥2 rows — for a single row the
          per-row Edit action is the right tool. Showing it with 1 selected
          was just noise.
        -->
        <button v-if="bulkSelectActive && can.bulkUpdate" @click="openBulkUpdate" class="btn-secondary">
          Bulk Update ({{ selectedDns.size }})
        </button>
        <button v-if="bulkSelectActive && can.manageMembers" @click="openBulkMembership" class="btn-secondary">
          Manage Groups ({{ selectedDns.size }})
        </button>
          <button v-if="can.create" @click="openCreate" class="btn-primary">+ New User</button>
        </div>
      </div>
      <p class="text-sm text-gray-500 mt-1">Manage users in this directory</p>
    </div>

    <!-- Search bar -->
    <div class="flex gap-3 mb-2">
      <input
        v-model="filterText"
        placeholder="LDAP filter, e.g. (cn=john*)"
        aria-label="LDAP filter"
        class="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        @keyup.enter="search"
      />
      <button @click="search" class="btn-primary">Search</button>
    </div>

    <!-- Visual filter builder + recent searches share one row: the builder's
         "Build filter visually" trigger on the left, the "Recent searches"
         disclosure to its right. Each owns its own collapsible panel. -->
    <div class="flex flex-wrap items-start gap-x-8 gap-y-2 mb-4">
      <LdapFilterBuilder
        v-model="filterText"
        :directory-id="dirId"
        :relevant-attributes="profileAttributeNames"
        :object-classes="filterObjectClasses"
      />
      <RecentSearches
        ref="recentSearchesRef"
        :directory-id="dirId"
        storage-key="users"
        @select="applyRecentSearch"
      />
    </div>

    <ResultsTable
      table-key="users-list"
      :columns="cols"
      :rows="users"
      row-key="dn"
      selectable
      auto-fit-first-view
      v-model:selectedKeys="selectedDns"
      :loading="loading"
      empty-text="No users found"
      @visible-columns-change="visibleColumnKeys = $event"
    >
      <template #toolbar>
        <label class="flex items-center gap-1.5 text-xs text-gray-600"
               title="Also fetch operational attributes (createTimestamp, entryUUID, …). They appear in the Columns menu.">
          <input v-model="includeOperational" type="checkbox" class="rounded border-gray-300" />
          Operational attributes
        </label>
        <button
          v-if="can.exportCsv"
          @click="doExportUsers"
          :disabled="exporting"
          class="btn-secondary text-xs"
        >{{ exporting ? 'Exporting…' : 'Export CSV' }}</button>
      </template>
      <template #cell-dn="{ value, row }">
        <span class="inline-flex items-center gap-1">
          <!-- No max-width cap: the DN truncates against the column's actual
               width (the table is fixed-layout with resizable columns), so a
               widened column shows the full value. -->
          <span class="min-w-0 truncate" :title="value as string">{{ value }}</span>
          <CopyButton
            :text="value as string"
            class="opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100"
          />
          <!-- "No IVIA account" chip — surfaces the enricher's
               isva.hasaccount=false flag (linked mode: no paired secUser;
               inline mode: no secUser objectClass) so admins can spot users
               without an IVIA account without opening each one. Neutral,
               not a warning: plenty of directory users aren't IVIA users.
               Renders only when the addon is on and the row carries the
               flag (community / non-addon directories never get it).
               shrink-0 + whitespace-nowrap keep the chip intact and let the
               DN text truncate to make room for it instead. -->
          <span
            v-if="auth.isIsvaIntegrationEnabled && lacksIviaAccount(row)"
            class="badge-gray text-[10px] shrink-0 whitespace-nowrap"
            :title="`No ${IVIA_ABBR} account. If this user should have ${IVIA_ABBR} access, grant it from the ${IVIA_ABBR} Account tab or run Integrity reconcile.`"
          >No {{ IVIA_ABBR }} account</span>
        </span>
      </template>
      <template #cell-enabled="{ value }">
        <span :class="value !== false ? 'badge-green' : 'badge-red'">
          {{ value !== false ? 'Active' : 'Disabled' }}
        </span>
      </template>
      <!-- Group-membership pills. `groupsExpanded` (toggled from the
           header control below) flips every row between collapsed
           (first few names + a "+N" pill) and the full list. -->
      <template #cell-__groups="{ row }">
        <GroupChips :dns="((row as unknown as UserRow).__groupDns as string[]) ?? []" :expanded="groupsExpanded" />
      </template>
      <!-- Header toggle for the whole Groups column. @click.stop so it
           doesn't trip the header's (no-op, non-sortable) click. -->
      <template #header-__groups>
        <button
          type="button"
          @click.stop="groupsExpanded = !groupsExpanded"
          class="ml-1 align-middle text-gray-400 hover:text-gray-600"
          :aria-pressed="groupsExpanded"
          :title="groupsExpanded ? 'Collapse group lists' : 'Show all groups'"
          :aria-label="groupsExpanded ? 'Collapse group lists' : 'Show all groups'"
        >
          <svg v-if="!groupsExpanded" class="w-3.5 h-3.5 inline-block" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8">
            <path stroke-linecap="round" stroke-linejoin="round" d="M3.75 8.25v-4.5h4.5M20.25 8.25v-4.5h-4.5M3.75 15.75v4.5h4.5M20.25 15.75v4.5h-4.5" />
          </svg>
          <svg v-else class="w-3.5 h-3.5 inline-block" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8">
            <path stroke-linecap="round" stroke-linejoin="round" d="M8.25 3.75v4.5h-4.5M15.75 3.75v4.5h4.5M8.25 20.25v-4.5h-4.5M15.75 20.25v-4.5h4.5" />
          </svg>
        </button>
      </template>
      <template #cell-actions="{ row }">
        <!--
          When the user is mid bulk-select (≥2 rows ticked), per-row actions
          yield to the bulk toolbar — the selection is clearly about
          multiple rows at that point, and letting an individual Delete /
          Move fire would either silently ignore the selection or act on
          just one row.
        -->
        <!-- inline-threshold=1 keeps the actions column as narrow as
             possible: only the Edit primary button stays inline, and every
             item below — Disable/Enable included — folds into the kebab.
             (The primary slot counts as the one inline button, leaving a
             budget of zero item slots.) This is what lets the pinned actions
             column shrink to ~120px without clipping the kebab. -->
        <ActionMenu :disabled="bulkSelectActive"
                    :inline-threshold="1"
                    :items="[
          { label: (row as unknown as UserRow).enabled !== false ? 'Disable' : 'Enable', onClick: () => toggleEnabled(row as unknown as UserRow),
            variant: (row as unknown as UserRow).enabled !== false ? 'warning' : 'success', hidden: !can.enableDisable },
          { label: 'Reset password', onClick: () => openResetPassword(row as unknown as UserRow), hidden: !can.resetPassword },
          { label: 'Move',            onClick: () => openMove(row as unknown as UserRow), hidden: !can.move || !hasOtherProfiles },
          { label: 'Run playbook',    onClick: () => openPlaybookRun(row as unknown as UserRow), hidden: !can.runPlaybook },
          { label: 'View history',    onClick: () => { timelineTarget = row as unknown as UserRow; showTimeline = true }, variant: 'neutral' },
          { label: 'Delete',          onClick: () => confirmDelete(row as unknown as UserRow), danger: true, hidden: !can.delete },
        ]">
          <template #primary="{ disabled }">
            <button v-if="can.edit" @click="openEdit(row as unknown as UserRow)" :disabled="disabled" class="btn-secondary btn-compact">Edit</button>
            <button v-else-if="can.read" @click="openView(row as unknown as UserRow)" :disabled="disabled" class="btn-secondary btn-compact">View</button>
          </template>
        </ActionMenu>
      </template>
    </ResultsTable>

    <!-- Truncation notice: the page stopped at the size limit. "Load all"
         re-runs the search unbounded (the server still caps at its own
         ceiling, in which case the notice stays but without the action).
         The in-table pager handles pagination across the loaded set. -->
    <p
      v-if="searchMeta?.truncated && users.length"
      class="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"
      role="status"
      data-testid="users-truncated"
    >
      First {{ users.length.toLocaleString() }} of
      {{ searchMeta.totalIsLowerBound ? 'more than ' : '' }}{{ searchMeta.total.toLocaleString() }}
      entries returned.
      <template v-if="!searchMeta.all">
        Either
        <button
          type="button"
          class="font-medium text-blue-700 hover:underline disabled:opacity-50"
          :disabled="loadingAll"
          @click="loadAll"
          data-testid="users-load-all"
        >{{ loadingAll ? 'Loading all…' : 'Load All' }}</button>
        or narrow the search filter (or use the visual builder above).
      </template>
      <template v-else>
        Narrow the search filter (or use the visual builder above) to see the rest.
      </template>
    </p>

    <!-- Profile picker modal (step 1 of create) -->
    <AppModal v-model="showTemplatePicker" title="Choose Profile" size="sm">
      <div class="space-y-2">
        <p class="text-sm text-gray-600 mb-3">Select a provisioning profile to define which attributes are available for the new user.</p>
        <div v-if="allProfiles.length === 0" class="text-sm text-gray-500 py-4 text-center">
          No provisioning profiles are configured for this directory. Create a profile in the Profiles settings first.
        </div>
        <button
          v-for="p in allProfiles"
          :key="p.id"
          @click="selectProfileAndCreate(p)"
          class="w-full text-left px-4 py-3 border border-gray-200 rounded-lg hover:bg-blue-50 hover:border-blue-300 transition-colors"
        >
          <span class="font-medium text-gray-900 text-sm">{{ p.name }}</span>
          <span class="text-xs text-gray-500 ml-2">({{ (p.objectClassNames || []).join(', ') }})</span>
        </button>
      </div>
      <template #footer>
        <button @click="showTemplatePicker = false" class="btn-neutral">Cancel</button>
      </template>
    </AppModal>

    <!-- Create/Edit modal (step 2 of create, or edit) -->
    <AppModal v-model="showModal" size="xl" :dirty="modalDirty">
      <template #title>
        <span class="whitespace-nowrap">{{ viewOnly ? 'User Details' : editingDn ? 'Edit User' : 'New User' }}</span>
        <ProfilePill v-if="profileConfig?.name" class="flex-1 min-w-0"
                     :name="profileConfig.name" :color="profileConfig.themeColor" />
      </template>
      <UserForm ref="userFormRef" :data="form" :is-edit="!!editingDn" :read-only="viewOnly" :user-template-config="profileConfig ?? undefined" :dir-id="dirId" :profile-id="selectedProfileId" @update="(v: UserFormState) => form = v" />
      <template #footer="{ close }">
        <!-- Cancel routes through the AppModal close guard so unsaved values
             get the same discard confirmation as the × button and Escape. -->
        <button @click="close()" class="btn-neutral">{{ viewOnly ? 'Close' : 'Cancel' }}</button>
        <button v-if="!viewOnly" @click="save" :disabled="saving" class="btn-primary">{{ saving ? 'Saving…' : 'Save' }}</button>
      </template>
    </AppModal>

    <!-- Move modal -->
    <AppModal v-model="showMove" title="Move User" size="sm">
      <div class="space-y-4">
        <p class="text-sm text-gray-600">
          Move <span class="font-mono break-all">{{ moveTarget?.dn }}</span>
          <template v-if="moveSourceProfile?.name"> out of the <strong>{{ moveSourceProfile.name }}</strong> profile</template>
          into another provisioning profile in this directory.
        </p>
        <div>
          <label for="ul-move-dest" class="block text-sm font-medium text-gray-700 mb-1">
            Destination profile <span class="text-red-500">*</span>
          </label>
          <select id="ul-move-dest" v-model="moveDestProfileId" class="input w-full">
            <option value="" disabled>Select a profile…</option>
            <option v-for="p in moveTargets" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <p v-if="moveDestTargetDn" class="mt-1 text-xs text-gray-500 font-mono break-all">
            New location: {{ moveDestTargetDn }}
          </p>
        </div>
      </div>
      <template #footer>
        <button @click="showMove = false" class="btn-neutral">Cancel</button>
        <button @click="doMove" :disabled="saving || !moveDestProfileId" class="btn-primary">Move</button>
      </template>
    </AppModal>

    <!-- Reset Password modal -->
    <AppModal v-model="showResetPassword" title="Reset Password" size="sm">
      <div class="space-y-4">
        <p class="text-sm text-gray-600">Reset password for:</p>
        <p class="text-sm font-mono text-gray-900 bg-gray-50 px-3 py-2 rounded-lg break-all">{{ resetPwTarget?.dn }}</p>
        <PasswordPolicyStatus v-if="resetPwTarget" :directory-id="dirId" :user-dn="resetPwTarget.dn" />
        <div>
          <label for="ul-pw-new" class="block text-sm font-medium text-gray-700 mb-1">New Password <span class="text-red-500">*</span></label>
          <div class="relative">
            <input id="ul-pw-new" v-model="resetPwNew" :type="resetPwVisibleNew ? 'text' : 'password'" required placeholder="Enter new password"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 pr-9" />
            <button type="button" class="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
              @mousedown.prevent="resetPwVisibleNew = true" @mouseup.prevent="resetPwVisibleNew = false"
              @mouseleave="resetPwVisibleNew = false" @touchstart.prevent="resetPwVisibleNew = true" @touchend.prevent="resetPwVisibleNew = false"
              title="Hold to show password">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path v-if="!resetPwVisibleNew" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
              </svg>
            </button>
          </div>
        </div>
        <div>
          <label for="ul-pw-confirm" class="block text-sm font-medium text-gray-700 mb-1">Confirm Password <span class="text-red-500">*</span></label>
          <div class="relative">
            <input id="ul-pw-confirm" v-model="resetPwConfirm" :type="resetPwVisibleConfirm ? 'text' : 'password'" required placeholder="Re-enter new password"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 pr-9" />
            <button type="button" class="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
              @mousedown.prevent="resetPwVisibleConfirm = true" @mouseup.prevent="resetPwVisibleConfirm = false"
              @mouseleave="resetPwVisibleConfirm = false" @touchstart.prevent="resetPwVisibleConfirm = true" @touchend.prevent="resetPwVisibleConfirm = false"
              title="Hold to show password">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path v-if="!resetPwVisibleConfirm" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
              </svg>
            </button>
          </div>
        </div>
        <div v-if="resetPwNew" class="flex items-center gap-2">
          <div class="flex-1 h-1.5 bg-gray-200 rounded-full overflow-hidden">
            <div class="h-full rounded-full transition-all" :class="pwStrengthColor" :style="{ width: pwStrengthPct + '%' }"></div>
          </div>
          <span class="text-xs font-medium" :class="pwStrengthTextColor">{{ pwStrengthLabel }}</span>
        </div>
        <p v-if="resetPwConfirm && resetPwNew !== resetPwConfirm" class="text-xs text-red-600">Passwords do not match.</p>
        <p v-if="resetPwError" class="text-sm text-red-600">{{ resetPwError }}</p>
      </div>
      <template #footer>
        <button @click="showResetPassword = false" class="btn-neutral">Cancel</button>
        <button @click="doResetPassword" :disabled="saving || !resetPwNew || resetPwNew !== resetPwConfirm" class="btn-primary">
          {{ saving ? 'Resetting…' : 'Reset Password' }}
        </button>
      </template>
    </AppModal>

    <!-- Bulk Attribute Update modal -->
    <AppModal v-model="showBulkUpdate" title="Bulk Attribute Update" size="lg">
      <div class="space-y-4">
        <p class="text-sm text-gray-600">
          Apply attribute changes to <strong>{{ selectedDns.size }}</strong> selected user(s).
        </p>
        <div v-for="(mod, i) in bulkMods" :key="i" class="flex gap-2 items-end">
          <div class="flex-1">
            <label v-if="i === 0" class="block text-xs font-medium text-gray-600 mb-1">Operation</label>
            <select v-model="mod.operation" aria-label="Operation" class="input w-full">
              <option value="REPLACE">Set (replace)</option>
              <option value="ADD">Add value</option>
              <option value="DELETE">Remove value</option>
            </select>
          </div>
          <div class="flex-1">
            <label v-if="i === 0" class="block text-xs font-medium text-gray-600 mb-1">Attribute</label>
            <input v-model="mod.attribute" placeholder="e.g. department" aria-label="Attribute" class="input w-full" />
          </div>
          <div class="flex-[2]">
            <label v-if="i === 0" class="block text-xs font-medium text-gray-600 mb-1">Value(s)</label>
            <input v-model="mod.value" :placeholder="mod.operation === 'DELETE' ? '(leave empty to remove all)' : 'e.g. Engineering'" aria-label="Value(s)" class="input w-full" />
          </div>
          <button @click="bulkMods.splice(i, 1)" aria-label="Remove modification" class="text-red-400 hover:text-red-600 text-lg leading-none pb-2"
            :class="{ 'invisible': bulkMods.length <= 1 }">&times;</button>
        </div>
        <button @click="addBulkMod" class="text-sm text-blue-600 hover:text-blue-800">+ Add another modification</button>
        <div v-if="bulkResult" class="p-3 rounded-lg text-sm" :class="bulkResult.errors ? 'bg-amber-50 border border-amber-200' : 'bg-green-50 border border-green-200'">
          <p><strong>{{ bulkResult.updated }}</strong> updated, <strong>{{ bulkResult.errors }}</strong> errors</p>
          <ul v-if="bulkResult.failures?.length" class="mt-1 space-y-0.5">
            <li v-for="f in bulkResult.failures" :key="f.dn" class="text-xs text-red-600">{{ f.dn }}: {{ f.message }}</li>
          </ul>
        </div>
      </div>
      <template #footer>
        <button @click="showBulkUpdate = false" class="btn-neutral">Close</button>
        <button @click="doBulkUpdate" :disabled="bulkUpdating || !canBulkUpdate" class="btn-primary">
          {{ bulkUpdating ? 'Updating…' : 'Apply Changes' }}
        </button>
      </template>
    </AppModal>

    <!-- Bulk Group Membership modal -->
    <AppModal v-model="showBulkMembership" title="Manage Group Memberships" size="lg">
      <!-- flex column chain down to the search-results list so it fills the
           modal's remaining height instead of capping at a fixed max. -->
      <div class="space-y-4 flex-1 min-h-0 flex flex-col">
        <p class="text-sm text-gray-600">
          Add or remove group memberships for <strong>{{ selectedDns.size }}</strong> selected user(s).
          Adds may require approval; removes are applied directly.
        </p>

        <!-- Add / Remove toggle -->
        <div class="flex gap-2">
          <button type="button" @click="bulkMembershipMode = 'ADD'"
                  :class="bulkMembershipMode === 'ADD' ? 'btn-primary' : 'btn-neutral'" class="text-sm">Add to groups</button>
          <button type="button" @click="bulkMembershipMode = 'REMOVE'"
                  :class="bulkMembershipMode === 'REMOVE' ? 'btn-primary' : 'btn-neutral'" class="text-sm">Remove from groups</button>
        </div>

        <!-- Chosen groups -->
        <div class="flex-1 min-h-0 flex flex-col">
          <label class="block text-xs font-medium text-gray-600 mb-1">Groups</label>
          <div v-if="bulkChosenGroups.length" class="flex flex-wrap gap-1.5 mb-2">
            <span v-for="g in bulkChosenGroups" :key="g.dn" class="badge-blue" :title="g.dn">
              {{ g.cn }}
              <button type="button" @click="removeBulkGroup(g)" aria-label="Remove group"
                      class="ml-1 text-blue-700 hover:text-blue-900 leading-none">&times;</button>
            </span>
          </div>
          <p v-else class="text-xs text-gray-500 mb-2">No groups chosen yet — search and add below.</p>

          <div class="flex gap-2">
            <input v-model="bulkGroupFilter" @keyup.enter="searchBulkGroups"
                   placeholder="Search groups…" aria-label="Search groups" class="input flex-1" />
            <button type="button" @click="searchBulkGroups" :disabled="bulkGroupSearching" class="btn-secondary text-sm shrink-0">Search</button>
          </div>
          <ul v-if="bulkGroupResults.length" class="mt-2 divide-y divide-gray-100 border border-gray-200 rounded-lg min-h-0 overflow-y-auto">
            <li v-for="g in bulkGroupResults" :key="g.dn" class="flex items-center justify-between px-3 py-2 text-sm hover:bg-gray-50">
              <div class="min-w-0 flex-1">
                <div class="font-medium text-gray-800 truncate">{{ g.cn }}</div>
                <code class="text-xs text-gray-500 block truncate" :title="g.dn">{{ g.dn }}</code>
              </div>
              <button type="button" @click="addBulkGroup(g)" class="ml-2 text-blue-600 hover:text-blue-800 text-xs font-medium">Add</button>
            </li>
          </ul>
          <p v-else-if="bulkGroupSearched && !bulkGroupSearching" class="text-xs text-gray-500 mt-2 text-center">No matching groups.</p>
        </div>

        <!-- Result summary -->
        <div v-if="bulkMembershipResult" class="p-3 rounded-lg text-sm"
             :class="bulkMembershipResult.failed ? 'bg-amber-50 border border-amber-200' : 'bg-green-50 border border-green-200'">
          <p>
            <strong>{{ bulkMembershipResult.applied }}</strong> applied,
            <strong>{{ bulkMembershipResult.queued }}</strong> pending approval,
            <strong>{{ bulkMembershipResult.failed }}</strong> failed
            across {{ bulkMembershipResult.users }} user(s)
          </p>
          <ul v-if="bulkMembershipResult.userFailures.length" class="mt-1 space-y-0.5">
            <li v-for="f in bulkMembershipResult.userFailures" :key="f.dn" class="text-xs text-red-600">{{ f.dn }}: {{ f.message }}</li>
          </ul>
        </div>
      </div>
      <template #footer>
        <button @click="showBulkMembership = false" class="btn-neutral">Close</button>
        <button @click="doBulkMembership" :disabled="bulkMembershipBusy || !bulkChosenGroups.length" class="btn-primary">
          {{ bulkMembershipBusy ? 'Applying…' : (bulkMembershipMode === 'ADD' ? 'Add to Groups' : 'Remove from Groups') }}
        </button>
      </template>
    </AppModal>

    <!-- Activity Timeline modal -->
    <AppModal v-model="showTimeline" title="Activity History" size="lg">
      <div v-if="timelineTarget" class="mb-3">
        <p class="text-[13px] font-mono text-gray-500 break-all">{{ timelineTarget.dn }}</p>
      </div>
      <EntryTimeline v-if="timelineTarget" :directory-id="dirId" :target-dn="timelineTarget.dn" />
      <template #footer>
        <button @click="showTimeline = false" class="btn-neutral">Close</button>
      </template>
    </AppModal>

    <ConfirmDialog v-model="showDelete" title="Delete User" :message="`Delete '${deleteTarget?.dn}'?`" confirm-label="Delete" danger @confirm="doDelete" />

    <!-- Playbook Run Modal -->
    <AppModal v-model="showPlaybookModal" title="Run Playbook" size="md">
      <div class="space-y-4">
        <p class="text-sm text-gray-600">Target: <code class="bg-gray-100 px-1 rounded text-xs">{{ playbookTarget?.dn }}</code></p>
        <div>
          <label for="ul-playbook" class="block text-sm font-medium text-gray-700 mb-1">Select Playbook</label>
          <select id="ul-playbook" v-model="selectedPlaybookId" @change="playbookPreview = null" class="input w-full">
            <option :value="null" disabled>Choose a playbook...</option>
            <option v-for="pb in availablePlaybooks" :key="pb.id" :value="pb.id">
              {{ pb.name }} ({{ pb.type }})
            </option>
          </select>
        </div>
        <button v-if="selectedPlaybookId" @click="doPlaybookPreview" class="btn-secondary text-sm">Preview Steps</button>
        <div v-if="playbookPreview" class="border rounded-lg divide-y">
          <div v-for="step in playbookPreview.steps" :key="step.stepOrder" class="px-3 py-2 text-sm flex items-start gap-2">
            <span class="text-gray-500 font-mono w-5 shrink-0">{{ step.stepOrder + 1 }}.</span>
            <div>
              <span class="font-medium">{{ step.description }}</span>
              <span v-if="!step.reversible" class="ml-2 text-red-500 text-xs font-medium">IRREVERSIBLE</span>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <button @click="showPlaybookModal = false" class="btn-neutral">Cancel</button>
        <button @click="doPlaybookExecute" :disabled="playbookExecuting || !playbookPreview" class="btn-primary">
          {{ playbookExecuting ? 'Executing...' : 'Execute' }}
        </button>
      </template>
    </AppModal>

    <!-- Playbook Result Modal -->
    <AppModal v-model="showPlaybookResult" title="Playbook Result" size="md">
      <template v-if="playbookResult">
        <div class="mb-3">
          <span class="text-sm font-medium">Status: </span>
          <span :class="{ 'text-green-600': playbookResult.status === 'SUCCESS', 'text-red-600': playbookResult.status === 'FAILED', 'text-amber-600': playbookResult.status === 'PARTIAL' }" class="font-medium">
            {{ playbookResult.status }}
          </span>
        </div>
        <div class="border rounded-lg divide-y min-h-0 overflow-y-auto">
          <div v-for="step in parsedPlaybookResults(playbookResult.stepResults)" :key="step.stepOrder" class="px-3 py-2 text-sm flex items-center gap-2">
            <span class="font-mono text-gray-500 w-5">{{ step.stepOrder + 1 }}.</span>
            <span class="font-medium">{{ step.action }}</span>
            <span :class="{ 'text-green-600': step.status === 'SUCCESS', 'text-red-600': step.status === 'FAILED', 'text-gray-500': step.status === 'SKIPPED' }" class="ml-auto text-xs font-medium">{{ step.status }}</span>
          </div>
        </div>
        <div v-if="playbookResult.status === 'PARTIAL'" class="mt-4 flex justify-end">
          <button @click="doPlaybookRollback(playbookResult.id)" class="btn-secondary text-sm text-amber-600">Rollback Completed Steps</button>
        </div>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, watch } from 'vue'
import { useUnsavedChangesGuard } from '@/composables/useUnsavedChangesGuard'
import { useProfilePickerStore } from '@/stores/profilePicker'
import { useRoute } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { useApi, downloadBlob } from '@/composables/useApi'
import { IVIA_ABBR, isIviaAttr, iviaAttrLabel } from '@/constants/productNames'
import * as usersApi from '@/api/users'
import * as groupsApi from '@/api/groups'
import { exportCsv as exportUsersCsv } from '@/api/csvTemplates'
import { listAttributeTypes } from '@/api/schema'
import {
  buildUserAttributeColumns, exportAttributeNames, useOperationalAttributesToggle,
} from '@/composables/useUserAttributeColumns'
import { listProfiles, getProfile } from '@/api/profiles'
import { listEnabled as listEnabledPlaybooks, previewPlaybook, executePlaybook, rollbackExecution } from '@/api/playbooks'
import { usePermissions } from '@/composables/usePermissions'
import ResultsTable from '@/components/ResultsTable.vue'
import LdapFilterBuilder from '@/components/LdapFilterBuilder.vue'
import { rowObjectClasses } from '@/composables/useLdapFilter'
import RecentSearches from '@/components/RecentSearches.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import AppModal from '@/components/AppModal.vue'
import FormField from '@/components/FormField.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import UserForm from './UserForm.vue'
import CopyButton from '@/components/CopyButton.vue'
import EntryTimeline from '@/components/EntryTimeline.vue'
import PasswordPolicyStatus from '@/components/PasswordPolicyStatus.vue'
import GroupChips from '@/components/GroupChips.vue'
import { rdnValue } from '@/composables/useEntryClassification'
import { ensureNamingValues, parseLeadingRdn } from '@/utils/dn'
import { resolveGroupMembers } from '@/utils/groupMembers'
import ProfilePill from '@/components/ProfilePill.vue'

interface ProfileLite {
  id: string
  name: string
  /** Optional #RRGGBB theme colour; when set the page header / modal render a band of it. */
  themeColor?: string | null
  targetUserDn?: string | null
  rdnAttribute?: string
  showDnField?: boolean
  dnTemplate?: string | null
  enabled?: boolean
  objectClassNames?: string[]
  attributeConfigs?: Array<{ attributeName: string, defaultValue?: string }>
  groupAssignments?: Array<{ groupDn: string, memberAttribute: string }>
}

interface UserRow {
  dn: string
  enabled?: boolean | string
  __entry: { dn: string, attributes?: Record<string, string[] | string | null> }
  _raw?: { attributes?: Record<string, string[] | string | null> }
  [key: string]: unknown
}

interface UserFormState {
  dn?: string
  parentDn?: string
  rdnAttribute?: string
  rdnValue?: string
  attributes?: Record<string, unknown>
  _pendingGroups?: Array<{ dn: string, memberAttr: string }>
}

interface BulkMod {
  operation: 'REPLACE' | 'ADD' | 'DELETE'
  attribute: string
  value: string
}

interface BulkResult {
  updated: number
  errors: number
  failures?: Array<{ dn: string, message: string }>
}

interface BulkGroup {
  dn: string
  cn: string
  memberAttr: 'member' | 'uniqueMember' | 'memberUid'
}

interface BulkMembershipResult {
  users: number
  applied: number
  queued: number
  failed: number
  userFailures: Array<{ dn: string, message: string }>
}

// Per-user response shape from POST /users/memberships (see #165).
interface MembershipApplyItem { status: string, message?: string }
interface MembershipApplyResponse {
  applied: number, queued: number, refused: number, blocked: number, errored: number,
  items: MembershipApplyItem[]
}

interface Playbook { id: string, name: string, type: string }
interface PlaybookStep { stepOrder: number, description?: string, reversible?: boolean, action?: string, status?: string }
interface PlaybookPreview { steps: PlaybookStep[] }
interface PlaybookResult { id: string, status: 'SUCCESS' | 'FAILED' | 'PARTIAL', stepResults: string }

// Tier-2 pagination model: the server returns one page of up to
// FETCH_LIMIT entries and the table paginates client-side. When the page
// was cut short the server also says how many entries matched in all, and
// the notice under the table offers "Load all" (the same search unbounded,
// capped at the server's own ceiling) or narrowing the filter. No "Load
// more" — it composed badly with the in-table pager (two layers of
// pagination disagreeing about "Page 1 of 1" vs "of 4" depending on which
// had just acted).
const FETCH_LIMIT = 1000

/** What the users search endpoint says lies beyond the page it returned. */
interface UserSearchPage {
  entries?: Array<{ dn: string, attributes?: Record<string, string[] | string | null> }>
  truncated?: boolean
  total?: number
  totalIsLowerBound?: boolean
  /** Entry DN → the DNs of its direct and nested groups (asked for with includeGroups). */
  groups?: Record<string, string[]> | null
}
interface SearchMeta {
  truncated: boolean
  total: number
  totalIsLowerBound: boolean
  /** The page was requested unbounded (Load all), so there is nothing more to load. */
  all: boolean
}

const route  = useRoute()
const notif  = useNotificationStore()
const auth   = useAuthStore()
const { loading, call } = useApi()

// Feature gating for the action surface. Mirrors the backend
// @RequiresFeature checks on UserController so admins only see the
// verbs they're actually granted (the server still enforces; this just
// avoids showing actions that would 403). The effective feature set
// comes from /auth/me — superadmins get every feature, admins get their
// base-role defaults + overrides — so gating on hasFeature never hides an
// action the caller can actually perform.
const { hasFeature } = usePermissions()
const can = computed(() => ({
  read:          hasFeature('user.read'),
  create:        hasFeature('user.create'),
  edit:          hasFeature('user.edit'),
  delete:        hasFeature('user.delete'),
  move:          hasFeature('user.move'),
  resetPassword: hasFeature('user.reset_password'),
  enableDisable: hasFeature('user.enable_disable'),
  bulkUpdate:    hasFeature('bulk.attribute_update'),
  manageMembers: hasFeature('group.manage_members'),
  exportCsv:     hasFeature('bulk.export'),
  runPlaybook:   hasFeature('playbook.execute') && auth.isPlaybooksEnabled,
}))

/**
 * True iff the row carries the IsvaUserReadEnricher's isva.hasaccount=false
 * flag. The enricher emits it as a lowercase string ('true' / 'false'); rows
 * it couldn't classify, and non-IVIA directories, have no attribute at all.
 * Defensive against the value being an array (LDAP attrs are lists on the wire).
 */
function lacksIviaAccount(row: unknown): boolean {
  const v = (row as Record<string, unknown> | null)?.['isva.hasaccount']
  if (Array.isArray(v)) return v.length > 0 && String(v[0]).toLowerCase() === 'false'
  return typeof v === 'string' && v.toLowerCase() === 'false'
}

// Column-level expand toggle for the Groups column: collapsed shows the
// first few group names + a "+N" pill; expanded shows the full list for
// every row. Driven from the header control on the Groups column.
const groupsExpanded = ref(false)

const dirId          = route.params.dirId as string
const profilePicker  = useProfilePickerStore()
const users          = ref<UserRow[]>([])
const filterText     = ref('')
const searchMeta     = ref<SearchMeta | null>(null)
const loadingAll     = ref(false)
const selectedDns    = ref<Set<unknown>>(new Set())

/**
 * ≥2 rows ticked = "bulk select mode": the Bulk Update button is visible
 * and per-row actions (Edit / Disable / Delete / …) are disabled so the
 * user can't accidentally act on just one row while the selection
 * suggests otherwise.
 */
const bulkSelectActive = computed(() => selectedDns.value.size >= 2)
const showTemplatePicker = ref(false)
const showModal      = ref(false)
const showMove       = ref(false)
const showDelete     = ref(false)
const editingDn      = ref<string | null>(null)
// The edit dialog opened view-only (see openView); reset whenever it closes
// so the create and edit flows never inherit it.
const viewOnly       = ref(false)
// Snapshot of the attribute values as loaded into the edit form, in the same
// joined-string shape the form holds. save() diffs against it so only
// actually-changed attributes ship as modifications.
const editOriginalAttributes = ref<Record<string, unknown>>({})

// Snapshot of the whole form state once the modal (create or edit) has opened
// and UserForm's mount-time normalization has settled. Drives the dialog's
// unsaved-changes guard: dirty = the form differs from this snapshot, or
// group-membership changes are staged. Empty string = not yet captured =
// never dirty, so the guard can't misfire while the form is still seeding.
//
// isva.* enrichment is excluded from the comparison: it's read-only and
// refreshed in place after IVIA account actions (suspend flips
// ivia.secacctvalid, etc.), and a server-side refresh must never make an
// untouched dialog claim unsaved work.
function dirtyComparable(state: UserFormState): string {
  const attrs = Object.fromEntries(
    Object.entries(state?.attributes || {}).filter(([k]) => !isIviaAttr(k)))
  return JSON.stringify({ ...state, attributes: attrs })
}
const formSnapshot = ref('')
watch(showModal, (open) => {
  if (!open) viewOnly.value = false
  formSnapshot.value = ''
  if (open) {
    nextTick(() => { formSnapshot.value = dirtyComparable(form.value) })
  }
})
const modalDirty = computed(() =>
  showModal.value && !viewOnly.value && formSnapshot.value !== '' && (
    dirtyComparable(form.value) !== formSnapshot.value
    || !!userFormRef.value?.hasPendingMembershipChanges
  ))
const deleteTarget   = ref<UserRow | null>(null)
const moveTarget        = ref<UserRow | null>(null)
const moveDestProfileId = ref('')
const showTimeline   = ref(false)
const timelineTarget = ref<UserRow | null>(null)
const saving             = ref(false)
const exporting          = ref(false)
const showResetPassword  = ref(false)
const resetPwTarget      = ref<UserRow | null>(null)
const resetPwNew         = ref('')
const resetPwConfirm     = ref('')
const resetPwError       = ref('')
const resetPwVisibleNew     = ref(false)
const resetPwVisibleConfirm = ref(false)

function computePasswordStrength(pw: string): number {
  if (!pw) return 0
  let score = 0
  if (pw.length >= 8) score++
  if (pw.length >= 12) score++
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++
  if (/\d/.test(pw)) score++
  if (/[^a-zA-Z0-9]/.test(pw)) score++
  return score
}

const pwStrength = computed(() => computePasswordStrength(resetPwNew.value))
const pwStrengthPct = computed(() => Math.min(100, pwStrength.value * 20))
const pwStrengthLabel = computed(() => {
  const s = pwStrength.value
  if (s <= 1) return 'Weak'
  if (s <= 2) return 'Fair'
  if (s <= 3) return 'Good'
  return 'Strong'
})
const pwStrengthColor = computed(() => {
  const s = pwStrength.value
  if (s <= 1) return 'bg-red-500'
  if (s <= 2) return 'bg-amber-500'
  if (s <= 3) return 'bg-blue-500'
  return 'bg-green-500'
})
const pwStrengthTextColor = computed(() => {
  const s = pwStrength.value
  if (s <= 1) return 'text-red-600'
  if (s <= 2) return 'text-amber-600'
  if (s <= 3) return 'text-blue-600'
  return 'text-green-600'
})

/**
 * Union of attribute keys appearing on any returned user row. Every attribute
 * the search surfaces becomes a column (see buildUserAttributeColumns for the
 * order, defaults and what's left out — the same rules the user reports use).
 */
const discoveredAttrs = computed<string[]>(() => {
  const seen = new Set<string>()
  for (const row of users.value) {
    const entry = row.__entry
    if (!entry?.attributes) continue
    for (const k of Object.keys(entry.attributes)) seen.add(k)
  }
  return [...seen]
})

/** Fetch operational attributes too (`+`); remembered per browser. */
const includeOperational = useOperationalAttributesToggle('users-list')

/**
 * Lower-cased attribute name → schema spelling, so headings read `givenName`
 * rather than the backend's lower-cased `givenname`. Best effort: without
 * schema access the headings fall back to the known defaults' casing.
 */
const schemaNames = ref<Map<string, string>>(new Map())
async function loadSchemaNames(): Promise<void> {
  try {
    const { data } = await listAttributeTypes(dirId)
    const map = new Map<string, string>()
    for (const a of (Array.isArray(data) ? data : []) as Array<{ name?: string }>) {
      if (a.name) map.set(a.name.toLowerCase(), a.name)
    }
    schemaNames.value = map
  } catch {
    schemaNames.value = new Map()
  }
}

/** Visible column keys in display order, reported by ResultsTable; the CSV export follows them. */
const visibleColumnKeys = ref<string[]>([])

// Every column carries an explicit defaultWidth. ResultsTable is
// table-layout:fixed and renders columns at exactly their set widths (a
// single trailing filler column soaks up any slack), so a column without a
// width would fall back to width:auto and compete with that filler for the
// leftover space — sizing unpredictably. Row cells are keyed by the backend's
// lower-cased attribute names, which the column keys keep; only the heading
// is re-cased. The row-action menu renders via a synthetic `actions` column
// (alwaysVisible, pinned to the right edge so it stays reachable).
const cols = computed(() => [
  ...buildUserAttributeColumns(discoveredAttrs.value, {
    groupsKey: '__groups',
    schemaNames: schemaNames.value,
    // IVIA enrichment columns (keyed `isva.*` by the backend) keep their
    // stable internal key but display the marketing `ivia.` prefix.
    labelOverride: k => (isIviaAttr(k) ? iviaAttrLabel(k) : undefined),
    columnDefaults: { defaultWidth: 180 },
    dnColumn: { defaultWidth: 360 },
  }).map(c => (c.key === '__groups' ? { ...c, defaultWidth: 280 } : c)),
  { key: 'actions', label: '', alwaysVisible: true, sortable: false, defaultWidth: 120, pinned: true },
])

const allProfiles       = ref<ProfileLite[]>([])
const selectedProfileId = ref('')
const profileData       = ref<ProfileLite | null>(null)
const profileConfig     = ref<ProfileLite | null>(null)

// "Move" targets: the operator's other accessible profiles in this directory.
// allProfiles is already permission-scoped (the server returns only profiles
// the admin has a role on), so when the directory exposes ≤1 such profile there
// is nowhere to move a user and the action is hidden (see the ActionMenu gate).
const hasOtherProfiles = computed(() => allProfiles.value.length > 1)

/**
 * The profile that owns an existing entry.
 *
 * DN containment is authoritative — this mirrors the backend's
 * ProvisioningProfileService.resolveProfileForDn (the profile whose target OU
 * is a suffix of the entry DN, most specific wins), which is what audit,
 * approvals, self-service and the Move flow all resolve with. objectClass is
 * only a fallback for an entry that sits outside every profile's OU: matching
 * on it alone silently mis-resolves as soon as an entry's classes drift from
 * the profile's configured set — an entry created before an objectClass was
 * added to the profile, or loaded out-of-band, matches nothing and drops the
 * whole customized edit form back to raw attribute names.
 *
 * Both lists are permission- and enabled-scoped (see loadProfiles), so a
 * disabled profile or one the admin has no role on still resolves to null.
 */
function resolveProfileForEntry(dn: string, objectClasses: string[] = []): ProfileLite | null {
  const dnLower = dn.toLowerCase()
  const byDn = allProfiles.value
    .filter(p => p.targetUserDn && dnLower.endsWith(p.targetUserDn.toLowerCase()))
    .sort((a, b) => (b.targetUserDn?.length ?? 0) - (a.targetUserDn?.length ?? 0))[0]
  if (byDn) return byDn
  if (!objectClasses.length) return null
  // The empty-list guard keeps `every` from matching vacuously: a profile
  // declaring no object classes would otherwise claim every entry.
  return allProfiles.value.find(p =>
    (p.objectClassNames || []).length > 0 &&
    (p.objectClassNames || []).every(oc => objectClasses.includes(oc.toLowerCase()))
  ) ?? null
}

// The row's current profile, so the modal can drop it from the destination
// list. DN-only by design: Move is about which OU the entry lives in.
const moveSourceProfile = computed<ProfileLite | null>(() =>
  moveTarget.value?.dn ? resolveProfileForEntry(moveTarget.value.dn) : null)
const moveTargets = computed<ProfileLite[]>(() =>
  allProfiles.value.filter(p => p.id !== moveSourceProfile.value?.id))
const moveDestTargetDn = computed(() =>
  moveTargets.value.find(p => p.id === moveDestProfileId.value)?.targetUserDn ?? '')

// Attribute names the active profile declares — fed to LdapFilterBuilder
// alongside filterObjectClasses below. The builder suggests the superset
// of attributes those classes allow; these cover any profile field no
// listed class declares.
const profileAttributeNames = computed<string[]>(() =>
  (profileData.value?.attributeConfigs ?? [])
    .map(a => a.attributeName)
    .filter((n): n is string => typeof n === 'string' && n.length > 0),
)

// objectClasses fed to LdapFilterBuilder so its scoped suggestions cover
// every attribute those classes allow, not just the profile's form fields:
// the profile's own objectClassNames plus every objectClass value present
// on the loaded rows (entries in the branch often carry auxiliary classes —
// posixAccount, shadowAccount, vendor extensions — the profile doesn't list).
const filterObjectClasses = computed<string[]>(() => {
  const out = [...(profileData.value?.objectClassNames ?? [])]
  for (const row of users.value) out.push(...rowObjectClasses(row.__entry?.attributes))
  return out
})

const emptyForm = (): UserFormState => {
  // Pre-populate default values from profile attribute configs
  const attributes: Record<string, unknown> = {}
  if (profileData.value?.attributeConfigs) {
    for (const attr of profileData.value.attributeConfigs) {
      if (attr.defaultValue) {
        attributes[attr.attributeName] = attr.defaultValue
      }
    }
  }
  return {
    parentDn: profileData.value?.targetUserDn || '',
    // No profile: the fallback form's RDN field starts at uid. A profile with
    // no RDN attribute (operator-entered DN, no template) leaves it blank.
    rdnAttribute: profileData.value ? (profileData.value.rdnAttribute || '') : 'uid',
    rdnValue: '',
    attributes,
  }
}
const form = ref<UserFormState>(emptyForm())
const userFormRef = ref<{
  validate: () => boolean
  applyMembershipChanges?: () => Promise<unknown>
  hasPendingMembershipChanges?: boolean
} | null>(null)

const recentSearchesRef = ref<{ record: (filter: string) => void } | null>(null)

function search() {
  searchMeta.value = null // a new filter starts back at one page
  recentSearchesRef.value?.record(filterText.value)
  load()
}

// Apply a chip from the Recent searches panel: drop it into the filter and
// run it (which re-records it, moving it back to the front of the list).
function applyRecentSearch(filter: string) {
  filterText.value = filter
  search()
}

/**
 * Loads the current filter. `all` re-runs it unbounded (limit 0 — the
 * server caps at its own ceiling and reports that), which is what the
 * "Load all" action in the truncation notice does. Refreshes after an
 * edit keep whichever mode the operator last chose.
 */
async function load(all: boolean = searchMeta.value?.all === true) {
  await call(async () => {
    const params = {
      filter: filterText.value || undefined,
      baseDn: profileData.value?.targetUserDn || undefined,
      limit:  all ? 0 : FETCH_LIMIT,
      // All user attributes ('*') plus the reverse group-membership
      // attributes by name, so the optional memberOf / isMemberOf columns
      // are there even where they're operational (OpenLDAP's memberof
      // overlay, OUD/OpenDJ's isMemberOf).
      attributes: includeOperational.value ? '*,+,memberOf,isMemberOf' : '*,memberOf,isMemberOf',
      // The Groups column: each user's direct and nested groups, worked out
      // on the server from the group entries the operator can see — the same
      // lookup the reports and the CSV export's allgroups column use.
      includeGroups: true,
    }
    const { data } = await usersApi.searchUsersPage(dirId, params)
    const page = (data ?? {}) as UserSearchPage
    const entries = Array.isArray(page.entries) ? page.entries : []
    users.value = entries.map((e) => {
      // Flatten every returned attribute to a string-or-string[] cell
      // value so the dynamic columns from `cols` can read them as
      // `row[attr]`. Multi-valued attrs join with ", " — matches what
      // the superadmin Directory Search results table does. The full
      // entry is preserved on `__entry` for callers that need it
      // (toggleEnabled / openMove / etc.).
      const row: Record<string, unknown> = { dn: e.dn, __entry: e }
      const attrs = e.attributes ?? {}
      for (const [attr, vals] of Object.entries(attrs)) {
        const arr = Array.isArray(vals) ? vals : (vals == null ? [] : [vals])
        row[attr] = arr.join(', ')
      }
      // Group membership for the Groups column, from the server. The
      // DNs feed GroupChips; the joined names back the quick-filter and
      // cell title so an operator can filter the table on a group name.
      const gdns = page.groups?.[e.dn] ?? []
      row.__groupDns = gdns
      row.__groups = gdns.map(rdnValue).join(', ')
      // Backwards-compat with the row.enabled lookup the
      // toggleEnabled action menu still reads. The backend doesn't
      // return an `enabled` attribute today; this stays undefined,
      // matching the prior behaviour.
      row.enabled = attrs.enabled
      return row as unknown as UserRow
    })
    searchMeta.value = {
      truncated: page.truncated === true,
      total: page.total ?? users.value.length,
      totalIsLowerBound: page.totalIsLowerBound === true,
      all,
    }
  })
}

/** "Load all" from the truncation notice: the same search, unbounded. */
async function loadAll() {
  if (loadingAll.value) return
  loadingAll.value = true
  try {
    await load(true)
  } finally {
    loadingAll.value = false
  }
}

/**
 * Streams the current filter's full result set as CSV through the
 * backend's bulk-export endpoint (rate-limited; gated by
 * BULK_EXPORT). The export honours the same filter / baseDn the
 * page is viewing, but is independent of the table's in-memory
 * pagination — it returns every matching entry, not just what's
 * loaded into the table.
 */
async function doExportUsers() {
  if (exporting.value) return
  exporting.value = true
  try {
    // The file carries the columns on screen, in their order: dn (always
    // first in the export), the visible attributes by their real names, and
    // the groups column when it's shown. IVIA enrichment columns aren't
    // directory attributes, so the raw export can't carry them.
    const attrs = exportAttributeNames(visibleColumnKeys.value, cols.value,
      ['dn', '__groups', 'actions', ...visibleColumnKeys.value.filter(isIviaAttr)])
    const params = {
      filter: filterText.value || undefined,
      baseDn: profileData.value?.targetUserDn || undefined,
      // An empty list would mean "every attribute"; ask for dn alone instead.
      attributes: attrs.length ? attrs.join(',') : 'dn',
      includeGroups: visibleColumnKeys.value.includes('__groups') || undefined,
    }
    const { data } = await exportUsersCsv(dirId, params)
    downloadBlob(data, 'users.csv')
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Export failed')
  } finally {
    exporting.value = false
  }
}

/**
 * New User. With a profile selected (the sidebar picker, or this page's
 * Profile dropdown), create through it; the dialog's header names it. Only
 * under "All", which lists several profiles' users at once, is there no
 * single profile to use, so the operator picks one.
 */
async function openCreate() {
  editingDn.value = null
  profileConfig.value = null
  const selected = allProfiles.value.find(p => p.id === selectedProfileId.value)
  if (selected) {
    await selectProfileAndCreate(selected)
  } else if (allProfiles.value.length === 1) {
    await selectProfileAndCreate(allProfiles.value[0])
  } else if (allProfiles.value.length > 1) {
    showTemplatePicker.value = true
  } else {
    form.value = emptyForm()
    showModal.value = true
  }
}

async function selectProfileAndCreate(p: ProfileLite) {
  showTemplatePicker.value = false
  try {
    const { data } = await getProfile(dirId, p.id)
    // Refresh the page's profile only when it's the one the list shows: under
    // "All", the profile picked for the new user mustn't narrow the list.
    if (p.id === selectedProfileId.value) profileData.value = data
    profileConfig.value = data
  } catch (e) {
    const err = e as { message?: string }
    console.warn('Failed to load profile:', err.message ?? e)
    profileConfig.value = null
  }
  const f = emptyForm()
  // Auto-queue group assignments defined on the profile
  if (profileConfig.value?.groupAssignments?.length) {
    f._pendingGroups = profileConfig.value.groupAssignments.map(g => ({
      dn: g.groupDn,
      memberAttr: g.memberAttribute,
    }))
  }
  form.value = f
  showModal.value = true
}

// View-only details (user.read without user.edit): the edit dialog with
// every field disabled and no Save — same layout, groups and IVIA status.
function openView(row: UserRow) {
  return openEdit(row, true)
}

async function openEdit(row: UserRow, readOnly = false) {
  viewOnly.value = readOnly
  editingDn.value = row.dn
  // Fetch full entry from LDAP to get all attributes (search results may be incomplete)
  let attrs: Record<string, string[] | string | null> = row._raw?.attributes || {}
  try {
    const { data } = await usersApi.getUser(dirId, row.dn)
    if (data?.attributes) attrs = data.attributes
  } catch (e) {
    const err = e as { message?: string }
    console.warn('Failed to fetch full user entry, using search data:', err.message ?? e)
  }

  // Resolve the profile that owns this entry — its attribute configs drive the
  // customized form (labels, input types, sections, and fields the entry
  // doesn't carry yet). Without one, UserForm falls back to raw per-attribute
  // textareas labelled with the bare LDAP names.
  profileConfig.value = null
  const ocRaw = attrs.objectClass || attrs.objectclass || []
  const ocArr = Array.isArray(ocRaw) ? ocRaw : [ocRaw]
  const userOCs = ocArr.filter((s): s is string => typeof s === 'string').map(s => s.toLowerCase())
  const match = resolveProfileForEntry(row.dn, userOCs)
  if (match) {
    try {
      const { data } = await getProfile(dirId, match.id)
      profileConfig.value = data
    } catch (e) {
      const err = e as { message?: string }
      console.warn('Failed to load profile for edit:', err.message ?? e)
    }
  }

  // The backend lower-cases all LDAP attribute names, but the profile config
  // stores them with original casing (e.g. "givenName" vs "givenname").
  // Build a lookup to re-key the attributes to the profile-configured casing
  // so that v-model bindings in edit mode find the values.
  const caseMap: Record<string, string> = {}
  if (profileConfig.value?.attributeConfigs) {
    for (const ac of profileConfig.value.attributeConfigs) {
      caseMap[ac.attributeName.toLowerCase()] = ac.attributeName
    }
  }
  form.value = { dn: row.dn, attributes: Object.fromEntries(
    Object.entries(attrs).map(([k, v]) => [
      caseMap[k.toLowerCase()] || k,
      Array.isArray(v) ? v.join('\n') : v,
    ])
  )}
  editOriginalAttributes.value = { ...form.value.attributes }
  showModal.value = true
}

async function save() {
  // Gate on the form's own client-side validation (mirrors the server rules).
  if (userFormRef.value && !userFormRef.value.validate()) return
  saving.value = true
  try {
    if (editingDn.value) {
      // Attributes that name the entry — every AVA of the DN's leading RDN,
      // including each component of a multi-valued RDN (o AND cn in
      // o=0001+cn=Jane Rowe,…) — are locked in the form and immutable via
      // MODIFY: replacing one is a server-side naming violation. Changing a
      // naming value is the rename flow's job, not save's.
      const namingAttrs = new Set(
        parseLeadingRdn(editingDn.value).map(a => a.name.toLowerCase()))
      // Canonical value list for change detection and for the wire format —
      // the same splitting/trimming the mods mapper has always applied.
      const toValueList = (val: unknown): string[] =>
        typeof val === 'string'
          ? val.split('\n').map(v => v.trim()).filter(v => v.length > 0)
          : val == null ? [] : [String(val)]
      const unchanged = (attr: string, val: unknown): boolean => {
        const before = toValueList(editOriginalAttributes.value[attr])
        const after  = toValueList(val)
        return after.length === before.length && after.every((v, i) => v === before[i])
      }
      const mods = Object.entries(form.value.attributes || {})
        .filter(([attr]) => attr.toLowerCase() !== 'objectclass')
        .filter(([attr]) => !namingAttrs.has(attr.toLowerCase()))
        // IVIA (isva.*) attributes are read-only enrichment merged from the
        // paired secUser — they aren't real attributes on the demographic
        // entry, so never write them back. They're managed via the IVIA tab.
        .filter(([attr]) => !isIviaAttr(attr))
        // Only ship what actually changed. Disabled fields (editableOnUpdate
        // = false, e.g. uid) round-trip their loaded value, and the backend
        // correctly 400s ANY modification targeting them — so an untouched
        // locked field must produce no modification at all. Also keeps a
        // save from REPLACE-ing every attribute it never touched (audit
        // noise, needless writes).
        .filter(([attr, val]) => !unchanged(attr, val))
        // A changed attribute whose new value list is empty was cleared
        // (e.g. the last line removed from a multi-valued field): send a
        // value-less DELETE, which removes the attribute. Dropping it — as an
        // empty REPLACE used to be — silently discarded the edit.
        .map(([attr, val]) => {
          const values = toValueList(val)
          return values.length
            ? { operation: 'REPLACE', attribute: attr, values }
            : { operation: 'DELETE', attribute: attr, values: [] as string[] }
        })
      // Nothing changed → no MODIFY at all: an empty modification list is
      // an LDAP protocol error, and there's nothing worth an audit row.
      if (mods.length) {
        await usersApi.updateUser(dirId, editingDn.value, { modifications: mods })
      }
      notif.success('User updated')
      // Flush any staged group-membership changes as a single batch. The form
      // owns the staged state and surfaces its own per-change result toast; a
      // thrown request propagates to the catch below, leaving the modal open
      // and the staged changes intact for retry.
      await userFormRef.value?.applyMembershipChanges?.()
    } else {
      const f = form.value
      // The DN is editable on the create form (UserForm seeds it from the
      // profile's computed default / dnTemplate, but the admin may override it).
      // Honour that value when present; fall back to composing it otherwise.
      const dn = (f.dn && f.dn.trim())
        ? f.dn.trim()
        : `${f.rdnAttribute}=${f.rdnValue},${f.parentDn}`
      const attributes: Record<string, string[]> = {}
      for (const [k, v] of Object.entries(f.attributes || {})) {
        if (!v && v !== false) continue
        // Multi-value / textarea fields are newline-separated
        if (typeof v === 'string' && v.includes('\n')) {
          attributes[k] = v.split('\n').map(s => s.trim()).filter(Boolean)
        } else {
          attributes[k] = [String(v)]
        }
      }
      // The designated RDN field's value is still an entry attribute even
      // when an overridden DN no longer uses that attribute for naming.
      const rdnAttrName = f.rdnAttribute
      if (rdnAttrName && f.rdnValue
          && !Object.keys(attributes).some(k => k.toLowerCase() === rdnAttrName.toLowerCase())) {
        attributes[rdnAttrName] = [f.rdnValue]
      }
      // Include objectClasses from the selected user template
      if (profileConfig.value?.objectClassNames?.length) {
        attributes.objectClass = profileConfig.value.objectClassNames
      }
      // Naming consistency: every AVA in the DN's leading RDN must be present
      // among the entry's attribute values or the directory rejects the add
      // with a naming violation. Derived from the actual (possibly overridden,
      // possibly multi-valued) RDN — e.g. o=0001+cn=Sanjay Mishra adds both —
      // rather than blindly injecting the profile's designated rdnAttribute.
      const createRes = await usersApi.createUser(dirId, { dn, attributes: ensureNamingValues(dn, attributes) })
      if (createRes.status === 202) {
        // Approval workflow intercepted — user creation is pending approval
        notif.success('User creation submitted for approval')
      } else {
        // Group assignments are applied server-side by UserController.create
        // (it consults the matched profile's effective group set) and the
        // 201 body reports the actual outcome. Count what really happened
        // rather than echoing the profile's intent, and surface per-group
        // failures — the entry exists either way, so a silent miss here
        // stays invisible until someone audits the group.
        const created = createRes.data as
          { groupsAdded?: number; groupWarnings?: string[] } | undefined
        const groupsAdded = created?.groupsAdded ?? 0
        const groupWarnings = created?.groupWarnings ?? []
        notif.success(groupsAdded ? `User created and added to ${groupsAdded} group(s)` : 'User created')
        if (groupWarnings.length) {
          notif.error(`Some profile group assignments failed — ${groupWarnings.join('; ')}`)
        }
      }
    }
    showModal.value = false
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: UserRow) {
  const fn = row.enabled !== false ? usersApi.disableUser : usersApi.enableUser
  await call(() => fn(dirId, row.dn), { successMsg: 'Status updated' })
  await load()
}

function openMove(row: UserRow) {
  moveTarget.value        = row
  moveDestProfileId.value = ''
  showMove.value          = true
}

async function doMove() {
  if (!moveTarget.value || !moveDestProfileId.value) return
  saving.value = true
  try {
    const res = await usersApi.moveUser(dirId, moveTarget.value.dn, { destinationProfileId: moveDestProfileId.value })
    if (res.status === 202) {
      notif.success('User move submitted for approval')
    } else {
      notif.success('User moved')
    }
    showMove.value = false
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  } finally { saving.value = false }
}

function openResetPassword(row: UserRow) {
  resetPwTarget.value  = row
  resetPwNew.value     = ''
  resetPwConfirm.value = ''
  resetPwError.value   = ''
  resetPwVisibleNew.value = false
  resetPwVisibleConfirm.value = false
  showResetPassword.value = true
}

async function doResetPassword() {
  if (!resetPwTarget.value) return
  resetPwError.value = ''
  saving.value = true
  try {
    await usersApi.resetPassword(dirId, resetPwTarget.value.dn, resetPwNew.value)
    notif.success('Password reset successfully')
    showResetPassword.value = false
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    resetPwError.value = err.response?.data?.detail || err.message || ''
  } finally {
    saving.value = false
  }
}

// ── Bulk Attribute Update ────────────────────────────────────────────────────

const showBulkUpdate = ref(false)
const bulkUpdating   = ref(false)
const bulkResult     = ref<BulkResult | null>(null)
const bulkMods       = ref<BulkMod[]>([{ operation: 'REPLACE', attribute: '', value: '' }])

const canBulkUpdate = computed(() =>
  bulkMods.value.some(m => m.attribute && m.attribute.trim())
)

function addBulkMod() {
  bulkMods.value.push({ operation: 'REPLACE', attribute: '', value: '' })
}

function openBulkUpdate() {
  bulkMods.value = [{ operation: 'REPLACE', attribute: '', value: '' }]
  bulkResult.value = null
  showBulkUpdate.value = true
}

async function doBulkUpdate() {
  bulkUpdating.value = true
  bulkResult.value = null
  try {
    const modifications = bulkMods.value
      .filter(m => m.attribute && m.attribute.trim())
      .map(m => ({
        operation: m.operation,
        attribute: m.attribute.trim(),
        values: m.value ? m.value.split('|').map(v => v.trim()).filter(Boolean) : [],
      }))
    const { data } = await usersApi.bulkUpdateAttributes(dirId, {
      dns: [...selectedDns.value],
      modifications,
    })
    bulkResult.value = data
    if (data.errors === 0) {
      notif.success(`${data.updated} user(s) updated successfully`)
      showBulkUpdate.value = false
      selectedDns.value = new Set()
      await load()
    } else {
      notif.warning(`${data.updated} updated, ${data.errors} failed`)
      await load()
    }
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  } finally {
    bulkUpdating.value = false
  }
}

// ── Bulk Group Membership ────────────────────────────────────────────────────
// Fan the staged batch endpoint (POST /users/memberships, #165) across the
// selected users: each user gets one request applying the chosen groups in the
// selected direction. Per-user outcomes are aggregated into one summary.

const showBulkMembership  = ref(false)
const bulkMembershipMode  = ref<'ADD' | 'REMOVE'>('ADD')
const bulkGroupFilter     = ref('')
const bulkGroupResults    = ref<BulkGroup[]>([])
const bulkChosenGroups    = ref<BulkGroup[]>([])
const bulkGroupSearching  = ref(false)
const bulkGroupSearched   = ref(false)
const bulkMembershipBusy  = ref(false)
const bulkMembershipResult = ref<BulkMembershipResult | null>(null)

function openBulkMembership() {
  bulkMembershipMode.value = 'ADD'
  bulkGroupFilter.value = ''
  bulkGroupResults.value = []
  bulkChosenGroups.value = []
  bulkGroupSearched.value = false
  bulkMembershipResult.value = null
  showBulkMembership.value = true
}

/**
 * Map a group search entry to a BulkGroup, resolving its membership
 * attribute. resolveGroupMembers handles the backend's lower-cased
 * attribute keys (`uniquemember`) — a camelCase lookup here resolved
 * every groupOfUniqueNames group to `member` and bulk-added against
 * the wrong attribute.
 */
function toBulkGroup(e: { dn: string, attributes?: Record<string, string[] | undefined> }): BulkGroup {
  const attrs = e.attributes || {}
  return {
    dn: e.dn,
    cn: attrs.cn?.[0] || rdnValue(e.dn) || e.dn,
    memberAttr: resolveGroupMembers(attrs).memberAttr,
  }
}

async function searchBulkGroups() {
  const q = bulkGroupFilter.value.trim()
  bulkGroupSearching.value = true
  bulkGroupSearched.value = true
  try {
    const params: Record<string, string> = {}
    if (q) params.filter = `(cn=*${q}*)`
    const { data } = await groupsApi.searchGroups(dirId, params)
    const entries = Array.isArray(data) ? data : (data?.entries || [])
    const chosenDns = new Set(bulkChosenGroups.value.map(g => g.dn.toLowerCase()))
    bulkGroupResults.value = entries
      .map(toBulkGroup)
      .filter((g: BulkGroup) => !chosenDns.has(g.dn.toLowerCase()))
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  } finally {
    bulkGroupSearching.value = false
  }
}

function addBulkGroup(g: BulkGroup) {
  if (!bulkChosenGroups.value.some(c => c.dn.toLowerCase() === g.dn.toLowerCase())) {
    bulkChosenGroups.value.push(g)
  }
  bulkGroupResults.value = bulkGroupResults.value.filter(r => r.dn.toLowerCase() !== g.dn.toLowerCase())
}

function removeBulkGroup(g: BulkGroup) {
  bulkChosenGroups.value = bulkChosenGroups.value.filter(c => c.dn.toLowerCase() !== g.dn.toLowerCase())
}

async function doBulkMembership() {
  if (!bulkChosenGroups.value.length) return
  bulkMembershipBusy.value = true
  bulkMembershipResult.value = null
  const dns = [...selectedDns.value] as string[]
  const op = bulkMembershipMode.value
  const changes = bulkChosenGroups.value.map(g => ({
    groupDn: g.dn, memberAttribute: g.memberAttr, op,
  }))

  let applied = 0, queued = 0, failed = 0
  const userFailures: Array<{ dn: string, message: string }> = []
  try {
    for (const dn of dns) {
      try {
        const { data } = await usersApi.applyMemberships(dirId, dn, { changes })
        const r = data as MembershipApplyResponse
        applied += r.applied
        queued += r.queued
        const f = r.refused + r.blocked + r.errored
        if (f) {
          failed += f
          const firstFail = r.items.find(i => i.status === 'REFUSED' || i.status === 'BLOCKED' || i.status === 'ERROR')
          userFailures.push({ dn, message: firstFail?.message || `${f} change(s) failed` })
        }
      } catch (e) {
        // A whole-request failure (e.g. out-of-scope user → 403) fails every
        // change for this user; record it and keep going for the others.
        const err = e as { response?: { data?: { detail?: string } }, message?: string }
        failed += changes.length
        userFailures.push({ dn, message: err.response?.data?.detail || err.message || 'Request failed' })
      }
    }
    bulkMembershipResult.value = { users: dns.length, applied, queued, failed, userFailures }
    if (!failed) {
      notif.success(`Memberships updated for ${dns.length} user(s) — ${applied} applied${queued ? `, ${queued} pending approval` : ''}`)
      showBulkMembership.value = false
      selectedDns.value = new Set()
      await load()
    } else {
      notif.warning(`${applied} applied, ${queued} pending, ${failed} failed across ${dns.length} user(s)`)
      await load()
    }
  } finally {
    bulkMembershipBusy.value = false
  }
}

function confirmDelete(row: UserRow) { deleteTarget.value = row; showDelete.value = true }

async function doDelete() {
  if (!deleteTarget.value) return
  await call(() => usersApi.deleteUser(dirId, deleteTarget.value!.dn), { successMsg: 'User deleted' })
  await load()
}

// ── Playbook run from user actions ────────────────────────────────────────

const showPlaybookModal = ref(false)
const availablePlaybooks = ref<Playbook[]>([])
const playbookTarget = ref<UserRow | null>(null)
const selectedPlaybookId = ref<string | null>(null)
const playbookPreview = ref<PlaybookPreview | null>(null)
const playbookExecuting = ref(false)
const playbookResult = ref<PlaybookResult | null>(null)
const showPlaybookResult = ref(false)

async function openPlaybookRun(row: UserRow) {
  playbookTarget.value = row
  selectedPlaybookId.value = null
  playbookPreview.value = null
  playbookResult.value = null
  try {
    const { data } = await listEnabledPlaybooks(dirId)
    availablePlaybooks.value = data
    showPlaybookModal.value = true
  } catch (e) {
    void e
    notif.error('Failed to load playbooks')
  }
}

async function doPlaybookPreview() {
  if (!selectedPlaybookId.value || !playbookTarget.value) return
  try {
    const { data } = await previewPlaybook(dirId, selectedPlaybookId.value, playbookTarget.value.dn)
    playbookPreview.value = data
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  }
}

async function doPlaybookExecute() {
  if (!selectedPlaybookId.value || !playbookTarget.value) return
  playbookExecuting.value = true
  try {
    const { data } = await executePlaybook(dirId, selectedPlaybookId.value, [playbookTarget.value.dn])
    playbookResult.value = data[0]
    showPlaybookModal.value = false
    showPlaybookResult.value = true
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  }
  finally { playbookExecuting.value = false }
}

async function doPlaybookRollback(executionId: string) {
  try {
    await rollbackExecution(dirId, executionId)
    notif.success('Rollback completed')
    showPlaybookResult.value = false
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  }
}

function parsedPlaybookResults(json: string): PlaybookStep[] {
  try { return JSON.parse(json) } catch { return [] }
}

async function loadProfiles() {
  try {
    const { data: profiles } = await listProfiles(dirId)
    // Picker is "which profile do I provision through" — disabled
    // profiles can't, so hide them here. Symmetric to the sidebar
    // Profile picker (/auth/me/profiles is filtered server-side)
    // and the admin dashboard's Profiles panel. Superadmin manages
    // disabled state from SuperadminProfilesView, which uses a
    // separate (unfiltered) list endpoint.
    const enabled: ProfileLite[] = profiles.filter((p: ProfileLite) => p.enabled !== false)
    allProfiles.value = enabled
    // Follow the sidebar picker when it points at one of this directory's
    // profiles: a sidebar switch remounts this view, and the reload should
    // land on the newly picked profile rather than fall back to "All".
    const picked = enabled.find((p: ProfileLite) => p.id === profilePicker.selectedId)
    if (picked) {
      selectedProfileId.value = picked.id
      profileData.value = picked
    } else if (enabled.length === 1) {
      selectedProfileId.value = enabled[0].id
      profileData.value = enabled[0]
    }
  } catch (e) {
    const err = e as { message?: string }
    console.warn('Failed to load profiles:', err.message ?? e)
  }
}

function onProfileChange() {
  const p = allProfiles.value.find((p: ProfileLite) => p.id === selectedProfileId.value)
  profileData.value = p || null
  searchMeta.value = null // a new scope starts back at one page
  load()
}

// A sidebar profile switch remounts this page. Warn first while any editor
// dialog is open — the create/edit form, bulk update, bulk membership, move,
// password reset or playbook run — since whatever was typed there is lost.
useUnsavedChangesGuard('Users', () =>
  (showModal.value && !viewOnly.value) || showBulkUpdate.value || showBulkMembership.value
  || showMove.value || showResetPassword.value || showPlaybookModal.value)

watch(includeOperational, () => load())

onMounted(async () => {
  loadSchemaNames()
  await loadProfiles()
  load()
})
</script>

<style scoped>
@reference "tailwindcss";
</style>
