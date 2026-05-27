# Accessibility (Section 508 / WCAG 2.1 AA) — updated status for `ldapportal-core`

**Status:** In progress (Phases 1–3 shipped; 4–6 open, 2026-05-27).
**Date of this assessment:** 2026-05-27.
**Reviewer:** Frontend (code audit against the 2026-05-25 remediation plan).
**Standard / target:** U.S. Section 508 (2018 ICT Refresh) → **WCAG 2.1 Level AA**.
**Scope:** `frontend/` (Vue 3 SPA) in this repository.

This document re-checks the prior remediation plan
(`docs/accessibility-508-remediation.md`) against the **actual code in this
repo** and records what is genuinely complete and what is still pending.

## Important scope note: core vs. the superset the plan was written against

The original plan was authored against a **superset** of this codebase
(157 `.vue` components, including an `ee/` enterprise tree: auditor, drift,
SoD, identity-mapping, alert-dashboard, etc.).

**This repo is the community/core edition:**

- **104 `.vue` components** (not 157).
- **No `src/ee/` source.** The enterprise seam is stubbed via
  `src/ee-shim.js` (vite aliases `@/ee` → the inert stub in community
  builds). So every `ee/**` file the plan enumerates — `AuditorLayout`,
  `AccessDriftView`, `SodViolationsView`, `AlertDashboardView`,
  `MappingDrawer`, `IdentityMappingHelpDialog`, `AuditorNotesDrawer`,
  the auditor/HR/cross-campaign views — **is out of scope here** (absent
  from the bundle).
- Consequently the unit-test count (plan cites "330") and the
  lint-warning count (plan cites "163") differ for core (see below).

Where the plan's status boxes say "Done", I verified the corresponding code
**that exists in core**; EE-only sub-items are marked N/A.

## Headline

| Phase | Plan said | **Verified core status** |
|---|---|---|
| 1 — Level-A quick wins | Done | **Done (verified)** |
| 2 — Shared dialog + table semantics | Done | **Done (verified)** |
| 3 — Bespoke modals | Done | **Done (verified, core subset)** |
| 4 — Form label association | "complete across Batches 1–4" | **Partial — real gaps remain (see below)** |
| 5 — Names / contrast / color-only | Partial | **Partial — `color-contrast` still open** |
| 6 — Tooling gate + verification | Partial | **Partial — and the runtime axe gate is NOT wired into CI in core** |

Build, type-check, and the a11y lint all pass in core today
(`npm run build`, `npm run type-check`, `npm run lint:a11y` → **0 errors**,
92 advisory warnings).

---

## Phase 1 — Level-A quick wins — **Done (verified)**

- `index.html:2` → `<html lang="en">`. ✔
- Skip-to-content link as first focusable child, target `#main-content`, in
  `components/AppLayout.vue:4` (`main` at `:259`) and
  `layouts/SelfServiceLayout.vue:4` (`main` at `:44`). ✔
  - *(`ee/views/auditor/AuditorLayout.vue` from the plan: N/A — not in core.)*
- `components/NotificationToast.vue`: container `role="region"
  aria-label="Notifications"` (`:4`), per-toast `:role` = `alert` (error) /
  `status` (otherwise) (`:9`), labeled dismiss button (`:42`). ✔
- Banners carry `role`: `AppLayout.vue:263` skew banner `role="status"`;
  `LicenseExpirationBanner.vue:5` `:role` = `alert`/`status`. ✔

## Phase 2 — Shared dialog + table semantics — **Done (verified)**

- `composables/useDialogA11y.ts` exists (focus trap / Esc-to-close / focus
  restore). ✔
- `components/AppModal.vue`: `role="dialog"` + `aria-modal="true"` +
  `:aria-labelledby="titleId"` (`useId()`, `:7`/`:47`), labeled `Close`
  button (`:15`), consumes `useDialogA11y` (`:34`/`:50`). ✔
- `components/ConfirmDialog.vue`: same dialog semantics + `useId()` title +
  composable (`:6`/`:55`/`:58`). ✔
- `components/DataTable.vue`: `scope="col"` on headers (`:10`,`:17`,`:20`),
  `aria-label="Select all rows"` (`:12`) and per-row
  `:aria-label="Select row …"` (`:47`). ✔

## Phase 3 — Bespoke modals — **Done (verified, core subset)**

- `directives/dialogA11y.ts` exists and is registered in `main.js:20` as
  `v-dialog-a11y` (imported `:14`). ✔
- Every hand-rolled overlay **present in core** carries `v-dialog-a11y`:
  `AppLayout.vue` (no-profiles modal), `CommandPalette.vue`, `DnPicker.vue`,
  `KeyboardShortcutsHelp.vue`, `UserPreferencesDialog.vue`,
  `superadmin/DirectoryBrowserView.vue`, `superadmin/DirectorySearchView.vue`. ✔
- Audit of all 9 `fixed inset-0` overlays: the only two without the directive
  are `AppModal.vue`/`ConfirmDialog.vue`, which intentionally use the Phase-2
  composable instead. No uncovered overlay remains. ✔
