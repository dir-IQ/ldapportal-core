# Accessibility (Section 508 / WCAG 2.1 AA) remediation plan

**Status:** Not started (assessment complete 2026-05-25).
**Standard:** U.S. Section 508 (2018 ICT Refresh), which incorporates
**WCAG 2.1 Level AA** by reference. That is the conformance target.
**Owner:** Frontend.
**Scope:** `frontend/` (Vue 3 SPA). Backend API responses are JSON and out
of scope except where they carry user-facing text used as an accessible name.

This plan maps every finding from the 2026-05-25 audit to the files that
carry it, an effort estimate, and a fix approach, then sequences the work
into reviewable phases. Each **phase is one branch / one PR**.

## Method

All 157 `.vue` components plus `assets/main.css` and `index.html` were
scanned for the WCAG 2.1 AA success criteria that static review can detect
(1.1.1, 1.3.1, 1.4.1, 1.4.3, 1.4.11, 2.1.1, 2.4.1, 2.4.3, 2.4.7, 3.1.1,
3.3.2, 4.1.2, 4.1.3). Contrast (1.4.3/1.4.11) and focus-management
(2.4.3/2.4.7) were confirmed by reading the shared CSS and the shared
modal/table/toast components. Criteria that require a running build + AT
(e.g. 1.4.10 reflow, 2.4.7 visible-focus on every control, 1.3.2 meaningful
sequence) are deferred to the axe-core + manual pass in Phase 6.

## Leverage points (fix once, cover many)

| Component | Uses | Current state | What it still needs |
|---|---|---|---|
| `components/AppModal.vue` | 21 | has `role="dialog"`, `aria-modal` | `aria-labelledby` → title, focus trap + restore, Esc-to-close, labeled close button |
| `components/ConfirmDialog.vue` | 19 | — (verify) | same dialog semantics as AppModal |
| `components/DataTable.vue` | 10 | keyboard row-nav | `scope="col"` on `<th>`, accessible names on row/all checkboxes, `aria-selected` on focused row |

Fixing the three shared components is Phase 2 and removes the bulk of the
dialog + table debt before the long tail of bespoke instances.

---

## Phase 1 — Level-A quick wins (low risk, mostly template-only)

Highest impact per line; no behavior change. **Effort: ~half a day.**

| Finding | WCAG | Files | Fix |
|---|---|---|---|
| Empty page language | 3.1.1 (A) | `index.html:2` | `<html lang="en">` |
| No skip-to-content link | 2.4.1 (A) | `components/AppLayout.vue`, `layouts/SelfServiceLayout.vue`, `ee/views/auditor/AuditorLayout.vue` | add a visually-hidden "Skip to main content" link as first focusable child, target `<main id="main">` |
| Toasts not announced | 4.1.3 (AA) | `components/NotificationToast.vue:3` | wrap container in `role="region" aria-label="Notifications"`; per-toast `role` = `alert` (error) / `status` (others); container `aria-live` matching |
| Skew + license banners silent | 4.1.3 (AA) | `AppLayout.vue:258`, `LicenseExpirationBanner.vue` | `role="status"` (error banner → `role="alert"`) |

> **Correction (verified in code):** the audit's "nav active state
> color-only" finding was a false positive from static grep. The sidebar
> uses vue-router `<RouterLink>`, which emits `aria-current="page"` on the
> exact-active link by default — `aria-current` appears at runtime, not in
> the template. No change needed; confirm in the Phase 6 AT pass.

**Acceptance:** keyboard Tab from page load reaches "skip to main" first;
triggering a toast is announced by VoiceOver/NVDA; `lang` present.

**Status: Done (2026-05-25).** `index.html` `lang="en"`; skip-to-content
links + `id="main-content"` in `AppLayout`, `SelfServiceLayout`,
`AuditorLayout`; `NotificationToast` is a labeled region with per-toast
`role` (`alert` for errors, `status` otherwise); skew + license banners
carry `role`. Build + 330 unit tests green.

## Phase 2 — Shared dialog + table semantics

Fix the three leverage components. **Effort: ~1 day.**

