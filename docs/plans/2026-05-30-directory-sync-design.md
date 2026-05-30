# Directory sync — design plan

- **Date:** 2026-05-30
- **Status:** Approved — pending implementation
- **Scope:** Asynchronous fan-out of app-initiated LDAP writes to one or
  more configured replica directories, with monitoring + dashboard
  surfacing for queue health and per-event status.
- **Audience:** Written to hand to a fresh Claude Code session in the
  `ldapportal-core` repo. Self-contained; assumes no prior conversation.
  Paths are relative to the repo root.
- **Branch:** `feat/directory-sync` (cut from main at `3587542`).

## 1. Goal

Operators configure one or more **replication links** from a source
directory to a target directory. Every add/modify/delete/modifyDN the
portal performs against the source — via any code path, including LDIF
import and bulk attribute update — is queued and asynchronously
applied to the target. Failures don't roll back the source write; they
surface on the Directory Sync page and on the dashboard.

The user-facing promise is:

> "Anything I do in this app on the source directory will be applied to
> the target directory shortly afterwards. If it can't be applied I'll
> see it on the Directory Sync page with the reason, and I can retry or
> skip it."

## 2. Design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Capture point | **`LdapConnectionFactory.withConnection` interception via a `ReplicatingLDAPConnection` wrapper** | All app writes funnel through `withConnection(dc, op)`, including the higher-level LDIF import + bulk attribute update services that would slip past a `ProvisioningInterceptor`. Wrapper delegates everything; on each successful `add`/`modify`/`delete`/`modifyDN` call it appends to a per-call enqueuer. Reads pass through with no overhead. `openUnboundConnection` (Test Connection one-shot) is NOT wrapped. |
| Source → target write coupling | **Decoupled** — source write commits first; target write is queued in Postgres | Target downtime can't block portal saves. Durable queue means a portal restart doesn't lose writes. |
| Transport | **Postgres `replication_events` table** with `@Scheduled` worker drainer | Existing `outbox_entry` / `OutboxStatus` pattern is the same shape — reuse the dispatch convention. |
| Ordering | **Per-link FIFO** by enqueue time | A SyncEvent only progresses once all earlier events for the same link have completed or been skipped. Prevents reorder-induced "modify on deleted entry" type failures. |
| Failure handling | **Exponential backoff + max retries → DEAD_LETTERED**; operator can retry / skip / acknowledge | Stay visible in the UI until acknowledged. |
| Topology | **N source → M target** per replication link, separate row per pair | Per-pair enable/disable; per-pair attribute mapping. |
| **DN mapping** | **Default: identity** (source base DN == target base DN). Optional per-link substring substitution (sourceBase → targetBase). | The "OUD with same DIT structure on two machines" case wants zero config; anything else is one optional field. |
| **Attribute mapping** | **Default: identity** (no rename, no value transformation). Optional per-link list of `{ sourceAttr, targetAttr, valueTransform? }` rules. v1 supports rename + simple value-template substitution (no scripting). | Common case is zero config; the cross-vendor case (`uid` → `sAMAccountName`) needs only rename; the hard cases (multi-valued → JSON, password format conversion) stay out of v1. |
| Conflict policy | **Last-writer-wins per event**, no merge | Per-link toggle `auto_create_on_missing`: when MODIFY targets an entry the target doesn't have, optionally backfill via source SUB read + ADD. Default off. |
| Loop prevention | **None** (deferred) | Operator must not configure A→B + B→A. Documented in the create-link form's hint text. |
| **Future changelog compatibility** | `replication_events` table is the dispatch queue regardless of enqueue source. A future `ChangelogReplicationEnqueuer` would write the same shape from polled `cn=changelog` entries; the worker is source-agnostic. A small `enqueue_source` column (`APP_INTERCEPT` / `SOURCE_CHANGELOG`) on the event row carries provenance for diagnostics. | Lets changelog-based capture land later as an additive feature without schema migration. |
| Auth | **SUPERADMIN only** for both link CRUD and event operations (retry/skip/acknowledge) | Misconfigured replication can silently corrupt a target. |

## 3. Phases

### P0 — Persistence + intercept (foundation, no UI)

- Flyway: `replication_links`, `replication_link_attr_mappings`,
  `replication_events` tables (§4).
