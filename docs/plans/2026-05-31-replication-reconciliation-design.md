# Replication reconciliation — design plan

- **Date:** 2026-05-31
- **Status:** Not started (design proposal, 2026-05-31).
- **Scope:** Periodic, operator-scheduled reconciliation between the
  source and target DITs of an existing **replication link**. Compares the
  replicated subtrees, classifies discrepancies, and either auto-corrects
  them or presents them to the operator with suggested corrective actions
  for selective apply. Corrections flow through the existing
  `replication_events` dispatch queue.
- **Builds on:** `docs/plans/2026-05-30-directory-sync-design.md` (the
  async fan-out feature this extends). Read that first — this document
  assumes its vocabulary (`ReplicationLink`, `replication_events`,
  `ReplicationWorker`, `DnMapper`, `AttributeMapper`, the JSONB payload
  shapes) and its accepted limitations.
- **Audience:** Self-contained; written to hand to a fresh Claude Code
  session. Paths are relative to the repo root.
- **Suggested branch:** `feat/replication-reconciliation` (cut fresh from
  `origin/main`).

## 1. Goal

Directory sync (the predecessor feature) is **write-capture** based: it
replicates only the writes *this app* makes to a source directory. Its
§7 "Known limitations (accepted)" calls out exactly the gaps that let a
target silently drift from its source:

> - **Doesn't see out-of-band changes** — anything written to the source by
>   a tool other than this app … Target drifts silently.
> - **No initial backfill** — turning a replication link on doesn't backfill
>   the existing source state to the target.
> - **No conflict detection** — a write on the target that the portal didn't
>   make won't be detected; the next replication event may clobber it.

**Reconciliation closes all three.** On an operator-chosen schedule, it
performs a full state comparison of the link's source subtree against its
target subtree — independent of the write-capture path — and resolves the
differences. The user-facing promise is:

> "On the schedule I set, the app checks that the target actually matches
> the source for this link. If they've drifted — because of an out-of-band
> change, a missed write, or because I just turned the link on — it either
> fixes it automatically or shows me exactly what's different and lets me
> choose which fixes to apply."

Reconciliation is **opt-in per link** and **off by default**. A link with
reconciliation disabled behaves exactly as it does today.

## 2. Relationship to the existing replication code

This feature is purely additive. It reuses, and does not modify the
contract of, the following (all under
`core/src/main/java/com/ldapportal/ldap/replication/` unless noted):

| Existing piece | Reuse in reconciliation |
|---|---|
| `entity/ReplicationLink` + `replication_links` table | Carries the new reconciliation config columns (§5.1). |
| `DnMapper.map(sourceDn, linkSnapshot)` | Maps a source DN → expected target DN when building the expected target state. |
| `AttributeMapper.mapAttributes(...)` / `mappingFor(...)` | Maps source attr names + values → expected target attrs, so the diff compares like-for-like. |
| `ReplicationPayloadCodec` + the JSONB payload shapes (design §9.2) | Corrective actions are encoded in the **same** `ADD`/`MODIFY`/`DELETE` payload shape and applied by the existing worker. |
| `replication_events` table + `ReplicationWorker` + `ReplicationDelivery` + `ReplicationBackoffPolicy` | A corrective action is enqueued as an ordinary `replication_events` row with `enqueue_source='RECONCILIATION'`; the worker delivers it with the same per-link FIFO, backoff, dead-letter, and audit machinery. **No second delivery path.** |
| `ReplicationReadOps` (source/target connection snapshots) | Extended (or paralleled by `ReconciliationReadOps`) with a paged subtree read for both sides of the comparison. |
| `ReplicationEventRetentionScheduler` | Pattern mirrored by a retention sweep for `reconciliation_runs` / `reconciliation_findings`. |
| `@Scheduled(fixedDelayString=...)` worker pattern (e.g. `ReplicationWorker`, `EntraSyncScheduler`) | A fixed-delay sweeper claims and runs **due** reconciliation jobs (§6). |

The single contract change to existing schema is widening the
`replication_events.enqueue_source` CHECK constraint to admit a third
value, `RECONCILIATION` (§5.3) — forward-only, additive.