- **`AppModal.vue`**: generate an `id` for the title, set
  `aria-labelledby`; add a focus trap (focus first focusable on open, cycle
  Tab within, `@keydown.esc` to emit close), restore focus to the invoking
  element on close; `aria-label="Close"` on the × button. (2.4.3, 2.1.2,
  4.1.2)
- **`ConfirmDialog.vue`**: same dialog semantics; ensure the confirm/cancel
  buttons have discernible names and initial focus lands on the safe action.
- **`DataTable.vue`**: `scope="col"` on every `<th>`; `aria-label` on the
  select-all and per-row checkboxes (e.g. "Select <row name>");
  `aria-selected` on the focused/selected row; give the keyboard-nav
  container an `aria-label`. (1.3.1, 4.1.2)

**Acceptance:** every `AppModal`/`ConfirmDialog` instance traps focus, closes
on Esc, returns focus; `DataTable` headers and checkboxes have names.

**Status: Done (2026-05-25).** Added `composables/useDialogA11y.ts` (focus
trap, Esc-to-close, focus restore). `AppModal` and `ConfirmDialog` converted
to `<script setup lang="ts">`, given `aria-labelledby` (via `useId()`),
backdrop/Esc close, and a labeled close button; both now consume the
composable. `DataTable` headers carry `scope="col"` and the select-all /
per-row checkboxes have accessible names. Typecheck + build + 330 unit tests
green. Runtime focus-trap behaviour will be confirmed by the axe gate +
manual AT pass (Phase 6). `aria-dialog-name` stays in the axe allowlist until
Phase 3 retires it from the bespoke modals.

## Phase 3 — Bespoke modals (not using AppModal)

13 hand-rolled `fixed inset-0` overlays need the same treatment as Phase 2,
or migration to `AppModal`. Prefer migration where the markup is simple.
**Effort: ~1.5 days.**

Files: `UserPreferencesDialog.vue`, `KeyboardShortcutsHelp.vue`,
`CommandPalette.vue`, `DnPicker.vue`, `DirectorySearchView.vue`,
`DirectoryBrowserView.vue`, `ee/views/drift/AccessDriftView.vue`,
`ee/views/sodPolicies/SodViolationsView.vue`,
`ee/views/superadmin/AlertDashboardView.vue`,
`ee/components/identity/MappingDrawer.vue`,
`ee/components/identity/IdentityMappingHelpDialog.vue`,
`ee/views/auditor/AuditorLayout.vue` (mobile nav + verify drawer),
`ee/views/auditor/components/AuditorNotesDrawer.vue`.

Each gets: `role="dialog"` + `aria-modal="true"` + `aria-labelledby`, focus
trap/restore, Esc-to-close, labeled close button. Drawers use the same
pattern. (2.4.3, 2.1.2, 4.1.2)

**Status: Done (2026-05-25).** Rather than migrate each to `AppModal` (which
would touch every view's `<script>` and so force a whole-view TS conversion
per the repo convention), added a template-only directive
`directives/dialogA11y.ts` (`v-dialog-a11y`, registered in `main.js`) that
owns focus trap + restore + initial focus. Every bespoke overlay now carries
`role="dialog"` + `aria-modal` + `aria-labelledby`/`aria-label`, the
directive, a template `@keydown.escape` to its own close expression, and a
labeled close button: `UserPreferencesDialog`, `KeyboardShortcutsHelp`,
`CommandPalette`, `DnPicker`, the `AppLayout` no-profiles modal,
`DirectorySearchView`, `DirectoryBrowserView` (delete/move/rename),
`AccessDriftView` (exempt/rules), `SodViolationsView` (exempt/user-detail),
`AlertDashboardView`, `AuditorNotesDrawer`, `AuditorLayout` (mobile nav +
verify drawer); `IdentityMappingHelpDialog` and `MappingDrawer` already had
the ARIA and gained the trap. Reusable `AppModal`/`ConfirmDialog` keep the
Phase-2 composable. Typecheck + build + 330 unit tests green. Note: the axe
gate scans page-load state and doesn't open modals, so `aria-dialog-name`
stays in `ACCEPTED_RULES`; dialog focus/announcement is verified in the
Phase 6 manual AT pass.

## Phase 4 — Form label association

