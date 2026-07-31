# Schema hygiene — unused and mismatched DB entities

**Status:** Audit complete (2026-07-31). The safe code-only cleanup (Tier 3/4)
shipped as its own PR off `main`; the migration-requiring drops and the three
product decisions below are scoped but **not started**.

## Why this exists

Scoping the [multi-base-DN plan](2026-07-31-multi-basedn-connection-plan.md)
turned up that `directory_user_base_dns` / `directory_group_base_dns` are
**inert** — rows are written and read back only to build a GET response, an IaC
export, and a discovery add-counter, but no LDAP search / count / browse / auth
path ever consults them. That is a specific smell — *configured and persisted,
but no behaviour reads it* — distinct from ordinary dead code. This audit swept
every core entity for the same three defects:

1. **DEAD** — table/entity (or a repository/service method) with no reachable
   caller at all.
2. **INERT** — written on a live path and often echoed back into a response,
   but no behaviour consumes it (the base-DN pattern).
3. **MISMATCH** — the entity's mapped fields/columns don't line up with the
   migration's actual table (orphan columns, wrong `@Column` length, stale
   docs).

Each entity was traced end-to-end: repository injection sites → every read's
actual consumer → entity fields vs. `V1__baseline.sql` and later migrations.

## Findings

### Tier 1 — Inert features (a decision, not just cleanup)

These are persisted, round-trip through the UI, and look like working controls,
but nothing enforces them. Each needs a *build-it-or-remove-it* call.

#### 1a. `profile_lifecycle_policies` — the whole table is inert ⚠️ highest impact

An admin can configure "expire after N days → disable / move to DN / strip
groups, warn M days before, max R renewals," and it persists and round-trips.
But there is **no enforcement engine**: the eight behavioural getters
(`getExpiresAfterDays`, `getOnExpiryAction`, `getOnExpiryMoveDn`,
`getWarningDaysBefore`, `getMaxRenewals`, `getRenewalDays`,
`isOnExpiryRemoveGroups`, `isOnExpiryNotify`) are referenced **only** in
`LifecyclePolicyResponse` (the GET echo) and the profile-clone block
(`ProvisioningProfileService.java:413-420`). No `@Scheduled` job sweeps accounts
for expiry — the full scheduler inventory (sync workers, metrics, changelog,
approval reminder, Entra poll, outbox dispatch, report jobs) contains nothing
that reads these fields.

Accounts never actually expire, move, lose groups, or emit warnings. This is a
silent security/compliance gap: it presents as a lifecycle control that does
nothing.

- **Option A (build):** add a `@Scheduled` `AccountLifecycleSweeper` that loads
  profiles with a policy, resolves member accounts, and applies expiry / move /
  group-strip / warning-notify with an audit trail. Sizeable — it needs an
  account→profile membership resolver, per-account "provisioned at / last
  renewed" timestamps (not currently stored), idempotency, and dry-run.
- **Option B (remove):** drop the table + entity + the config surface in the
  edit form, so the product stops advertising a control it doesn't honour.
- **Recommendation:** decide with product. If lifecycle enforcement isn't on the
  near-term roadmap, **remove** it — a non-functional expiry control is worse
  than none. If it is, treat 1a as its own project, not a cleanup.

#### 1b. `ProfileApprovalConfig.approverMode = LDAP_GROUP` (+ `approver_group_dn`) — half-wired

`requireApproval` itself is genuinely enforced (routes ops to approval in
`ApprovalWorkflowService.java:140` and self-registration in
`SelfServiceService.java:377`). But the `LDAP_GROUP` approver mode is stored,
cloned, and echoed with **no decision path branching on it**:
`ApprovalWorkflowService.canViewApproval` (`:370-373`) and
`ApprovalNotificationService` (`:47-48`, `:115-116`) both read the DB
`profile_approvers` table unconditionally, regardless of mode.

Net effect: set a profile to `LDAP_GROUP` with a group DN and its approvals
become actionable **only by superadmins** (no `profile_approvers` rows exist),
and the named LDAP-group members are neither authorized nor notified — a silent
misbehaviour, worse than an inert one.

- **Option A (build):** in `canViewApproval` and the notifier, when
  `approverMode == LDAP_GROUP`, resolve `approverGroupDn` membership via the
  existing LDAP group service and authorize/notify those members.
- **Option B (remove):** drop `LDAP_GROUP` from the enum and the two columns;
  keep DB-approver mode only.
- **Recommendation:** Option A is small and closes a real authorization hole;
  prefer it unless LDAP-group approvers aren't wanted at all.

#### 1c. `ProfileApprovalConfig.auto_escalate_days` + `escalation_account_id` — inert

