# UX Component Integration Guide

Several UX components require explicit opt-in when building new pages or modifying existing ones. This guide explains how to use each one.

---

## 1. DN Preview Popover (`DnPreview`)

**What it does:** Shows a hover popover with user details (name, email, groups) when hovering over a DN string.

**When to use:** Anywhere a DN or username is displayed — audit logs, approval lists, access review decisions, group member lists.

**How to integrate:**

```vue
<script setup>
import DnPreview from '@/components/DnPreview.vue'
</script>

<template>
  <!-- Wrap any DN display in DnPreview -->
  <DnPreview :dn="row.memberDn" :directory-id="dirId">
    <code class="text-xs">{{ row.memberDn }}</code>
  </DnPreview>
</template>
```

**Props:**
- `dn` (String, required) — the full DN to look up
- `directoryId` (String) — the directory UUID for the API lookup. If omitted, falls back to parsing the CN from the DN string (no API call).

**Behavior:**
- 400ms hover delay prevents flicker
- Results are cached per DN for the session
- Falls back gracefully if the API lookup fails
- Popover appears below the element, repositioned to stay within viewport

---

## 2. Help Tooltip (`HelpTip`)

**What it does:** Renders a small "?" icon that shows a tooltip on hover explaining a non-obvious setting or field.

**When to use:** Next to field labels in settings pages, profile configuration, SoD policy forms, playbook step configuration — anywhere a user might wonder "what does this do?"

**How to integrate:**

```vue
<script setup>
import HelpTip from '@/components/HelpTip.vue'
</script>

<template>
  <label class="text-sm font-medium text-gray-700">
    Sync Schedule (Cron)
    <HelpTip text="A cron expression defining when the HR sync runs automatically. Example: '0 0 * * * ?' runs daily at midnight." />
  </label>
  <input v-model="form.syncCron" class="input w-full" />
</template>
```

**Props:**
- `text` (String, required) — the tooltip content

**Behavior:**
- Tooltip appears above the "?" icon on hover
- Positioned with a caret pointer
- 256px max width, wraps text automatically
- No click interaction — hover only

---

## 3. Saved Filters (`useSavedFilters` + `SavedFilterBar`)

**What it does:** Lets users save named filter configurations that persist across page reloads via localStorage.

**When to use:** Any page with filter controls — audit log, user list, approval list, SoD violations, access drift findings.

**How to integrate:**

```vue
<script setup>
import { useSavedFilters } from '@/composables/useSavedFilters'
import SavedFilterBar from '@/components/SavedFilterBar.vue'

// Define the page key (unique per page) and default filter values
const { savedViews, currentFilters, saveView, loadView, deleteView, resetFilters }
  = useSavedFilters('audit-log', {
    action: '',
    from: '',
    to: '',
    search: '',
  })
</script>

<template>
  <!-- Place the bar near the filter controls -->
  <SavedFilterBar
    :views="savedViews"
    @save="saveView"
    @load="loadView"
    @delete="deleteView"
  />

  <!-- Bind filter inputs to currentFilters -->
  <input v-model="currentFilters.search" placeholder="Search..." class="input" />
  <select v-model="currentFilters.action" class="input">
    <option value="">All Actions</option>
    <!-- ... -->
  </select>
</template>
```

**`useSavedFilters(pageKey, defaultFilters)` returns:**
- `savedViews` — computed array of `{ name, filters, savedAt }`
- `currentFilters` — reactive object bound to filter inputs
- `saveView(name)` — saves current filters under the given name
- `loadView(name)` — restores filters from a saved view
- `deleteView(name)` — removes a saved view
- `resetFilters()` — resets to default values

**Storage:** localStorage key is `saved-filters:{pageKey}`. Each page gets its own namespace.

---

## 4. Dashboard Widget Customization (`useDashboardLayout` + `DashboardCustomizer`)

**What it does:** Lets users show/hide and reorder dashboard widgets, persisted to localStorage.

**When to use:** Only on the superadmin dashboard (`DashboardView.vue`).

**How to integrate:**

```vue
<script setup>
import { useDashboardLayout } from '@/composables/useDashboardLayout'
import DashboardCustomizer from '@/components/DashboardCustomizer.vue'

const { widgets, visibleWidgets, editMode, toggleWidget, moveUp, moveDown, resetLayout }
  = useDashboardLayout()
</script>

<template>
  <!-- Customize button in the page header -->
  <button @click="editMode = !editMode" class="btn-secondary text-xs">
    {{ editMode ? 'Done' : 'Customize' }}
  </button>

  <!-- Customizer panel (shows when editMode is true) -->
  <DashboardCustomizer
    :widgets="widgets"
    :edit-mode="editMode"
    @toggle="toggleWidget"
    @move-up="moveUp"
    @move-down="moveDown"
    @reset="resetLayout"
    @close="editMode = false"
  />

  <!-- Render widgets conditionally based on visibility -->
  <template v-for="w in visibleWidgets" :key="w.id">
    <CompliancePostureCards v-if="w.id === 'compliance'" />
    <AggregateMetrics v-else-if="w.id === 'metrics'" />
    <ApprovalAging v-else-if="w.id === 'approvals'" />
    <ActiveCampaigns v-else-if="w.id === 'campaigns'" />
    <DirectoriesTable v-else-if="w.id === 'directories'" />
    <RecentActivity v-else-if="w.id === 'activity'" />
    <MyNotifications v-else-if="w.id === 'notifications'" />
    <MyPendingReviews v-else-if="w.id === 'reviews'" />
  </template>
</template>
```

**Adding a new widget:**

1. Add it to `DEFAULT_WIDGETS` in `useDashboardLayout.js`:
   ```js
   { id: 'my-new-widget', label: 'My New Widget', visible: true, order: 8 },
   ```

2. Add the corresponding `v-else-if` in the dashboard template

3. Users who have previously customized their layout will see the new widget appear at the end (the composable merges saved preferences with new defaults)

**Storage:** localStorage key is `dashboard-layout`.

---

## 5. Keyboard Table Navigation (DataTable)

**What it does:** Arrow keys, Enter, Space, and Escape for navigating and interacting with table rows.

**When to use:** Already built into the shared `DataTable` component. Any page using `DataTable` gets it automatically.

**To handle row selection via keyboard or click:**

```vue
<DataTable
  :columns="cols"
  :rows="data"
  @row-click="handleRowClick"
>
  <!-- cell slots as usual -->
</DataTable>
```

The `row-click` event fires on both mouse click and Enter key when a row is focused.

---

## Summary: What's Automatic vs Opt-In

| Feature | Automatic | Opt-in |
|---------|-----------|--------|
| Breadcrumbs | Yes — derives from route | — |
| Command palette (Cmd+K) | Yes — derives from router | — |
| Page transitions | Yes — applied in AppLayout | — |
| Keyboard table navigation | Yes — built into DataTable | — |
| Improved toasts | Yes — via notification store | — |
| DN preview popovers | — | Wrap DNs in `<DnPreview>` |
| Help tooltips | — | Add `<HelpTip>` next to labels |
| Saved filters | — | Call `useSavedFilters()` + add `<SavedFilterBar>` |
| Dashboard customization | — | Integrate into DashboardView only |