The largest finding. 72 files render inputs; labels are visually present but
not programmatically tied (sibling `<label>`, no `for`/`id`). **Effort: ~3–4
days, batched by area.**

Approach (per field): give the control an `id`, point the `<label for>` at
it; where a placeholder is the only label, add a real `<label>` (visually
hidden if the design needs it) and keep the placeholder as a hint, or add
`aria-label`. Convert touched plain `<script setup>` blocks to
`lang="ts"` per CLAUDE.md.

Batch by directory so each PR is reviewable:

1. `views/users/`, `views/groups/`, `views/profiles/` (incl. the 37-field
   `SuperadminProfilesView.vue`)
2. `views/superadmin/` (directories, discovery, ISVA, admin users, search)
3. `views/settings/`, `views/selfservice/`, `views/bulk/`, `views/playbooks/`,
   `views/reports/`, `views/approvals/`, `views/SetupWizardView.vue`
4. `ee/views/**` + `ee/components/**`
5. shared form components (`CreateEntryForm.vue`, `LdapFilterBuilder.vue`,
   `LdifImportModal.vue`, `UserPreferencesDialog.vue`)

**Key leverage (confirmed 2026-05-25):** the shared `components/FormField.vue`
wrapper — used for the majority of "normal" form fields app-wide — is
**already accessible** (`<label :for="id">` + control `:id="id"`, unique
default id). So Phase 4's real scope is only the *raw* controls that bypass
FormField, plus other shared field wrappers. Most are template-only
`for`/`id` (or `aria-label` for search boxes / repeating-row controls), so no
`<script>` is touched and no TS conversion is triggered.

**Partial — Batch 1 (2026-05-25):**
- `SelfServiceLoginView.vue` / `RegisterView.vue` directory selects (earlier,
  via the local axe scan).
- Shared `components/DnPicker.vue`: its browse input gained `aria-label` (was
  placeholder-only) — fixes every DnPicker usage at once.
- `views/users/UserForm.vue` (password input + group-search box),
  `views/users/UserListView.vue` (profile/playbook selects, search, password
  reset fields, bulk-modify rows via `aria-label`),
  `views/groups/GroupListView.vue` (profile select, filter, bulk textarea).
- `components/profiles/IsvaProfileOverrideControl.vue`: already correct
  (wrapping `<label>`), no change.

**Partial — Batch 1b / profiles (2026-05-25):**
`views/profiles/SuperadminProfilesView.vue` (37 controls) fully labelled —
`for`/`id` on the singleton fields (directory, name, description, password
length/special, approver mode, clone name), unique `for`/`id` per row on the
repeating attribute-config grid (custom label, input type, default value,
computed expression, validation regex, allowed values) and group-assignment
rows, and `aria-label` on the object-class picker, RDN select, and the
compliance-table row checkboxes. Checkboxes throughout were already
wrapping-label associated.

Remaining: Batches 2–5. The axe `label` / `select-name` /
`aria-input-field-name` rules stay in `ACCEPTED_RULES` until *all* raw
controls are labelled app-wide.

**Partial — Batch 2 / `views/superadmin/*` (2026-05-25):** labelled the raw
controls across `AuditSourcesView`, `DirectoriesManageView`,
`DirectoryBrowserView`, `DirectoryScopedWrapper`, `IntegrityCheckView`,
`DirectorySearchView`, `DiscoveryWizardView`, `AdminUsersView`,
`EntraBrowserView` — `for`/`id` on singleton selects/inputs, `aria-label` on
search boxes and repeating table/row controls (per-profile role selects,
feature-override selects, discovery attribute rows). `IsvaConfigView` was
already clean. Remaining: Batches 3–5.

**Partial — Batch 3 (2026-05-25):** labelled the remaining raw controls in
`SetupWizardView`, `BulkView`, `PlaybooksView`, `ReportJobsView`,
`PendingApprovalsView`, the self-service views (`RegisterView`,
`RegistrationStatusView`, `SelfServiceGroupsView`, `SelfServiceLoginView`,
`SelfServicePasswordView`, `SelfServiceProfileView`) and the settings sections
(`SettingsSidebar`, `AuthenticationSection`, `BrandingSection`, `SiemSection`).
`for`/`id` on singleton + `v-for`-keyed fields; `aria-label` on search/file
inputs, colour pickers and repeating step/column rows. Remaining: Batch 4
(`ee/**`) and Batch 5 (validation-error association).