## 3. Design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Trigger model | **Per-link, operator-scheduled**: enable flag + first-run datetime + repeat interval, plus an on-demand "Reconcile now" button. | Exactly the operator controls requested. On-demand is free once the scheduled path exists and is invaluable for "I just fixed the target, re-check." |
| Scheduling mechanism | **`next_run_at` column + fixed-delay sweeper** (§6), not a dynamic per-link `TaskScheduler`. | Matches the established `replication_events.next_attempt_at` + `ReplicationWorker` pattern; survives restarts; no in-memory schedule registry to rebuild. |
| Resolution mode | Per-link **`reconcile_mode`**: `AUTO_CORRECT` or `REVIEW`. **Default `REVIEW`.** | The prompt asks for *both* "correct the discrepancies" *and* "present them … and perform the selected operation." A per-link mode lets the operator pick trust level per topology; review is the safe default. |
| Deletion handling | **Operator-chosen, separately from the mode** (§5.1 `reconcile_delete_action`): `IGNORE` (don't touch extras), `REVIEW` (always hold deletes for a human, even on an auto-correct link), or `AUTO` (apply deletes automatically). **Default `REVIEW`.** | Deleting target entries is the one irreversible class; the operator — not us — decides how much autonomy it gets. A link can auto-correct adds/drift while still holding deletes for review. |
| How corrections are applied | **Enqueue a `replication_events` row** (`enqueue_source='RECONCILIATION'`) — never write the target directly from the reconciler. | One delivery path, one audit trail, one backoff/dead-letter story, per-link FIFO ordering preserved. The reconciler *detects*; the worker *applies*. |
| Comparison authority | **Source is authoritative.** Expected target state = source state mapped through `DnMapper` + `AttributeMapper`. | Same directionality as replication. Reverse flow (target→source) is out of scope. |
| Discrepancy classes | `MISSING_IN_TARGET` → ADD, `ATTRIBUTE_DRIFT` → MODIFY, `EXTRA_IN_TARGET` → DELETE (governed by `reconcile_delete_action`). `DN_MISMATCH`/MODIFY_DN deferred. | Covers the three accepted limitations. Adds/drift follow `reconcile_mode`; the destructive extra→delete class follows the separate, operator-chosen `reconcile_delete_action`. |
| Minimum interval | **1 hour** floor on `reconcile_interval_secs`. | A full subtree compare is heavy; sub-hour cadence is almost never the right tool (the live capture path already handles steady-state) and guards against accidental tight loops. |
| Managed-attribute scope | Compare only **non-operational, source-present** attributes, minus a permanent exclusion set (`userPassword`, operational attrs: `createTimestamp`, `modifyTimestamp`, `entryUUID`, `entryDN`, `creatorsName`, `modifiersName`, `pwdChangedTime`, …) plus any attr not covered when a link defines an explicit mapping allow-list. | A target legitimately holds attrs the source never sends; flagging those as drift would be noise. `userPassword` can't be read back from most targets, so password drift is unobservable and must never be flagged. |
| Race with the live queue | A finding is **suppressed** when an undelivered (`PENDING`/`IN_FLIGHT`/`FAILED`) `replication_events` row already targets the same target DN. | The queue is mid-converging that entry; reconciliation must not race the worker or double-enqueue. Reconciliation fixes *drift the capture path will never catch*, not transient lag. |
| Concurrency | **Single-flight per link** via a `reconciliation_runs` row claimed CAS-style (mirrors `ReplicationWorker.tryClaim`); a second tick for a link already `RUNNING` is a no-op. | A subtree compare is heavy; never run two for one link. |
| Catch-up after downtime | On run completion, advance `next_run_at` by whole `interval`s until it is **strictly in the future** (skip missed slots; never burst). | If the app was down for a day, the operator wants one reconcile now and the cadence resumed — not 48 back-to-back runs. |
| Auth | **SUPERADMIN only** for config, manual trigger, and finding apply/dismiss — same as link CRUD and event ops in the predecessor. | Misapplied reconciliation can mass-delete a target. |
| Edition gating | Behind the existing **`Entitlement.DIRECTORY_SYNC`**. | Reconciliation is part of directory sync; same gate, same editions. |
| Out-of-scope (v1) | Bidirectional reconciliation; MODIFY_DN/rename reconciliation; structural schema diff; password reconciliation; reconciling entries outside the link's base-DN scope. | Keeps v1 shippable; each is a clean later addition. |

## 4. End-to-end flow

```
                          ┌─────────────────────────────────────────┐
   fixed-delay sweep ───▶ │ ReconciliationScheduler (@Scheduled)     │
   (every 30s)            │  • find links: reconcile_enabled         │
                          │    AND reconcile_next_run_at <= now()    │
                          │  • CAS-claim a reconciliation_runs row   │
                          └───────────────┬─────────────────────────┘
                                          │ (also: manual "Reconcile now")
                                          ▼
                          ┌─────────────────────────────────────────┐
                          │ ReconciliationService.run(link)          │
                          │  1. paged SUB read of source subtree     │
                          │  2. paged SUB read of target subtree     │
                          │  3. for each source entry:               │
                          │       expectedTargetDn = DnMapper.map    │
                          │       expectedAttrs   = AttributeMapper  │
                          │       compare vs actual target entry     │
                          │  4. for each target entry with no source │
                          │       counterpart (in scope) → EXTRA     │
                          │  5. suppress findings shadowed by an     │
                          │     undelivered replication_event        │
                          └───────────────┬─────────────────────────┘
                                          ▼
            mode = AUTO_CORRECT │                 │ mode = REVIEW
                                ▼                 ▼
        enqueue replication_events       persist reconciliation_findings
        (enqueue_source=RECONCILIATION)  status=PROPOSED; operator reviews
        finding status=AUTO_APPLIED      → selects → apply → enqueue events
                                │                 │            (status=APPLIED)
                                └────────┬────────┘
                                         ▼
                          ┌─────────────────────────────────────────┐
                          │ existing ReplicationWorker / Delivery    │
                          │  • per-link FIFO, backoff, dead-letter   │
                          │  • AuditService.record(...) on delivery  │
                          └─────────────────────────────────────────┘
```

## 5. Data model

### 5.1 Config columns on `replication_links` (new migration, e.g. `V12`)

```sql
ALTER TABLE replication_links
    ADD COLUMN reconcile_enabled        BOOLEAN     NOT NULL DEFAULT false,
    -- AUTO_CORRECT applies findings immediately; REVIEW persists them as
    -- PROPOSED for operator selection.
    ADD COLUMN reconcile_mode           VARCHAR(20) NOT NULL DEFAULT 'REVIEW',
    -- Operator-chosen wall-clock start of the FIRST run. Drives the initial
    -- next_run_at; thereafter the sweeper advances by reconcile_interval.
    ADD COLUMN reconcile_first_run_at   TIMESTAMPTZ,
    -- Repeat cadence in seconds (UI offers hours/days; stored in s).
    ADD COLUMN reconcile_interval_secs  INTEGER,
    -- Next due time the sweeper polls on. Set to reconcile_first_run_at when
    -- the operator enables; advanced by whole intervals on each completed run.
    ADD COLUMN reconcile_next_run_at    TIMESTAMPTZ,
    ADD COLUMN reconcile_last_run_at    TIMESTAMPTZ,
    -- How EXTRA_IN_TARGET (entry on target with no source counterpart) is
    -- handled. IGNORE: never flag. REVIEW: always surface as a PROPOSED
    -- finding for a human, even when reconcile_mode=AUTO_CORRECT. AUTO:
    -- enqueue the DELETE automatically. Independent of reconcile_mode so the
    -- destructive class can be held back while adds/drift auto-apply.
    ADD COLUMN reconcile_delete_action  VARCHAR(20) NOT NULL DEFAULT 'REVIEW',
    CONSTRAINT replication_links_reconcile_mode_check
        CHECK (reconcile_mode IN ('AUTO_CORRECT','REVIEW')),
    CONSTRAINT replication_links_reconcile_delete_action_check
        CHECK (reconcile_delete_action IN ('IGNORE','REVIEW','AUTO')),
    -- When enabled, the schedule fields must be populated and sane.
    -- Interval floor is 1 hour (3600 s).
    CONSTRAINT replication_links_reconcile_schedule_consistency
        CHECK (
          reconcile_enabled = false
          OR (reconcile_first_run_at IS NOT NULL
              AND reconcile_interval_secs IS NOT NULL
              AND reconcile_interval_secs >= 3600)
        );

-- Sweeper index: due, enabled links only. Partial keeps it tiny.
CREATE INDEX idx_replication_links_reconcile_due
    ON replication_links(reconcile_next_run_at)
    WHERE reconcile_enabled;
```

### 5.2 `reconciliation_runs` and `reconciliation_findings` (same migration)

```sql
CREATE TABLE reconciliation_runs (
    id                 UUID PRIMARY KEY,
    link_id            UUID NOT NULL REFERENCES replication_links(id) ON DELETE CASCADE,
    trigger            VARCHAR(20) NOT NULL,   -- SCHEDULED | MANUAL
    mode               VARCHAR(20) NOT NULL,   -- snapshot of reconcile_mode at run time
    status             VARCHAR(20) NOT NULL,   -- RUNNING | COMPLETED | FAILED | CANCELLED
    -- single-flight claim, mirrors replication_events.claimed_at
    claimed_at         TIMESTAMPTZ,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    source_entry_count INTEGER,
    target_entry_count INTEGER,
    missing_count      INTEGER NOT NULL DEFAULT 0,  -- MISSING_IN_TARGET
    drift_count        INTEGER NOT NULL DEFAULT 0,  -- ATTRIBUTE_DRIFT
    extra_count        INTEGER NOT NULL DEFAULT 0,  -- EXTRA_IN_TARGET
    suppressed_count   INTEGER NOT NULL DEFAULT 0,  -- shadowed by a live event
    error              TEXT,
    CONSTRAINT reconciliation_runs_status_check
        CHECK (status IN ('RUNNING','COMPLETED','FAILED','CANCELLED')),
    CONSTRAINT reconciliation_runs_trigger_check
        CHECK (trigger IN ('SCHEDULED','MANUAL'))
);

CREATE INDEX idx_reconciliation_runs_link    ON reconciliation_runs(link_id, started_at DESC);
-- At most one live run per link (the single-flight guard).
CREATE UNIQUE INDEX uq_reconciliation_runs_one_active
    ON reconciliation_runs(link_id) WHERE status = 'RUNNING';

CREATE TABLE reconciliation_findings (
    id                 UUID PRIMARY KEY,
    run_id             UUID NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    link_id            UUID NOT NULL REFERENCES replication_links(id) ON DELETE CASCADE,
    finding_type       VARCHAR(30) NOT NULL,   -- MISSING_IN_TARGET | ATTRIBUTE_DRIFT | EXTRA_IN_TARGET
    suggested_op       VARCHAR(20) NOT NULL,   -- ADD | MODIFY | DELETE
    source_dn          VARCHAR(2000),          -- null for EXTRA_IN_TARGET
    target_dn          VARCHAR(2000) NOT NULL,
    -- The diff, ready to render and to encode as a replication_events payload:
    --   ADD    → { "attributes": { ... } }
    --   MODIFY → { "modifications": [ {type,name,values}, ... ],
    --              "before": { attr: [vals] } }   (before = current target, for UI)
    --   DELETE → { "currentTarget": { attr: [vals] } }   (for UI confirmation)
    detail             JSONB NOT NULL,
    status             VARCHAR(20) NOT NULL,   -- PROPOSED | AUTO_APPLIED | APPLIED | DISMISSED | SUPERSEDED
    -- set when status → AUTO_APPLIED / APPLIED; ties a finding to its event
    event_id           UUID REFERENCES replication_events(id) ON DELETE SET NULL,
    resolved_by        UUID REFERENCES app_accounts(id),   -- operator, when APPLIED/DISMISSED
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ,
    CONSTRAINT reconciliation_findings_type_check
        CHECK (finding_type IN ('MISSING_IN_TARGET','ATTRIBUTE_DRIFT','EXTRA_IN_TARGET')),
    CONSTRAINT reconciliation_findings_status_check
        CHECK (status IN ('PROPOSED','AUTO_APPLIED','APPLIED','DISMISSED','SUPERSEDED'))
);

CREATE INDEX idx_reconciliation_findings_run    ON reconciliation_findings(run_id, finding_type);
CREATE INDEX idx_reconciliation_findings_open
    ON reconciliation_findings(link_id) WHERE status = 'PROPOSED';
```

> `app_accounts` / `resolved_by`: confirm the actual operator-account table
> + column name (the predecessor uses an account id for event operator
> actions); adjust the FK to match.

### 5.3 Widen `replication_events.enqueue_source` (same migration)

```sql
ALTER TABLE replication_events
    DROP CONSTRAINT replication_events_enqueue_source_check,
    ADD  CONSTRAINT replication_events_enqueue_source_check
        CHECK (enqueue_source IN ('APP_INTERCEPT','SOURCE_CHANGELOG','RECONCILIATION'));
```

The `EnqueueSource` enum gains `RECONCILIATION`. The worker stays
source-agnostic; `enqueue_source` is provenance only (and lets the event
log badge reconciliation-originated corrections distinctly).

All new schema lives under `core/src/main/resources/db/migration/core/`
(reconciliation is vendor-agnostic core, like the rest of directory sync).

## 6. Scheduling

A single fixed-delay sweeper, modeled on `ReplicationWorker` /
`EntraSyncScheduler`:

```java
@Scheduled(fixedDelayString = "${ldapportal.reconciliation.sweep-ms:30000}")
void sweep() {
    for (var linkId : readOps.dueReconciliationLinkIds(OffsetDateTime.now())) {
        // CAS: INSERT a RUNNING reconciliation_runs row guarded by
        // uq_reconciliation_runs_one_active; on conflict, another node/tick
        // owns it → skip.
        runner.tryStart(linkId).ifPresent(run ->
            executor.submit(() -> service.run(run)));   // off the sweep thread
    }
}
```

Key properties:

- **Operator inputs map directly:** `reconcile_first_run_at` →
  `reconcile_next_run_at` on enable; `reconcile_interval_secs` is the step.
- **Due check is a cheap indexed query** (`idx_replication_links_reconcile_due`):
  `reconcile_enabled AND reconcile_next_run_at <= now()`.
- **Heavy work is off the sweep thread** on a small bounded executor
  (`ldapportal.reconciliation.pool-size`, default 2) so a long compare
  never wedges the 30 s tick.
- **Completion advances the schedule** with whole-interval catch-up:
  ```
  next = reconcile_next_run_at
  while (next <= now) next = next.plusSeconds(reconcile_interval_secs);
  reconcile_next_run_at = next; reconcile_last_run_at = now;
  ```
- **Crash recovery:** a `RUNNING` run with `claimed_at` older than a
  threshold (e.g. 2× the configured per-run timeout) is swept back to
  `FAILED` so the link reschedules — same stale-reset idea as
  `ReplicationWorker.resetStaleInFlight`.
- **Skip conditions:** link disabled, owning directory's
  `replication_enabled=false`, or directory unreachable → record a `FAILED`
  run with `error`, advance schedule, move on (don't wedge the cadence).

Config keys (all with sane defaults, no required env):
`ldapportal.reconciliation.sweep-ms`, `.pool-size`, `.page-size`
(paged search size, default 500 — reuse the directory's `pagingSize`),
`.run-timeout-ms`, `.max-findings-per-run` (safety cap; if a compare would
produce more than N findings — e.g. a base-DN typo — abort with a `FAILED`
run rather than enqueue a mass mutation), `.retention-days`.

## 7. Comparison engine

`ReconciliationService.run(run)` outline:

1. **Snapshot the link** (`ReplicationLinkSnapshot`) — base DNs, attribute
   mappings, `delete_action`, mode.
2. **Read both subtrees, paged**, via `ReconciliationReadOps`:
   - Source: SUB search under `source_base_dn` (or the directory base when
     null), returning DN + managed attributes.
   - Target: SUB search under `target_base_dn` (or identity of source base).
   - Stream into DN-keyed maps; cap at `max-findings-per-run` worth of
     entries with a hard abort beyond it.
3. **Build expected target state from source**: for each source entry,
   `expectedDn = DnMapper.map(sourceDn, link)`,
   `expectedAttrs = AttributeMapper.mapAttributes(sourceAttrs, link)`,
   then strip the exclusion set (§3) and, if the link defines an explicit
   mapping allow-list, restrict to mapped attrs.
4. **Classify:**
   - `expectedDn` absent from target → **MISSING_IN_TARGET**, `suggested_op=ADD`,
     payload `{ "attributes": expectedAttrs }`.
   - present but attrs differ → **ATTRIBUTE_DRIFT**, `suggested_op=MODIFY`,
     compute minimal `modifications` (REPLACE where multi-valued differs,
     ADD/DELETE per attr as appropriate), record `before` for the UI.
   - target entry whose DN reverse-maps into scope but has no source
     counterpart → **EXTRA_IN_TARGET**, `suggested_op=DELETE`. Governed by
     `reconcile_delete_action`: `IGNORE` skips it entirely; `REVIEW` and
     `AUTO` both record the finding (their difference is whether it
     auto-applies, decided in step 6).
5. **Suppress shadowed findings**: drop any finding whose `target_dn`
   matches an undelivered (`PENDING`/`IN_FLIGHT`/`FAILED`) `replication_events`
   row for the link; bump `suppressed_count`.
6. **Emit** per mode:
   - **Adds & drift** follow `reconcile_mode`: `AUTO_CORRECT` enqueues a
     `replication_events` row per finding (`enqueue_source='RECONCILIATION'`,
     payload as above) and sets the finding `status=AUTO_APPLIED, event_id=…`;
     `REVIEW` persists them `status=PROPOSED`.
   - **Extra→delete** follows `reconcile_delete_action`, *independent of the
     mode*: `AUTO` enqueues the DELETE (`status=AUTO_APPLIED`); `REVIEW`
     persists it `status=PROPOSED` (held for a human even on an auto-correct
     link); `IGNORE` never reached this point. This is the operator-chosen
     split between "fix it" and "show me first" for the destructive class.
7. **Finalize** the run row (counts, `status=COMPLETED`, `finished_at`) and
   advance the schedule (§6).

Comparison correctness notes baked in:

- **Eventual-consistency safety** via step 5 — reconciliation only fixes
  drift the capture path will never converge.
- **Value comparison** is attribute-syntax aware where it matters
  (case-insensitive DN/`distinguishedName` values, normalized via the SDK)
  to avoid cosmetic-difference false positives; default to exact set
  comparison otherwise.
- **`userPassword` and operational attrs never compared** (target won't
  return them / they're server-maintained).

## 8. REST API

All under the existing superadmin replication controller(s), SUPERADMIN
+ `Entitlement.DIRECTORY_SYNC` gated, returning `ResponseEntity<>` with
`ProblemDetail` error mapping (throw `IllegalArgumentException` for 400s,
`ResourceNotFoundException` for 404s — per core conventions).

**Config** is additive on the existing link create/update DTOs
(`/api/v1/superadmin/replication-links`): the request gains
`reconcileEnabled, reconcileMode, reconcileFirstRunAt, reconcileIntervalSecs,
reconcileDeleteAction`; the response gains those plus read-only
`reconcileNextRunAt, reconcileLastRunAt`. Validation: when enabled,
first-run + interval required, **interval ≥ 3600 s (1 hour)**, `reconcileMode`
and `reconcileDeleteAction` in their enums, and switching
`reconcileDeleteAction` to `AUTO` requires a confirmation flag.

**New endpoints:**

| Method & path | Purpose |
|---|---|
| `POST /api/v1/superadmin/replication-links/{id}/reconcile` | Trigger an immediate `MANUAL` run (409 if one is already `RUNNING`). Returns the run id. |
| `GET  /api/v1/superadmin/replication-links/{id}/reconciliation-runs` | Paged run history (status, counts, timestamps). |
| `GET  /api/v1/superadmin/reconciliation-runs/{runId}` | One run with summary counts. |
| `GET  /api/v1/superadmin/reconciliation-runs/{runId}/findings?status=&type=&page=&size=` | Paged findings for the review UI. |
| `POST /api/v1/superadmin/reconciliation-runs/{runId}/findings:apply` | Body `{ findingIds: [...] }` or `{ applyAll: true, type?: ... }`. Enqueues corrective events for the selected `PROPOSED` findings, sets them `APPLIED` + `event_id`, `resolved_by`. Returns `{ applied: n }`. |
| `POST /api/v1/superadmin/reconciliation-runs/{runId}/findings:dismiss` | Body `{ findingIds: [...] }`. Marks `DISMISSED`. |

Apply semantics mirror the existing `applySelectiveGroupChanges` /
`evaluateGroupChanges` pattern in `ProvisioningProfileService` /
`ProvisioningProfileController` — selective, idempotent, returns an
applied count.

## 9. Frontend

`frontend/src/views/superadmin/DirectorySyncView.vue` (already `lang="ts"`)
and `frontend/src/api/replication.js`.

### 9.1 Link create/edit modal — new "Reconciliation" section

Appended to the existing form, using the project utility classes
(`.input`, `.btn-*`) per conventions:

- **Enable reconciliation** checkbox (`reconcileEnabled`). Disabled when the
  link itself is disabled.
- When enabled:
  - **Mode** radio: *Auto-correct* / *Review before applying*
    (`reconcileMode`, default **Review**), with a one-line explainer each.
    This governs missing-entry and attribute-drift fixes.
  - **First run** datetime-local picker (`reconcileFirstRunAt`).
  - **Repeat every** number input + unit `<select>` (hours / days),
    serialized to `reconcileIntervalSecs`; the input enforces the **1-hour
    minimum**.
  - **Extra entries on the target (no source match)** `<select>`
    (`reconcileDeleteAction`, default **Review before deleting**): *Leave
    alone* (`IGNORE`) / *Review before deleting* (`REVIEW`) / *Delete
    automatically* (`AUTO`). Choosing *Delete automatically* prompts a
    destructive-action confirmation. This is surfaced as its own control,
    deliberately separate from Mode, so the operator consciously decides how
    much autonomy deletions get.
- Validation mirrors the backend (interval ≥ 1 h; first-run + interval
  required when enabled).

### 9.2 Link row surfacing

On each `DataTable` link row, add: last-reconciled relative time + next-run
time; an **open-findings** badge (count of `PROPOSED` findings) that links
into the review modal; and a **Reconcile now** action in the existing
`ActionMenu`.

### 9.3 Findings review modal

A new modal (sibling to the existing events modal), reusing the empty-state
+ table conventions established repo-wide:

- Header: run summary (source/target counts, missing / drift / extra /
  suppressed chips), status filter, type filter, **Refresh**.
- Table (one row per finding): type badge, source DN, target DN, suggested
  action, and an expandable **diff** cell:
  - MISSING → the attributes that would be added.
  - DRIFT → per-attribute *current target* vs *expected (source)* values.
  - EXTRA → the target entry that would be deleted.
- Per-row checkbox + bulk select (reuse `DataTable` `selectable`), then
  **Apply selected** / **Dismiss selected** — exactly the
  evaluate→selective-apply UX already used for profile group-compliance.
- Applied findings link to their `replication_events` row in the event log,
  so the operator can watch delivery.

### 9.4 API typing

`replication.js` is currently untyped. Add the new endpoints there; if the
reconciliation DTOs are added to the OpenAPI spec, prefer generated
`components['schemas'][...]` types in the consuming `lang="ts"` view (the
predecessor's replication types are not yet in `openapi.d.ts`, so local
interfaces are acceptable in the interim, matching current practice).

## 10. Audit

New `AuditAction.RECONCILIATION_*` subfamily (sibling to the design doc's
planned `REPLICATION_*`), recorded via
`AuditService.record(principal, dirId, action, dn, detail)`:

- `RECONCILIATION_CONFIG_UPDATED` — enable/disable/mode/schedule change
  (detail: the changed fields).
- `RECONCILIATION_RUN_STARTED` / `RECONCILIATION_RUN_COMPLETED` — detail
  carries run id, trigger, and the count summary.
- `RECONCILIATION_FINDING_AUTO_CORRECTED` — auto-correct mode applied a
  finding (detail: type + DN + event id).
- `RECONCILIATION_FINDING_APPLIED` / `RECONCILIATION_FINDING_DISMISSED` —
  operator action (detail: type + DN + run id).

The `dn` argument is the affected `target_dn`; the `detail` discriminator
carries `findingType` + `runId` so variants of the same operation reuse one
action value rather than proliferating actions (per the audit convention).
Actual target writes still emit the existing replication delivery audit
records — reconciliation audit is the *decision* trail, the worker's is the
*application* trail; `event_id` ties them together. `auditLabels.js`'s
humanizer fallback renders these automatically; add explicit labels for the
most-shown ones.

## 11. Dashboard surfacing (optional, follows the predecessor §5)

Gated behind `Entitlement.DIRECTORY_SYNC`, via `UnifiedDashboardService`:

- **AwarenessItem `RECONCILIATION_DRIFT_OPEN`** — emitted when any link has
  `PROPOSED` findings (review mode). Title: *"Reconciliation found drift on
  N links"*; link → `/superadmin/directory-sync?findings=open`. Awareness
  (informational), not an action item, to avoid alarm fatigue from expected
  drift.
- Optionally a `SummaryMetrics.reconciliationFindingsOpen` count card,
  amber when > 0. Auto-correct links won't accumulate open findings, so
  this primarily reflects review-mode links.

No new EE alerting hook in v1 (the predecessor defers replication alerting
to `ee/alerting` P4; a "reconciliation-drift-exceeds" rule would land there
later, out of core).

## 12. Phases

| Phase | Deliverable |
|---|---|
| **R-P0 — Config + schema** | Migration (§5), `ReplicationLink` fields + `EnqueueSource.RECONCILIATION`, request/response DTO additions, validation, UI config section (§9.1), audit `CONFIG_UPDATED`. No comparison yet. Tests: DTO round-trip, validation (interval floor, enabled-requires-schedule), migration. |
| **R-P1 — Engine + scheduler + AUTO_CORRECT** | `ReconciliationReadOps` paged subtree read, `ReconciliationService` compare/classify, the `@Scheduled` sweeper + single-flight + catch-up (§6), `reconciliation_runs`, shadow-suppression, enqueue-as-event for auto-correct, `RUN_*` + `FINDING_AUTO_CORRECTED` audit, **manual "Reconcile now"** endpoint + button. Tests (in-memory LDAP): missing→ADD, drift→MODIFY shape, extra gated by `delete_action` (IGNORE/REVIEW/AUTO), suppression vs a live event, catch-up math, single-flight, exclusion set, mapping rename/template parity with replication. |
| **R-P2 — REVIEW mode + operator UI** | `reconciliation_findings` persistence, findings list/apply/dismiss endpoints (§8), the review modal + selective apply (§9.3), row surfacing (§9.2), `FINDING_APPLIED/DISMISSED` audit. Tests: MockMvc authz + selective apply count + status transitions; Vitest for the modal (mock api, pinia). |
| **R-P3 — Retention + dashboard** | Retention sweep for runs/findings (mirror `ReplicationEventRetentionScheduler`), dashboard awareness item (§11), `auditLabels` entries. |
| **R-P4 — (deferred)** | MODIFY_DN/rename reconciliation; bidirectional; scripted attribute authority; EE alerting rule. |

## 13. Known limitations (accepted for v1)

- **Snapshot, not transactional** — source and target are read at slightly
  different instants; a change landing mid-run may show as a one-cycle
  finding that the next run clears. Shadow-suppression (§7.5) covers the
  app's own in-flight writes, not third-party concurrent writes.
- **No rename detection** — a renamed source entry reads as EXTRA (old DN)
  + MISSING (new DN) rather than a MODIFY_DN. Deferred to R-P4. Net effect
  is still correct (delete-old + add-new) once the EXTRA delete is applied
  (per `reconcile_delete_action`).
- **Scope is the link's base DN** — entries outside `source_base_dn` /
  `target_base_dn` are invisible to reconciliation, by design.
- **Cost** — a full subtree compare is O(entries) reads on both sides each
  cycle; the operator's interval choice is the throttle, and `page-size` /
  `max-findings-per-run` bound a single run. No incremental/changelog-based
  reconciliation in v1 (a future `SOURCE_CHANGELOG` capture path would
  reduce the need for frequent full compares).
- **Password drift unobservable** — `userPassword` can't be read back from
  most targets; reconciliation never asserts password equality.

## 14. Reference files

```
core/src/main/java/com/ldapportal/ldap/replication/ReplicationReadOps.java        (extend: paged subtree read)
core/src/main/java/com/ldapportal/ldap/replication/DnMapper.java                  (reuse: source→target DN)
core/src/main/java/com/ldapportal/ldap/replication/AttributeMapper.java           (reuse: attr name/value mapping)
core/src/main/java/com/ldapportal/ldap/replication/ReplicationPayloadCodec.java   (reuse: corrective event payloads)
core/src/main/java/com/ldapportal/ldap/replication/ReplicationWorker.java         (pattern: @Scheduled claim/CAS/stale-reset)
core/src/main/java/com/ldapportal/ldap/replication/ReplicationEventRetentionScheduler.java (pattern: retention sweep)
core/src/main/java/com/ldapportal/entity/ReplicationLink.java                     (add reconcile_* fields)
core/src/main/java/com/ldapportal/entra/EntraSyncScheduler.java                   (pattern: per-directory interval polling)
core/src/main/java/com/ldapportal/service/ProvisioningProfileService.java         (pattern: evaluate → applySelective)
core/src/main/java/com/ldapportal/controller/directory/ProvisioningProfileController.java (pattern: selective-apply endpoint shape)
core/src/main/java/com/ldapportal/service/UnifiedDashboardService.java            (dashboard awareness item + entitlement gate)
core/src/main/java/com/ldapportal/entity/enums/AuditAction.java                   (RECONCILIATION_* subfamily)
core/src/main/java/com/ldapportal/core/entitlement/Entitlement.java               (reuse DIRECTORY_SYNC gate)
core/src/main/resources/db/migration/core/V7__directory_sync.sql                  (schema this extends)
frontend/src/views/superadmin/DirectorySyncView.vue                              (config section + findings modal + row surfacing)
frontend/src/api/replication.js                                                  (new reconciliation endpoints)
frontend/src/components/dashboard/auditLabels.js                                 (humanize RECONCILIATION_*)
```

## 15. Decisions & remaining questions

**Resolved (2026-05-31):**

1. **Default mode → `REVIEW`.** New links default to review-before-applying
   for missing/drift fixes.
2. **Deletion autonomy is an explicit operator choice**, modeled as
   `reconcile_delete_action` (`IGNORE` / `REVIEW` / `AUTO`, default
   `REVIEW`) — separate from `reconcile_mode`, so a link can auto-correct
   adds/drift while still holding target deletions for human review.
   Switching to `AUTO` requires a destructive-action confirmation.
3. **Minimum interval → 1 hour.** `reconcile_interval_secs ≥ 3600`, enforced
   by both the DB CHECK and the form.

**Still open:**

4. **Managed-attribute allow-list** — v1 compares all non-operational
   source attrs. Do any links need an explicit per-link allow-list in v1,
   or is that an R-P4 refinement? Proposal: defer.
5. **`resolved_by` account table** — confirm the operator-account
   table/column for the FK (the predecessor's event-operator actions use an
   account id; reuse the same).