- The plan's EE overlays (AccessDrift / SodViolations / AlertDashboard /
  Auditor* / identity Mapping*) are **N/A** — not in core.

## Phase 4 — Form label association — **Partial (the plan overstates this for core)**

The shared `components/FormField.vue` wrapper is accessible
(`<label :for="id">` + control `:id="id"`, `:4`/`:10`/…). ✔ And much of the
app's raw-control labelling the plan describes is present.

**However, the plan's claim that "label association is now complete across the
app" does not hold in core.** Verified residual defects (sibling `<label>`
with no `for`/`id`, or controls with neither a `<label>` nor an `aria-label`):

| File:line | Control | Issue |
|---|---|---|
| `views/audit/AuditLogView.vue:9–10` | Directory `<select>` | sibling `<label>`, not associated |
| `views/schema/SchemaBrowserView.vue:9–10`, `:31–32` | Directory `<select>`, filter input | sibling `<label>` / placeholder-only |
| `components/CreateEntryForm.vue:19`,`:45`,`:92` | object-class / RDN selects | sibling `<label>`, not associated |
| `components/EditEntryForm.vue:56` | "Add attribute" `<select>` | no label / no `aria-label` |
| `components/ResultsTable.vue:288`,`:363`,`:406` | inputs | unlabelled |
| `components/SavedFilterBar.vue:24` | name input | placeholder-only |
| `components/EffectivePermissionsDialog.vue:141` | filter `<input>` | placeholder-only |
| `components/LdifImportModal.vue:28`,`:34` | file input / conflict select | hidden file input + sibling-label select |
| `components/UserPreferencesDialog.vue:52`,`57`,`69`,`74`,`79` | profile/pref fields | sibling `<label>`, not associated |
| `components/CommandPalette.vue:13` | search `<input>` | placeholder-only |
| `views/superadmin/DiscoveryWizardView.vue:321`,`450`,`498` | row/attr checkboxes | no `aria-label` |

These line up with the plan's own gaps:

1. **Batch 5 was never completed.** The plan lists shared form components
   (`CreateEntryForm`, `LdifImportModal`, `UserPreferencesDialog`,
   `LdapFilterBuilder`) and "associate validation errors via
   `aria-describedby` + `aria-invalid` (3.3.1)" as Batch 5 — and Batch 5 has
   **no** "Partial — done" entry. Confirmed in code:
   **`aria-invalid` / `aria-describedby` appear 0 times** in `frontend/src`,
   so error-association (3.3.1) is **not started**.
2. **`views/audit/` and `views/schema/` were never in any batch.** No Phase-4
   batch enumerates these directories, and their directory pickers / filters
   remain unassociated.

**Latent enforcement gap:** the plan removed `label`, `select-name`, and
`aria-input-field-name` from the axe ratchet (now "enforced"), and the core
spec reflects that (they are absent from `ACCEPTED_RULES`). **But** the axe
spec only scans 10 routes (`a11y.spec.ts` `ROUTES`), and none of them is the
audit log, schema browser, discovery wizard, or an entry create/edit form. So
the enforced rules **do not actually cover** the surfaces where the residual
gaps live — and in core that spec doesn't run in CI at all (see Phase 6). The
"enforced" status is therefore weaker than it reads.

The 24 advisory `form-control-has-label` warnings from `npm run lint:a11y`
correspond to the rows above (some are legitimate dynamic-`aria-label` false
positives elsewhere; the table above lists the ones I confirmed as real).

**Phase 4 verdict: Partial.** Remaining work: associate the controls above
(`for`/`id` or `aria-label`); complete Batch 5 shared components; implement
validation-error association (`aria-invalid` + `aria-describedby`, 3.3.1).

## Phase 5 — Names, contrast, color-only — **Partial**

- **Icon-only button names: Done.** `npm run lint:a11y` reports **0**
  `button-name`/`link-name`-class errors; both rules are removed from
  `ACCEPTED_RULES` (enforced). ✔
- **Non-interactive click handler (2.1.1): Done.**
  `components/dashboard/SuggestedConfigurationPanel.vue:42` is a
  `<RouterLink>`, not a clickable `<div>`. ✔
- **Focus visibility: Done globally.** `assets/main.css:4` `*:focus-visible`
  and `:8` `…button:focus-visible` provide a `2px` indicator, so the planned
  `.btn-*` / `.input-sm` ring items are moot. ✔
- **`text-gray-400` text contrast: Done.** No Tailwind `text-gray-400` usages
  remain in components; the only occurrence is a dark-theme token override in
  `assets/main.css:70`. ✔
- **`color-contrast`: NOT enforced — still open.** It remains in
  `ACCEPTED_RULES` (`a11y.spec.ts:53`). The systemic debt the plan describes
  (muted `text-gray-500` on the `bg-gray-100` app background ≈4.4:1,
  `text-green-600` status text ≈3.9:1, tinted stat cards, `<code>` on
  gray-100) still needs an eyes-on-screen design pass. **This is the single
  largest remaining functional a11y item.**
- **Color-only status (1.4.1):** per the plan, mostly compliant (color paired
  with text/weight/shape); not axe-detectable, remains a manual check. Treat
  as not independently re-verified here.