**Partial — Batch 4 / `ee/**` (2026-05-25):** labelled the raw controls across
the auditor views (`AuditorApprovals`, `AuditorAuditEvents`, `AuditorLinksView`,
`AuditorSod`, `AuditorNotesDrawer`, `CrossCampaignReportView`), drift/SoD/HR
(`AccessDriftView`, `SodPoliciesView`, `SodViolationsView`, `HrConnectionView`)
and reports/superadmin (`AuditReportsView`, `AccessReviewsView`,
`AlertDashboardView`, `AlertRulesView`). `for`/`id` on labelled + `v-for`-keyed
fields; `aria-label` on filter/search controls and repeating rule-param rows.
Pre-existing `v-dialog-a11y` modal attributes left untouched.

**Label association is now complete across the app (Batches 1–4).**

**Ratchet tightened (2026-05-25):** `label`, `select-name` and
`aria-input-field-name` removed from `ACCEPTED_RULES` — the axe gate now
**enforces** them. Locally re-verified the reachable (unauthenticated) routes
`/login`, `/self-service/login`, `/register`, `/setup` with headless
Chromium + axe-core: **0 violations** (live-confirms the Batch-3 setup /
register / self-service-login label work). The authenticated routes can't run
locally (no Docker), so this ratchet change is the live verification of their
label coverage — the CI axe job (which boots the backend) will now fail if any
control on a scanned superadmin route is unlabelled.

Next: Batch 5 — associate validation errors with their field via
`aria-describedby` + `aria-invalid` (3.3.1).

Also in this phase: associate validation errors with their field via
`aria-describedby` + `aria-invalid` (3.3.1). (1.3.1, 3.3.2, 3.3.1, 4.1.2)

## Phase 5 — Names, contrast, and color-only signals

**Effort: ~2 days.**

- **Icon-only buttons** (503 buttons, 25 labeled): add `aria-label` to every
  control whose only content is an icon/`&times;`/glyph. Sweep all `.vue`.
  (1.1.1, 4.1.2)
- **Non-interactive click handlers** (2.1.1): the one real defect is
  `dashboard/SuggestedConfigurationPanel.vue:35` (clickable `<div>` →
  `router.push`); make it a `<button>`/`<router-link>`. Backdrop
  `@click.self` handlers are acceptable (each modal also has a button close,
  fixed in Phases 2–3).
- **Contrast** (1.4.3): audit the 296 `text-gray-400` usages; promote real
  text to `text-gray-500`/`600` (≥4.5:1). Leave genuinely decorative/disabled
  uses. Fix the `.input-sm` focus ring (`#94a3b8`, <3:1 — 1.4.11) and add a
  `:focus-visible` style to `.btn-*` in `assets/main.css`.
- **Color-only status** (1.4.1): the 123 red/green/amber text badges need a
  non-color cue (icon or text label) where color is the sole carrier of
  meaning.

**Partial — icon-only button/link names (2026-05-25):** swept all `.vue`;
labelled the 13 true `button-name` violations (icon-only buttons with no text,
`aria-label`, or `title`) — tree toggles, chip-remove ×, help icon, dashboard
move up/down, drawer/nav close, etc. Icon-only `<a>`/`<RouterLink>` were
already clean (0). **`button-name` and `link-name` removed from
`ACCEPTED_RULES` — now enforced** by the axe gate. Still pending in this phase:
non-interactive click handler (`SuggestedConfigurationPanel.vue`), contrast
(`text-gray-400`, `.input-sm` ring, `.btn-*` focus-visible), color-only badges
— `color-contrast` stays allowlisted until the contrast pass lands.

**Partial — interactive/focus (2026-05-25):** `SuggestedConfigurationPanel`'s
clickable `<div>` is now a `<RouterLink>` (keyboard-accessible, semantically
correct) — the last `2.1.1` defect; converted that component to TS. Also
confirmed the **focus indicators are already handled globally**:
`assets/main.css` has `*:focus-visible` and `button:focus-visible` rules
(`2px solid #334155`), so the planned `.btn-*` `:focus-visible` / `.input-sm`
ring items are moot — keyboard focus is already visible with adequate
contrast. Remaining in Phase 5: `text-gray-400` text contrast (1.4.3) and
color-only status badges (1.4.1); `color-contrast` stays allowlisted until
the text-contrast pass lands (best verified by the axe gate's real
contrast computation).

