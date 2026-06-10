# Needs Attention — account-hygiene worklist — design

- **Date:** 2026-06-10
- **Status:** Not started (planning, 2026-06-10).
- **Scope:** A directory-scoped **worklist** that aggregates accounts needing
  admin attention — locked, password-expired/expiring, repeated login
  failures, never-signed-in, stale (no auth in N days), and past
  lifecycle end-date — into a triage queue, with a dashboard glance card,
  reason-aware row/bulk actions, and **per-directory configuration of which
  checks apply** (so e.g. a certificate-only directory hides the password
  checks). Pairs with a new **account-unlock** verb.
- **Audience:** Written to hand to a fresh Claude Code session in
  `ldapportal-core`. Self-contained; paths relative to repo root.
- **Branch:** cut fresh from `origin/main` per repo workflow; deliver in the
  phases of §11, one PR each. This doc is the design hand-off.
- **Mockup:** `docs/mockups/needs-attention-mockup.html` (interactive,
  single-file) shows the target UX for the view + glance card.

## 1. Goal

> "Every morning a user-admin opens one screen that lists exactly the
> accounts that need a human — nothing they can't act on, nothing that
> doesn't apply to how this directory authenticates — and resolves them
> in place, one at a time or in bulk."

Today the building blocks exist per-user but there is **no aggregate view**:
`PasswordPolicyService` answers "what's the state of *this* account?" one DN
at a time (`GET /api/v1/directories/{id}/users/password-status?dn=`). An
admin hunting for locked or expired accounts must already know who to look
at. This plan adds the batched aggregation, the worklist UI, and — crucially
— the configuration that keeps it relevant per directory.

## 2. Current state (verified)

| Concern | Today | Gap |
|---|---|---|
| Per-user policy state | `PasswordPolicyService.getPasswordStatus()` reads ppolicy overlay attrs (`pwdAccountLockedTime`, `pwdChangedTime`, `pwdFailureTime`, `pwdGraceUseTime`, `pwdReset`, `pwdPolicySubentry`) and the applicable ppolicy entry (`pwdMaxAge`, `pwdMaxFailure`, `pwdLockoutDuration`, `pwdExpireWarning`, `pwdLockout`, …). Exposed at `GET …/users/password-status?dn=`. | **Single-DN only.** No batched/aggregate query, no counts, no "who is locked across the directory". |
| Account enable/disable | `POST …/users/enable` / `/disable` (`FeatureKey.USER_ENABLE_DISABLE`). | — |
| Account **unlock** | None. Admins clear a lock as a side effect of password reset. | No first-class unlock verb (see §6.4). |
| Stale / last-auth | Not read anywhere. | Vendor-specific attribute (`lastLogonTimestamp` AD / `authTimestamp` OpenLDAP / `pwdLastSuccess`) not yet surfaced. |
| Lifecycle end-date | Per-profile lifecycle policy exists (`ProvisioningProfileController` lifecycle endpoints; `PlaybookType.OFFBOARD`). | No "past end-date" detector feeding a worklist. |
| Dashboard | `DashboardView` with customizable panels (`ActionRequiredPanel`, `MetricCard`, `AllClearPanel`, `PanelWrapper`, `DashboardCustomizer`, server-persisted layout). | No hygiene panel. |
| User list surface | `UserListView` + `ResultsTable` (selectable, paginated, saved filters) + `ActionMenu`; bulk membership fan-out shipped in PR #168. | Reusable for the worklist table + bulk bar. |
| Per-admin UI prefs | `usefPreferences` store (namespaces: appearance/tables/filters/search/modals/sidebar). | New namespace for personal check hiding. |
| Directory config UI | `DirectoriesManageView`, per-directory ISVA config, `SettingsView` sections. | No "account hygiene" config. |
| Schema/capability probing | Discovery wizard + root-DSE/vendor detection already probe directory capabilities. | Reuse pattern for check capability detection. |

## 3. The worklist (UX)

Two entry points, one data source.

**3.1 Dashboard glance card** — a new `NeedsAttentionPanel.vue` in the
`DashboardView` panel set (uses `PanelWrapper`; collapses to `AllClearPanel`
when all counts are zero). Compact per-category counts with severity dots;
clicking a category deep-links to the worklist pre-filtered. A top-line total
may also be a `MetricCard` on the dashboard metric strip.

**3.2 Triage view** — route `/directories/:dirId/needs-attention`
(`PageContainer`), built on the same `ResultsTable` + `ActionMenu` + bulk
selection stack as `UserListView`, so selection, pagination, saved filters,
and column prefs come for free:

- **Category chips with live counts** (`All`, `Locked`, `Password expired`,
  `Expiring soon`, `Login failures`, `Never signed in`, `Stale`, `Lifecycle`).
  Chips for checks that are disabled or unsupported on this directory **do
  not render** (see §4).