Written in `setApprovalConfig` (`:518-523`), cloned, echoed in
`ApprovalConfigResponse` — consumed nowhere (no escalation job; the approval
reminder cron doesn't read them). Configured auto-escalation never fires.
Fold the decision into 1b: either extend the existing approval-reminder
`@Scheduled` job to escalate, or drop both columns.

#### 1d. Entra mirror cluster — populated but behaviourally unconsumed

`entra_users` / `entra_groups` / `entra_group_memberships` are filled by a live
scheduler (`EntraSyncScheduler`, every 5 min) via full + delta Graph sync. But
the **only** consumers are read-only superadmin listing endpoints (`GET /users`,
`GET /groups`, `GET /sync-status` counters). The documented purpose —
entitlements, SoD, access reviews, evidence packages — is **unbuilt**: the two
methods that would realize it are dead with zero callers:
`EntraEntitlementService.getGroupMemberIds` and
`EntraUserRepository.findGuestsSyncedAfter`.

So it's a large mirror maintained on a timer to power a viewer, with the
behavioural half absent. Additionally three columns are pure write
amplification — `entra_users.department`, `.job_title`, `.employee_id` are set
on every sync (`EntraSyncService.java:160-162`, `:250-252`) and read by nothing
(the sole reader, `EntraEntitlementService.getUserEntitlements`, projects only
id/displayName/UPN/mail/accountEnabled).

- **Option A (build):** wire `getGroupMemberIds` into the access-review /
  entitlement surfaces that the Javadoc promises, and surface the guest-detection
  query. Then the mirror earns its keep and the three detail columns get read.
- **Option B (scope down):** if only the viewer is wanted, stop syncing the
  three unread columns and delete the two dead methods (and drop the columns via
  migration).
- **Recommendation:** product call on whether Entra entitlement/SoD is on the
  roadmap. Until then, this is the lowest-urgency Tier-1 item (it powers a real,
  if shallow, feature and misleads no one about enforcement).

### Tier 2 — Inert columns (drop via forward-only migration)

Written (or not even written) but never read. Removing them is a schema change,
so each needs a `V26+` migration; grouped here rather than in the code-only
cleanup PR.

| Column | State | Evidence |
|---|---|---|
| `directory_user_base_dns.editable` | written, never read; `directory_group_base_dns` has no such column (asymmetric) | `DirectoryDiscoveryService.java:215` sets it; no reader. Fold into the base-DN plan's flatten migration. |
| `entra_users.department`, `.job_title`, `.employee_id` | synced every poll, never read | `EntraSyncService.java:160-162`, `:250-252`; zero getter callers. Tie to decision 1d. |
| `csv_mapping_template_entries.csv_column_index` | never written **and** never read → always NULL | mapped in the entity; `saveEntries` omits it; no DTO/import path consumes it. Drop column + entity field together. |
| `api_tokens.scopes` (jsonb) | intentional forward-compat ("v1 always null" per its Javadoc) | leave as-is; documented, deliberately reserved. Listed for completeness. |

### Done — Tier 3 & 4 (code-only, no migration, no behaviour change)

Shipped as a standalone cleanup PR off `main` (branch `claude/db-hygiene-cleanup`):

- **Dead repository/service methods removed:**
  - `AccountRepository.findAllByAuthType` — no callers.
  - `AuditDataSourceRepository.findAllByEnabledTrue` — *bypassed*: `LdapChangelogReader`
    does `findAll().stream().filter(...)` in Java instead.
  - `NotificationService.getUnread` + `NotificationRepository.findByAccountIdAndReadFalseOrderByCreatedAtDesc`
    — orphaned second read path (the notification feature itself stays live via
    the count/page endpoints).
  - `CsvMappingTemplateEntryRepository.findByTemplateIdAndCsvColumnName` — no callers.
  - `CsvMappingTemplateRepository.findByDirectoryIdAndName` — no callers
    (uniqueness is checked via `existsByDirectoryIdAndName`).
- **Entity ↔ DDL mismatches fixed:**
  - `AuditDataSource.bindDn` / `.changelogBaseDn` / `.branchFilterDn` declared no
    `length` (implying `varchar(255)`) but the columns are `varchar(1000)` — now
    annotated `length = 1000`.
  - `Account.authType` Javadoc said "LOCAL … or LDAP" but the enum/code actively
    use `OIDC` and `WEBSEAL` — doc corrected.

> `EntraEntitlementService.getGroupMemberIds` and
> `EntraUserRepository.findGuestsSyncedAfter` are *also* dead, but they back the
> unbuilt behaviour in decision 1d, so they're left in place until that
> direction is set rather than deleted here.

## What was cleared

The remainder is genuinely wired and was verified end-to-end: the sync engine
(`SyncLink`/`SyncSet`/`Membership`/`RecomputeRequest` — and the
`membership`→`sync_membership` rename correctly matches the `@Table`), the
events backbone (`OutboxEntry`/`EventSubscription` — a fully drained
transactional outbox), lifecycle *playbooks* (executed and rolled back — not to
be confused with the inert lifecycle *policies* in 1a), scheduled reports (live
poller), CSV templates, the approval core, permissions
(`AdminProfileRole`/`AdminFeaturePermission`/`SuperadminPermissionGrant`), the
audit query surface, OIDC pending flows, preferences/notifications/dashboard,
and the three element-collection tables (`enabled_auth_types`,
`profile_object_classes`, `profile_additional_profiles`).

## Suggested sequencing

1. **Done** — the Tier 3/4 code-only cleanup (standalone PR, no risk, no migration).
2. **Decisions** — take 1a (highest impact), then 1b/1c together, then 1d to
   product. Each "remove" outcome becomes a small migration PR; each "build"
   outcome becomes its own scoped project.
3. **Tier 2 migrations** — drop `editable` (with the base-DN flatten) and
   `csv_column_index` once confirmed; the Entra columns follow decision 1d.