**Partial — color-only signals (2026-05-25):** audited the red/green/amber
indicators against 1.4.1. The large majority already pair colour with a
non-colour cue — the status **word** ("Enabled/Disabled", "Yes/No", severity
label, the count value), **font-weight** + tinted row (unread notifications),
**presence/absence** of an icon (settings "unsaved" dot, with a `title`), or
adjacent text — so they are compliant. Two genuine fixes: `DirectoriesPanel`'s
enabled dot was green-vs-grey with no shape/text difference for sighted users
→ now filled-green vs hollow-ring (shape cue); and the `SelfServicePasswordView`
requirements checklist used HTML-entity string literals (`'&#10003;'`) inside
`{{ }}`, which render as literal text rather than ✓/✗ → switched to real
`✓`/`✗` glyphs so the met/unmet cue is non-colour. (Note: 1.4.1 is not
reliably axe-detectable, so it stays a manual check — no ratchet change.)
**Done — text contrast (2026-05-25):** `text-gray-400` (#9ca3af, ~2.5:1 on
white — fails 1.4.3) was used 296× and *never* with a `dark:`/`hover:`
variant, so it was a clean token swap → `text-gray-500` (#6b7280, ~4.8:1 on
white). Lines where the text sits on a tinted background (`bg-gray-100/200/300`)
were bumped to `text-gray-600` instead (~6:1) since `gray-500` is borderline
there. Also fixed the dark sidebar (`.nav-section-toggle`, `.nav-header`,
inline labels, the build-SHA footer) which the light-bg-assuming swap had
regressed to dark-on-dark → now `text-white/70`.

**`color-contrast` NOT yet enforced — remains allowlisted (2026-05-25).**
Removing it from `ACCEPTED_RULES` and running the CI axe job (real backend,
authenticated routes) surfaced ~49 **systemic, pre-existing** contrast
failures that the text-gray swap neither caused nor fully resolves, and that
need a deliberate, eyes-on-screen design pass rather than blind CI-driven
edits:
- **Muted text on the `bg-gray-100` app background** — page-header subtitles
  etc. use `text-gray-500` directly on the gray-100 main bg (~4.4:1, just
  under 4.5). Systemic; the fix is a scheme decision (lighten the main bg, or
  darken muted text app-wide).
- **`text-green-600` status text** (~3.9:1 on white) → needs `green-700`.
- **Tinted stat cards** (dashboard `bg-green-50/30` cards + labels, `dt`
  metric labels; access-drift `border-2 p-5` cards).
- **`<code>` on `bg-gray-100`** (identities), a couple of empty-state `<p>`.

So Phase 5 enforced `button-name`/`link-name` and made substantial contrast
*improvements* (text-gray-400 retired app-wide, sidebar fixed, faint footer
fixed), but **full `color-contrast` enforcement is the one remaining a11y
item** and is tracked here. `aria-dialog-name`/`aria-required-children` stay
allowlisted only because the scan doesn't open modals (Phase 6 manual AT).

### Local automated-scan capability (validated 2026-05-25)

A headless-Chromium + axe-core scan **does** run in the dev sandbox: the
pre-installed Playwright chromium (`/opt/pw-browsers`) launches via an
`executablePath` override (the Playwright CDN is blocked by the network
policy, but the npm registry is reachable so `axe-core` installs). A scan of
the four unauthenticated routes (`/login`, `/self-service/login`,
`/register`, `/setup`) ran clean on contrast and structure **except** two
`select-name` violations (directory pickers in `SelfServiceLoginView` and
`RegisterView`), now fixed and re-scanned green.

**Limitation:** Docker is unavailable in the sandbox, so the real Spring
backend (and thus every authenticated view — where the label-association and
table-scope debt concentrates) cannot render locally. Full automated
coverage therefore **must** run in CI, where the e2e harness brings up the
backend and the existing auth fixtures reach the authenticated routes. That
is the core reason the axe spec below is a CI deliverable, not a local one.
True screen-reader passes remain a human step on a real OS regardless.