- `ReplicationLink` + `ReplicationEvent` entities, `enqueue_source` enum.
- New `ReplicationEnqueuer` service: given `(sourceDirId, operation,
  dn, payload)`, finds enabled links where `sourceDirId` matches,
  applies the DN + attribute mapping, persists one `ReplicationEvent`
  per matching link.
- New `ReplicatingLDAPConnection` — wraps `LDAPConnection`; delegates
  all methods; on successful add/modify/delete/modifyDN calls the
  enqueuer.
- `LdapConnectionFactory.withConnection` modified to pass the wrapped
  connection. `openUnboundConnection` unchanged. New
  `withConnectionUnreplicated(dc, op)` for the rare cases where
  intra-replication writes must NOT re-enqueue (worker's own target
  writes — see P1).
- Tests: wrapper-fan-out via in-memory LDAP; verify read passthrough
  has zero enqueue.

### P1 — Worker + delivery

- `@Scheduled` `ReplicationWorker` polling PENDING/FAILED events
  ordered by enqueue time, capped batch size, per-link FIFO guard.
- Per-event delivery rebuilds the operation against the target via
  `LdapConnectionFactory.withConnectionUnreplicated(targetDc, op)` (so
  target writes don't loop back).
- Backoff: 30s, 2m, 10m, 1h, 6h → DEAD_LETTERED.
- Optional auto-create-on-missing path (per-link flag).
- Tests: failure-then-success cadence, FIFO blocking, dead-letter
  transition, auto-create path, attribute mapping rename + value
  template.

### P2 — Operator UI + dashboard surfacing

- `frontend/src/views/superadmin/DirectorySyncView.vue`:
  - List of replication links — source / target / enabled / pending /
    failed / dead-lettered / lag (color-coded).
  - Per-link detail — event timeline, filter by status, retry / skip /
    acknowledge actions, attribute-mapping table editor.
- Sidebar nav under **Configure**: "Directory Sync".
- New backend endpoints under `/api/v1/superadmin/replication-links/*`
  and `/api/v1/superadmin/replication-events/*`.
- **Dashboard surfacing — see §5**.

### P3 — Audit + health (no alerting yet)

- New `AuditAction.REPLICATION_*` subfamily: `LINK_CREATED`,
  `LINK_UPDATED`, `LINK_DELETED`, `LINK_ENABLED`, `LINK_DISABLED`,
  `EVENT_DEAD_LETTERED`, `EVENT_RETRIED_BY_OPERATOR`,
  `EVENT_SKIPPED_BY_OPERATOR`, `EVENT_ACKNOWLEDGED`.
- The `auditLabels.js` humanizer fallback handles UI rendering
  automatically; explicit labels for the most-shown events.

### P4 — (deferred) ee.alerting hook

- Alert rule kind "replication-lag-exceeds" with operator-set
  threshold per link. Lives in `ee/alerting`, not in core. Out of
  scope for the first ship.

## 4. Data model

```sql
CREATE TABLE replication_links (
  id              UUID PRIMARY KEY,
  display_name    VARCHAR(255) NOT NULL,
  source_dir_id   UUID NOT NULL REFERENCES directory_connections(id),
  target_dir_id   UUID NOT NULL REFERENCES directory_connections(id),
  source_base_dn  VARCHAR(500),                -- null = identity (no DN rewrite)
  target_base_dn  VARCHAR(500),                -- null when source_base_dn is null
  enabled         BOOLEAN NOT NULL DEFAULT true,
  auto_create_on_missing BOOLEAN NOT NULL DEFAULT false,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (source_dir_id != target_dir_id),
  CHECK ((source_base_dn IS NULL) = (target_base_dn IS NULL))
);

CREATE TABLE replication_link_attr_mappings (
  link_id         UUID NOT NULL REFERENCES replication_links(id) ON DELETE CASCADE,
  source_attr     VARCHAR(255) NOT NULL,
  target_attr     VARCHAR(255) NOT NULL,
  value_template  VARCHAR(2000),               -- e.g. '${value}' for identity; '${value}@corp.com' for templated
  PRIMARY KEY (link_id, source_attr)
);

CREATE TABLE replication_events (
  id              UUID PRIMARY KEY,
  link_id         UUID NOT NULL REFERENCES replication_links(id) ON DELETE CASCADE,
  enqueue_source  VARCHAR(20) NOT NULL DEFAULT 'APP_INTERCEPT',  -- APP_INTERCEPT | SOURCE_CHANGELOG (future)
  operation       VARCHAR(20) NOT NULL,        -- ADD | MODIFY | DELETE | MODIFY_DN
  source_dn       VARCHAR(2000) NOT NULL,
  target_dn       VARCHAR(2000) NOT NULL,
  payload         JSONB NOT NULL,              -- mapped, ready for the target
  status          VARCHAR(20) NOT NULL,        -- PENDING | IN_FLIGHT | DELIVERED | FAILED | DEAD_LETTERED | SKIPPED | ACKNOWLEDGED
  attempts        INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  last_error      TEXT,
  enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  delivered_at    TIMESTAMPTZ
);

CREATE INDEX idx_replication_events_pending
  ON replication_events(link_id, enqueued_at)
  WHERE status IN ('PENDING','FAILED');
CREATE INDEX idx_replication_events_link_status
  ON replication_events(link_id, status);
```

## 5. Dashboard surfacing

Three places, mirroring how `openSodViolations` / approval-pending /
alert-firing already land on the dashboard via
`UnifiedDashboardService`:

### 5.1 Metrics row (lightweight count)

Add to `SummaryMetrics`:
- `replicationEventsDeadLettered: long` — count of
  `replication_events.status='DEAD_LETTERED'` across all links,
  system-wide.

Renders as a small numeric card alongside `openSodViolations` /
`pendingApprovals`. Color: amber when > 0, red when > 25 (heuristic
threshold). Card link → `/superadmin/directory-sync`.

### 5.2 Action items panel (call to action)

Add `ActionItem` type `REPLICATION_DEAD_LETTERED`, emitted when count
> 0:
- **Severity**: HIGH
- **Title**: `"N replication events failed delivery"` (singular when
  N=1)
- **Subtitle**: `"On M replication links — review or acknowledge to
  clear"`
- **Link**: `/superadmin/directory-sync?status=DEAD_LETTERED`
- **Count**: N

This appears in the same "Action items" list operators already use
for pending approvals and overdue campaigns; the existing red-dot
badge on the sidebar Dashboard link picks it up automatically.

### 5.3 Awareness item (informational, lag-only)

Add `AwarenessItem` type `REPLICATION_LAG_HIGH`, emitted when any
enabled link's lag > 5 minutes (configurable, default heuristic):
- **Title**: `"Replication lag exceeds 5 minutes on N links"`
- **Detail**: `"Target directory may be unreachable or write-throttled"`
- **Link**: `/superadmin/directory-sync`

Awareness items are lower-priority than action items — they don't
trigger the red dot, they're informational.

### 5.4 Per-link rendering on the Directory Sync page

The dedicated page is the detail view — every link gets:
- A health chip (`✓ Healthy` / `⚠ N pending` / `✗ N dead-lettered`)
- A lag indicator (last delivered timestamp + relative time)
- A "View events" link to a filterable event log

### 5.5 Edition gating

The metric, action item, and awareness all gate behind a new
`Entitlement.DIRECTORY_SYNC`. Community edition: feature absent →
none of the dashboard surfacing fires. Community-plus-isva and
enterprise: enabled. Same pattern as `ALERTING` / `GOVERNANCE`.

## 6. Open questions resolved

| # | Question | Answer |
|---|---|---|
| 1 | DN + attribute mapping scope | Default identity; optional per-link config for both. v1 supports rename + template substitution; no scripting. |
| 2 | Bidirectional acknowledgement | Deferred. No loop prevention; documented in create-link form. |
| 3 | Hook level | `LdapConnectionFactory.withConnection` via wrapper. Catches LDIF import + bulk attribute update. |
| 4 | Future changelog compat | `enqueue_source` column on events; same table + worker. |
| 5 | Auth | SUPERADMIN only. |
| 6 | Known limitations | Accepted: out-of-band changes invisible; no initial backfill; no conflict detection. |

## 7. Known limitations (accepted)

- **Doesn't see out-of-band changes** — anything written to the source
  by a tool other than this app (ldapmodify, vendor admin UI, an HR
  sync writing directly, another application using the same
  directory). Target drifts silently.
- **No initial backfill** — turning a replication link on doesn't
  backfill the existing source state to the target. The first events
  captured are new ones from that moment forward. Initial sync would
  need an explicit "seed" operation (export source subtree → ldif-import
  target).
- **No conflict detection** — a write on the target that the portal
  didn't make won't be detected; the next replication event from the
  source may clobber it.

## 8. Reference files

```
core/src/main/java/com/ldapportal/ldap/LdapConnectionFactory.java                     (hook point — withConnection / openUnboundConnection)
core/src/main/java/com/ldapportal/core/events/                                        (outbox pattern to mirror)
core/src/main/java/com/ldapportal/service/DashboardService.java                       (where new metric + ActionItem lands)
core/src/main/java/com/ldapportal/service/UnifiedDashboardService.java                (where filter + entitlement gating lands)
core/src/main/java/com/ldapportal/entity/enums/AuditAction.java                       (REPLICATION_* family)
core/src/main/java/com/ldapportal/core/entitlement/Entitlement.java                   (DIRECTORY_SYNC)
frontend/src/components/dashboard/auditLabels.js                                      (humanizer covers REPLICATION_*)
frontend/src/components/dashboard/RecentActivityPanel.vue                             (event rendering)
frontend/src/components/AppLayout.vue                                                 (sidebar nav)
```

## 9. Implementation notes from P0

These choices materialised during P0 implementation and constrain
subsequent phases. The §2 design table remains canonical; this
section records concrete details a later implementer needs to know
without re-reading the P0 entity / wrapper code.

### 9.1 `LdapOperation` parameter type

`LdapOperation<T>.execute` takes `LDAPInterface`, not
`LDAPConnection`. Operation lambdas passed to either
`withConnection` or `withConnectionUnreplicated` receive the SDK's
interface — the {@code ReplicatingLdapInterface} wrapper for the
former, the raw {@code LDAPConnection} (which implements
{@code LDAPInterface}) for the latter. Callers that need extended-
operation methods not exposed on the interface (rare) must add them
to {@code ReplicatingLdapInterface}'s passthrough surface; do not
cast.

### 9.2 `ReplicationEvent.payload` JSONB shape

The worker (P1) deserialises this shape; the wire-stable schema is:

```
ADD:       { "attributes": { attrName: [v1, v2, ...], ... } }
MODIFY:    { "modifications": [
               { "type": "REPLACE|ADD|DELETE",
                 "name": targetAttrName,
                 "values": [v1, ...] },
               ...
             ] }
DELETE:    {}   (DN alone identifies the operation)
MODIFY_DN: { "newRdn": "...",
             "deleteOldRdn": true|false,
             "newSuperiorDn": "..." | null }
```

Attribute names and values are already DN- and value-mapped at
enqueue time ({@code DnMapper} + {@code AttributeMapper}). The
worker applies the payload to the target as-is — no per-event
mapping in P1.

### 9.3 Capture only on `ResultCode.SUCCESS`

{@code ReplicatingLdapInterface} invokes the enqueuer only when the
delegated LDAP operation returns {@code ResultCode.SUCCESS}. A
thrown {@code LDAPException} records nothing. Tests should not
assume an event is recorded for partial-failure result codes
({@code REFERRAL}, etc.); they aren't.

### 9.4 `LdapConnectionFactory` nullable `replicationEnqueuer`

The factory's second constructor parameter is nullable. Unit tests
that construct the factory directly pass {@code null}; the wrapper
short-circuits when the enqueuer is absent, so the rest of the
codebase behaves identically to pre-P0. P1's worker tests can do
the same if they want isolated coverage.

## 10. Effort estimate

| Phase | Estimate | Notes |
|---|---|---|
| P0 | 4 days | `ReplicatingLdapInterface` wrapper is the biggest piece; needs careful read-passthrough plumbing. |
| P1 | 3 days | Worker + backoff + per-link FIFO; reuses outbox conventions. |
| P2 | 5 days | Both pages + dashboard surfacing + attribute-mapping editor. |
| P3 | 1 day | Audit subfamily + labels. |
| **Total MVP** | **~13 days** | Implementation, not including review-cycle latency. |