## Phase 6 — Tooling gate + verification — **Partial (with a core-specific gap)**

What IS in place in core:

- **ESLint a11y: wired and enforced.** `eslint-plugin-vuejs-accessibility`
  (`package.json:44`), `npm run lint:a11y`, and CI job
  **`Frontend (a11y lint)`** in `.github/workflows/ci.yml:230` running
  `npm run lint:a11y`. Error-level rules block; **0 errors**, 92 advisory
  warnings today. ✔
- **`prefers-reduced-motion`:** `assets/main.css:16`
  `@media (prefers-reduced-motion: reduce)` reset. ✔
- The axe spec exists: `tests/e2e/spec/a11y.spec.ts` (`@a11y`),
  `@axe-core/playwright` (`package.json:36`), `e2e:a11y` script, plus
  `tests/e2e/fixtures.ts` + `global-setup.ts`. ✔ (file-present)

**The core-specific gap — the runtime axe gate never runs in CI here:**

- There is **no `.github/workflows/a11y.yml`** in this repo (the plan claims
  one was added). The workflows present are `ci.yml`,
  `dependabot-automerge.yml`, `ghcr-publish.yml`, `release.yml`,
  `trivy-scan.yml`.
- `ci.yml` has **no Playwright/e2e job** — `grep` for
  `playwright|e2e|@a11y|e2e:a11y` in the workflows returns nothing. Its
  frontend jobs are typecheck, **a11y-lint**, unit, build, community-build.
- **Net effect:** `a11y.spec.ts` is effectively dead in CI for core. The
  ratchet (`ACCEPTED_RULES`) and the "enforced" Phase-4/5 rules are only
  exercised if someone runs `npm run e2e:a11y` locally with a backend — which
  the spec's own header notes is impossible in the sandbox (no Docker). So
  the authoritative runtime gate provides **no regression protection in CI**
  as the repo stands.

Still pending (as the plan notes, human/admin steps):

- **Manual AT pass** (VoiceOver + NVDA) over golden paths — not recorded.
- **`color-contrast` design pass** before that rule can leave
  `ACCEPTED_RULES` (Phase 5).
- Promote the a11y checks to **required** status checks in branch protection.

`ACCEPTED_RULES` in core today (`a11y.spec.ts:41`): `color-contrast`,
`aria-dialog-name`, `aria-required-children` — so this is **not yet a full AA
gate** even when it does run.

---

## What's complete vs. pending (core)

**Complete & verified:**
- Phase 1 (lang, skip links, live-region toasts, banner roles).
- Phase 2 (shared modal + ConfirmDialog + DataTable semantics via
  `useDialogA11y`).
- Phase 3 (all core bespoke overlays via `v-dialog-a11y`).
- Phase 5 sub-items: icon-only names (enforced), 2.1.1 click handler,
  global focus-visible, `text-gray-400` retirement.
- Phase 6 tooling: ESLint a11y job (enforced in CI), reduced-motion.

**Pending / not done in core:**
1. **Phase 4 label association — incomplete.** Associate the controls listed
   in the Phase-4 table (audit, schema, create/edit-entry forms, results
   table, saved-filter bar, effective-permissions dialog, LDIF import,
   user-preferences dialog, command palette, discovery wizard). Finish
   Batch 5 shared components.
2. **Phase 4 validation-error association (3.3.1) — not started**
   (`aria-invalid`/`aria-describedby` = 0 usages).
3. **Phase 5 `color-contrast` — not enforced;** systemic contrast debt
   pending a design pass.
4. **Phase 6 runtime axe gate — not wired into CI** in this repo (no
   `a11y.yml` / no e2e job). Highest-leverage fix to prevent regressions, and
   to make the "enforced" Phase-4/5 rules real. Also: extend `ROUTES` to
   cover audit/schema/discovery-wizard/entry-form surfaces, manual AT pass,
   and branch-protection promotion.

## Suggested next steps (in order)

1. **Wire the runtime axe job into CI** (port the e2e/backend-boot harness so
   `npm run e2e:a11y` runs on PRs). Without this, nothing guards the gains.
2. **Close Phase 4**: label the controls above + add 3.3.1 error association;
   then widen `ROUTES` so the now-enforced `label`/`select-name` rules
   actually cover those pages.
3. **Phase 5 contrast design pass**, then drop `color-contrast` from
   `ACCEPTED_RULES`.
4. **Manual AT pass** + promote a11y checks to required status checks.

## Per-phase status (core)

- [x] Phase 1 — Level-A quick wins
- [x] Phase 2 — Shared dialog + table semantics
- [x] Phase 3 — Bespoke modals (core subset; EE overlays N/A)
- [ ] Phase 4 — Form label association *(partial: residual unlabelled
      controls; validation-error association not started)*
- [ ] Phase 5 — Names / contrast / color-only *(names done; `color-contrast`
      open)*
- [ ] Phase 6 — Tooling gate + verification *(lint job + reduced-motion done;
      runtime axe gate not wired into CI; manual AT pending)*