## Phase 6 — Tooling gate + verification

Lock in the gains so they don't regress. **Effort: ~1 day.**

- Add `eslint-plugin-vuejs-accessibility` (recommended ruleset) to the
  frontend lint config; fix or baseline residual warnings.
- Add an `@axe-core/playwright` smoke spec that loads the main authenticated
  routes and asserts zero critical/serious violations; tag `@a11y`.
- Add `prefers-reduced-motion` handling: gate the toast slide + modal
  transitions behind the media query in `assets/main.css`. (2.3.3 / motion)
- Manual AT pass (VoiceOver + NVDA) over the golden paths: login, user CRUD,
  bulk, a modal-heavy flow, a data table. Record results here.

**Partial (2026-05-25):** the **axe-core gate is wired**. Added
`@axe-core/playwright`, `frontend/tests/e2e/spec/a11y.spec.ts` (tag `@a11y`,
`npm run e2e:a11y`) scanning ten dense superadmin routes, and
`.github/workflows/a11y.yml` (guard job + backend boot, mirrors e2e-smoke).
The spec uses a **rule-level allowlist ratchet** (`ACCEPTED_RULES`): green
today, fails on any *new* violation class. As each phase fixes a rule across
the app, delete it from `ACCEPTED_RULES` to enforce it; when the set is empty
the check is a full AA gate and should be **promoted to a required status
check** (a repo branch-protection setting).

**Tooling done (2026-05-25):**
- **`eslint-plugin-vuejs-accessibility`** wired: `eslint.config.js` (flat,
  a11y-only, TS-parser for `<script lang="ts">`), `npm run lint:a11y`, and a
  `Frontend (a11y lint)` CI job. Error-level rules (alt-text, aria-*,
  heading/anchor content, role-*, no-autofocus, tabindex-no-positive, …) are
  clean and now block. The rules that fire on intentional patterns or that the
  runtime axe gate already covers authoritatively are warn-only with a
  documented rationale: `form-control-has-label` (axe credits dynamic
  `:aria-label`; this static rule can't), `no-static-element-interactions` /
  `click-events-have-key-events` (the `@click.self` modal-backdrop pattern,
  already Esc- + button-dismissable), `mouse-events-have-key-events`
  (hold-to-reveal-password), `label-has-for` (deprecated/over-strict) off.
  163 advisory warnings remain as a backlog; 0 errors.
- **`prefers-reduced-motion`** handling added to `assets/main.css` — a global
  `@media (prefers-reduced-motion: reduce)` reset that near-zeroes
  animation/transition durations (toasts, modal/drawer enters, spinners,
  fade-ins) and disables smooth scroll. (2.3.3 / motion)

**Remaining (human / repo-admin steps I can't do here):**
- **Manual AT pass** (VoiceOver + NVDA) over the golden paths — needs a real
  OS; record results below.
- **Promote the axe check + a11y lint to required** status checks in branch
  protection (a GitHub setting).
- The **`color-contrast` design pass** (Phase 5 remaining) before that rule
  can leave `ACCEPTED_RULES`.

---

## Sequencing rationale

1 → 2 → 6(tooling only) early, so the lint/axe gate is catching regressions
*before* the long-tail Phases 3–5 land. Suggested order: **1, 2, then the
ESLint+axe parts of 6, then 3, 4, 5, then the manual-AT part of 6.**

## Definition of done

- `npm run lint` passes with the a11y plugin enabled (no disabled rules
  except documented, justified exceptions).
- The `@a11y` Playwright spec is green (0 critical/serious axe violations on
  covered routes).
- Manual AT pass over golden paths recorded with no blocking issues.
- This document's per-phase status boxes all read **Done**.

## Per-phase status

- [x] Phase 1 — Level-A quick wins (2026-05-25)
- [x] Phase 2 — Shared dialog + table semantics (2026-05-25)
- [x] Phase 3 — Bespoke modals (2026-05-25)
- [ ] Phase 4 — Form label association
- [ ] Phase 5 — Names, contrast, color-only signals
- [ ] Phase 6 — Tooling gate + verification