- **Columns:** User (display name + DN), **Reason** badge (severity-colored
  `badge-*`), human **Detail** ("expired 5 days ago"), **Last auth**
  (`RelativeTime`). Default sort: severity, then most-overdue.
- **Reason-aware row action** via `ActionMenu`: the primary button matches the
  reason — Locked→**Unlock**, Expired/Expiring→**Reset password**,
  Never/Stale→**Disable**, Lifecycle→**Disable** (kebab: **Extend**). Kebab
  also carries Edit / View history / Move / Run offboard playbook.
- **Contextual bulk bar** (same component pattern as PR #168): select ≥1 row →
  if the selection shares one reason, that verb leads ("Unlock the 7 locked
  accounts"); common verbs always available. Fan out per user with a per-user
  result summary.
- **Stale-threshold control** (30/60/90/180d) — see §4.3; persisted as a
  per-admin preference.
- **Export CSV** for evidence/audit (reuse existing CSV plumbing).
- **All-clear** empty state per category.

## 4. Configurable checks — the three-layer model

"Turn off a check" means three different things; conflating them is how a
worklist either nags about irrelevant things or silently hides real problems.

### 4.1 Layer 1 — Capability detection (automatic)

Before running a check, the aggregator probes whether the directory exposes
the attributes it needs: ppolicy overlay for the password family; a last-auth
attribute for Stale/Never. A check with **no data source is not offered** —
its chip never appears. Reuses the root-DSE/schema capability-probing pattern
the discovery wizard already uses. Probe result is cached per directory.

> This handles "the directory *can't* support this check." It is **not**
> sufficient for the cert-only case: a certificate-only directory may still
> carry residual `userPassword`/ppolicy attributes that would emit false
> "expired" alerts. That needs Layer 2.

### 4.2 Layer 2 — Per-directory check policy (authoritative)

**Scope is the directory**, because the authentication model is a property of
the directory, not of the admin or the tenant — and a multi-directory
deployment can mix a cert-only directory with a password directory.

Exposed as a single **Authentication model** selector that drives sensible
check defaults, with an **Advanced** expander for per-check overrides +
thresholds:

| Auth model | Password family (locked / expired / expiring / failures) | Stale / Never | Lifecycle |
|---|---|---|---|
| `PASSWORD` (default) | on | on | on |
| `CERTIFICATE` | **off** | on | on |
| `SMARTCARD` | off | on | on |
| `FEDERATED` (OIDC/SAML) | off | if last-auth source present | on |

The common case is one click (`CERTIFICATE`); power users still get
per-check control and threshold tuning. The aggregator **skips disabled
checks entirely** — never computes or returns them — so chips, counts, the
glance card, and CSV export omit them with no check-specific logic anywhere
downstream. A subtle worklist affordance — *"2 checks off for this directory ·
Configure"* — keeps it discoverable.

### 4.3 Layer 3 — Per-admin view preference (personal)

An individual admin collapsing a category they don't care about *today* — like
the dashboard customizer and table-column prefs. Stored client-side in the
`preferences` store (new `needsAttention` namespace). **Can only hide checks
that Layer 2 left on** — never re-enable a directory-disabled check. This is
"not for me right now," not "doesn't apply here." The stale-threshold control
lives here too (personal default; org default seeded from Layer 2).

**Why the split:** capability = *impossible here*, directory policy =
*doesn't apply here* (the cert case), preference = *not for me right now*.
Only Layer 2 changes what is computed and what every admin sees; it is
superadmin-gated and audited (§9).

## 5. Data model (Flyway, `db/migration/core`)

New migration adds a per-directory hygiene config. A typed table (queryable,
matches "Flyway owns the schema"):

```
directory_hygiene_config
  directory_id        uuid PK/FK → directory_connection(id) ON DELETE CASCADE
  auth_model          varchar   -- PASSWORD | CERTIFICATE | SMARTCARD | FEDERATED
  enabled_checks      varchar[] -- explicit per-check overrides; null = derive from auth_model
  stale_days          int       -- default 90
  expiring_window_days int      -- default 7
  never_grace_days    int       -- ignore accounts created < N days (default 7)
  last_auth_attribute varchar   -- override the auto-detected last-auth attr (nullable)
  updated_at / updated_by
```

Check keys (enum `HygieneCheck`, serialized stable): `LOCKED`, `PWD_EXPIRED`,
`PWD_EXPIRING`, `LOGIN_FAILURES`, `NEVER_SIGNED_IN`, `STALE`, `LIFECYCLE`.

No row ⇒ defaults (auth_model `PASSWORD`, all checks on subject to capability).

## 6. Backend design

### 6.1 Aggregator service + endpoint

`AccountHygieneService` (core) computes the worklist for a directory:

1. Load `directory_hygiene_config` (or defaults) and the capability probe.
2. Determine the **active check set** = `enabled(config) ∩ supported(capability)`.
3. For each active check, build a **targeted LDAP filter** so the directory
   does the work — e.g. `LOCKED` → `(pwdAccountLockedTime=*)`;
   `LOGIN_FAILURES` → presence of `pwdFailureTime`; `STALE`/`NEVER` → range/
   absence on the last-auth attribute; `LIFECYCLE` → end-date attribute past
   now. Password **expiry** (`pwdChangedTime` + `pwdMaxAge`) is computed from
   returned attributes (no server-side date math in LDAP), reusing
   `PasswordPolicyService`'s policy-resolution logic refactored to operate on a
   batch rather than a single entry.
4. Return per-category **counts** (cheap) and, for the selected category, a
   **page of rows** (DN, display name, reason, detail, last-auth).

Scope every search through `PermissionService` (admins see only their
authorized OUs), and cap result size like the existing search surface.

### 6.2 Endpoints

```
GET  /api/v1/directories/{id}/needs-attention/summary
       → { checks: [{ key, label, severity, supported, enabled, count }], total }
GET  /api/v1/directories/{id}/needs-attention?check=LOCKED&page=&size=
       → paged rows for one category
GET  /api/v1/directories/{id}/hygiene-config         (Layer 2 read)
PUT  /api/v1/directories/{id}/hygiene-config         (Layer 2 write, superadmin)
```

`@RequiresFeature(FeatureKey.USER_READ)` on the read endpoints (it surfaces
user state); each row action stays behind its own feature.

### 6.3 Consumption

Disabled/unsupported checks are absent from `summary`, so the frontend renders
chips purely from the server's check list — no client-side knowledge of auth
models. Counts are computed even when a category isn't currently being paged,
so the chips and glance card are always live.

### 6.4 Account-unlock verb (paired)

Add `POST /api/v1/directories/{id}/users/unlock?dn=` — clears
`pwdAccountLockedTime` (and `pwdFailureTime`) via the provisioning-plan SPI
(a `ModifyStep` delete), audited as a new `AuditAction.USER_UNLOCK`, gated by a
new `FeatureKey.USER_UNLOCK`. This is the worklist's most-used action and is
small; building it alongside avoids the awkward "reset password to unlock"
workaround. Surfaced as a row action on the user list too.

## 7. Frontend design

- `api/needsAttention.ts` (+ `api/hygieneConfig.ts`) — typed clients.
- `views/needs-attention/NeedsAttentionView.vue` — the triage view (§3.2).
- `components/dashboard/NeedsAttentionPanel.vue` — the glance card (§3.1),
  registered in the dashboard layout config.
- `views/settings/.../AccountHygieneSection.vue` (or per-directory config in
  `DirectoriesManageView`) — Layer 2 UI: auth-model selector + Advanced
  per-check + thresholds. Superadmin-only.
- `stores/preferences.ts` — add `needsAttention` namespace (Layer 3: hidden
  checks, stale-threshold default).
- Router: `/directories/:dirId/needs-attention`, in the directory nav next to
  `users` / `audit` / `bulk`; guard on `user.read`.

Reuse `ResultsTable`, `ActionMenu`, the bulk-bar pattern, `RelativeTime`,
`badge-*`, `EmptyState`/all-clear, and the notification store for results.

## 8. Category catalog (data source · severity · default action)

| Check | Data source | Severity | Primary action | Notes |
|---|---|---|---|---|
| `LOCKED` | `pwdAccountLockedTime=*` | red | Unlock | needs ppolicy |
| `PWD_EXPIRED` | `pwdChangedTime` + `pwdMaxAge` (computed) | red | Reset password | needs ppolicy + `pwdMaxAge` |
| `PWD_EXPIRING` | within `expiring_window_days` of expiry | amber | Reset password | + `pwdExpireWarning` if present |
| `LOGIN_FAILURES` | `pwdFailureTime` count near `pwdMaxFailure` | amber | Reset password | pre-lockout signal |
| `NEVER_SIGNED_IN` | last-auth attr absent, age > `never_grace_days` | gray | Disable | needs last-auth source |
| `STALE` | last-auth attr older than `stale_days` | gray | Disable | needs last-auth source |
| `LIFECYCLE` | profile lifecycle end-date attr < now | yellow | Disable (kebab: Extend) | needs lifecycle policy |

**Vendor caveat:** the last-auth attribute differs by vendor
(`lastLogonTimestamp` AD, `authTimestamp` OpenLDAP, `pwdLastSuccess` on some
ppolicy builds) and is not always populated/replicated. Capability detection
(§4.1) hides Stale/Never where no source exists; `last_auth_attribute` allows
an explicit override.

## 9. Authz & audit

- **Worklist read:** `FeatureKey.USER_READ`, scoped to authorized OUs.
- **Hygiene config (Layer 2):** `@PreAuthorize("hasRole('SUPERADMIN')")` —
  directory configuration, not a daily verb (per repo authz convention).
- **Row/bulk actions:** existing feature per verb (`USER_ENABLE_DISABLE`,
  `USER_RESET_PASSWORD`, new `USER_UNLOCK`).
- **Audit:** config changes recorded via `AuditService` (new
  `AuditAction.HYGIENE_CONFIG_UPDATED` with a detail of the diff); unlock as
  `USER_UNLOCK`. No new audit action for reads.

## 10. Non-goals / out of scope

- Automated remediation (auto-disable stale, auto-unlock) — the worklist is
  human-in-the-loop; automation is the lifecycle-playbook surface.
- Cross-directory aggregation (one worklist spanning directories) — v2.
- Notification/digest emails of the worklist — v2 (the data is here; routing
  is an `ALERTING`-entitlement concern).
- App-account (admin login) hygiene — separate concern from LDAP user data.

## 11. Phased delivery (one PR each)

- **P1 — Unlock verb.** `POST …/users/unlock`, `FeatureKey.USER_UNLOCK`,
  `AuditAction.USER_UNLOCK`, plan-fragment + MockMvc + service tests; row
  action on the user list. *Self-contained; unblocks the worklist's marquee
  action.* **Acceptance:** lock cleared via SPI; 403 without feature; audited.
- **P2 — Aggregator (password family + capability).** `AccountHygieneService`,
  `summary` + paged endpoints for `LOCKED`/`PWD_EXPIRED`/`PWD_EXPIRING`/
  `LOGIN_FAILURES`; capability probe; batch refactor of `PasswordPolicyService`
  policy resolution. **Acceptance:** counts/rows correct against a seeded
  OpenLDAP ppolicy fixture; OU scoping enforced; unsupported checks absent.
- **P3 — Triage view + dashboard panel.** `NeedsAttentionView`,
  `NeedsAttentionPanel`, route + nav, chips/table/actions/bulk-bar, all-clear,
  CSV export, Vitest specs (gating + chip rendering from server list).
- **P4 — Hygiene config (Layer 2).** `directory_hygiene_config` migration,
  enum, read/write endpoints (superadmin), `AccountHygieneSection` UI with
  auth-model selector + advanced overrides + thresholds; aggregator honors it.
  **Acceptance:** setting `CERTIFICATE` removes the four password chips from
  summary/view/export for that directory; audited.
- **P5 — Stale / Never (+ Layer 3 prefs).** Last-auth detection + override,
  `STALE`/`NEVER` checks, stale-threshold control, `preferences` namespace for
  personal hiding. **Acceptance:** threshold change re-buckets rows; hidden
  check returns on reset; cannot un-hide a directory-disabled check.
- **P6 — Lifecycle check.** Past-end-date detector wired to profile lifecycle
  policy; Extend action.

## 12. Testing

- **Backend:** service tests for each check's filter/derivation against
  seeded fixtures; OU-scoping; config-honoring (disabled check ⇒ absent);
  capability fallback; MockMvc for endpoints (authz, shapes); unlock
  plan-fragment + audit.
- **Frontend:** Vitest — chips render from the server check list (disabled ⇒
  hidden); reason→action mapping; bulk-bar contextual verb; threshold
  re-bucketing; all-clear; Layer-3 hide cannot override Layer-2 disable.

## 13. Risks / open questions

1. **Counts cost.** Seven targeted searches per summary load. Mitigate with
   capability gating, per-directory result caps, and a short server-side cache
   on `summary` (counts tolerate slight staleness; rows are fetched fresh).
2. **Expiry math in app, not LDAP.** `PWD_EXPIRED`/`EXPIRING` need attributes
   pulled and computed; the targeted filter narrows the set first
   (`pwdChangedTime` present), then app-side filtering finalizes — accept a
   coarse pre-filter.
3. **Last-auth reliability.** AD `lastLogonTimestamp` is replicated lazily
   (up to ~14 days skew); document that Stale is approximate and let the
   threshold absorb it. Hide entirely where no source exists.
4. **Auth-model default for existing directories.** Backfill as `PASSWORD`
   (current behavior); surface a one-time setup nudge rather than guessing
   cert-only.
5. **Per-profile vs per-directory config.** Some directories mix cert and
   password populations across OUs. Per-directory is the v1 scope; a
   per-profile override is a possible v2 if demand appears (the enum/threshold
   shape already anticipates narrowing).

## 14. Definition of done (feature)

A user-admin opens **Needs Attention** for a directory and sees only the
categories that apply to how that directory authenticates; resolves locked /
expired / stale accounts in place (single or bulk) with audited verbs; a
superadmin can switch a directory to certificate-only and the password
categories disappear everywhere for everyone; an individual admin can declutter
their own view without changing org policy.
