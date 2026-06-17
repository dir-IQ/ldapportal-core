<!-- SPDX-License-Identifier: Apache-2.0 -->
# Directory Sync — operator manual

**Status:** Shipped (engine core, app-intercept, changelog capture, reconcile, brownfield adoption; 2026-06-08).

Directory Sync keeps a **target** directory continuously convergent with a
**source** directory. It is a *membership engine*, not a change-replay queue:
for any entry it re-reads current source state, computes the desired target
state, diffs it against a stored index, and applies the minimal LDAP operation.
ADD / MODIFY / DELETE / rename / scope-enter / scope-exit are all outcomes of
one convergent operation, so duplicate, out-of-order, or missed signals all
heal to the same end state.

> **Supersedes the former `directory-replication.md`.** That document described
> an earlier queue-and-dead-letter replication subsystem, which was removed. Its
> concepts (`replication_events`, PENDING→IN_FLIGHT→DELIVERED, a retry ladder,
> dead-letter) no longer exist. Use this manual.

---

## 1. Architecture at a glance

```
            ┌─────────── change feeds ───────────┐
  portal write ─▶ SyncWriteCaptor (APP_INTERCEPT) │
  source changelog ─▶ SyncChangelogPoller (CHANGELOG)
  timer ─▶ SyncReconcileScheduler (anti-entropy)  │
            └──────────────┬─────────────────────┘
                           ▼
                 RecomputeEnqueuer  ──▶  recompute_request
                           ▼            (coalescing queue, PK-dedup)
                 RecomputeWorker (drains every ~10s)
                           ▼
                 RecomputeEngine.process(set, key)
            read source ─▶ MembershipFunction ─▶ diff vs sync_membership
                           ▼
                 apply idempotent op to TARGET
                           ▼
                 commit sync_membership row (APPLIED/FAILED/REVIEW)
                           ▼
                 ClosureResolver fan-out (referrers re-enqueued)
```

**One operation, three triggers.** All three feeds do the same thing —
enqueue `recompute(syncSet, key)` where `key` is a source DN or an identity.
The engine is the only thing that writes to the target. The reconcile sweep is
the *consistency floor*; the stream feeds are the *fast path*.

### Core building blocks

| Component | File | Role |
|---|---|---|
| `SyncWriteCaptor` | `ldap/sync/SyncWriteCaptor.java` | App-intercept: a successful portal write → `recompute(dn)`. |
| `SyncChangelogPoller` | `ldap/sync/SyncChangelogPoller.java` | Polls a source changelog, emits `recompute(targetDN)` per record. |
| `SyncReconcileScheduler` | `ldap/sync/SyncReconcileScheduler.java` | Timer that reconciles *due* sets. |
| `RecomputeEnqueuer` | `ldap/sync/RecomputeEnqueuer.java` | Writes onto the coalescing `recompute_request` queue. |
| `RecomputeWorker` | `ldap/sync/RecomputeWorker.java` | Drains the queue under a claim/settle lease. |
| `RecomputeEngine` | `ldap/sync/RecomputeEngine.java` | The convergent read→diff→apply→commit. |
| `MembershipReconciler` | `ldap/sync/MembershipReconciler.java` | Full enumeration + not-seen sweep. |
| `ClosureResolver` | `ldap/sync/ClosureResolver.java` | Re-enqueues DN-referrers so member/manager refs stay consistent. |

---

## 2. Enabling the feature (entitlement)

Directory Sync is gated by the **`DIRECTORY_SYNC`** entitlement. Without it:
the **Directory Sync** nav is hidden, `directorySyncEnabled` is `false` in
`/me`, all three schedulers no-op, and capture is inert. The
`DIRECTORY_SYNC` enum value is open-source and ships in every build.

Two ways to grant it:

### Option A — signed license (production)
Issue a license JWT that includes `DIRECTORY_SYNC` and point the app at it with
`ldapportal.license.path` (`LDAPPORTAL_LICENSE_PATH`). The JWT must verify
against the build's trust anchor (`license/license-public-key.pem`) or the app
**refuses to start**.

> If `ldapportal.license.path` is set, the file must exist and verify. A Docker
> bind mount of a missing path creates a *directory* there, which then fails to
> read — don't set a license path without a valid file.

### Option B — config override (self-host, no license)
```properties
# env: LDAPPORTAL_ENTITLEMENTS_GRANT  (CSV)
ldapportal.entitlements.grant=DIRECTORY_SYNC
```
Handled by `ConfiguredEntitlementsProbe`. Allow-listed to open-source
entitlements only; logs a loud startup warning; independent of any license
file. The dev `compose.yaml` defaults this on.

### Verify
```bash
docker compose logs app | grep -i "Granting entitlement"
#   → Granting entitlement(s) [DIRECTORY_SYNC] via ldapportal.entitlements.grant …
curl -s localhost:9090/api/v1/me | grep -o '"directorySyncEnabled":[a-z]*'
#   → "directorySyncEnabled":true
```

> **Rebuild, don't just restart.** The `app` image bakes a host-built JAR.
> After backend/entitlement changes:
> `./mvnw clean package -DskipTests && docker compose up -d --build app`.

---

## 3. Configuration model

Two levels. Configure under **Directory Sync** (superadmin). All config is
superadmin-only and `DIRECTORY_SYNC`-entitled.

### 3.1 Sync link — *where* (source → target pair)

| Field | Notes |
|---|---|
| **Display name** | Label only; shown in inventory and logs. |
| **Source directory** | Must differ from target (self-pairs rejected). |
| **Target directory** | Where entries are created/updated/deleted. |
| **Capture mode** | `APP_INTERCEPT` (capture portal writes) or `CHANGELOG` (poll source changelog). See §4. |
| **Changelog format** | Required for `CHANGELOG`. Currently `DSEE_CHANGELOG` (DSEE / `cn=changelog`). |
| **Changelog base DN** | Required for `CHANGELOG`, e.g. `cn=changelog`. |
| **Enabled** | When off, the link's sets are not processed (engine and poller skip a disabled link). |

Validation (`SyncConfigService.validateLink`): source ≠ target; both
directories must exist; `CHANGELOG` requires both `changelogFormat` and
`changelogBaseDn`. `APP_INTERCEPT` clears any changelog config.

### 3.2 Sync set — *what* (selection + projection), nested under a link

| Field | Meaning |
|---|---|
| **Name** | Label only; does not affect matching. |
| **Identity key** | Source attribute used as each entry's stable identity (`entryUUID`, `objectGUID`, …). Blank → directory-type default. **Avoid mutable attributes like `mail`** — identity must never change for the same entry. |
| **Source scope base DN** | Base DN under the source to enumerate / match against. Blank → the directory's own base DN. |
| **Source scope** | LDAP scope under the base: `SUB` (default), `ONE`, `BASE`. |
| **Applicability filter** | RFC 4515 filter selecting which source entries belong, e.g. `(&(objectClass=inetOrgPerson)(employeeType=staff))`. Validated for syntax at save. |
| **Target base DN** | Base DN under the target where matched entries are placed. |
| **Reference attributes** (csv) | DN-valued attributes whose values are rewritten to target DNs and whose referrers are re-driven on change, e.g. `member,uniqueMember,manager`. |
| **Source anchor attribute** | Target attribute that stores the source identity, used to **adopt** pre-existing target entries (brownfield). Ambiguity → REVIEW. See §6. |
| **Delete policy** | `DELETE` (remove target when an entry leaves membership) or `REVIEW` (quarantine for an operator instead of auto-deleting). |
| **Reconcile cadence (seconds)** | How often this set is fully reconciled. Blank → global default (`3600`). |
| **Attribute mapping** | Optional transform rules: `sourceAttr` → `targetAttr` (blank = same name), `valueTemplate` (blank = passthrough; `${value}` substitutes). |

Validation (`SyncConfigService.validateSet`): base DNs must parse; the filter
must compile; transform rules require a valid LDAP `sourceAttr`, no duplicate
source attrs (first-wins would silently shadow), valid `targetAttr`, and a
`valueTemplate` containing no token other than `${value}`.

> **Identity key is the expensive-to-change decision.** It is written into every
> target entry as the source anchor and is the join key for the whole index.
> Get it right before any real sync runs; changing it later re-anchors everything.

---

## 4. Capture modes

### 4.1 `APP_INTERCEPT`
Every successful write *made through LDAP Portal* against the source enqueues a
`recompute(dn)` for each enabled set whose scope contains the DN
(`SyncWriteCaptor.onWrite`). It runs **after** the source write commits and
**never throws back** into the caller — a capture failure is swallowed and the
reconcile sweep is the backstop.

> **Out-of-band changes aren't captured by this mode.** Changes made directly
> against the source by other tools are invisible to app-intercept. Use
> `CHANGELOG` mode, or rely on the reconcile sweep, to pick them up.

### 4.2 `CHANGELOG`
`SyncChangelogPoller` polls each `CHANGELOG`-mode link's source changelog
(`fixed-delay ~15s`), reading up to 500 records per pass. For each record it
emits `recompute(targetDN)` — *including DELETE records* (the engine re-reads
the source, finds it absent, and deletes via the index). The lossy `changes`
blob is **ignored**; convergence makes a bare "this DN changed" signal
sufficient, so there is no exactly-once dedup, no FIFO, no LDIF reconstruction.

The poller maintains a cursor, an HA poll-lease, and a **health** state
(see §7.3). On a detected gap it triggers a reconcile to re-derive state.

---

## 5. The engine — what actually happens on a recompute

`RecomputeEngine.process(syncSet, key)`:

1. **Resolve** the source entry and identity. If `key` is a DN that's now gone
   but the identity is still tracked, it re-searches by identity so a **rename
   converges as a MODDN**, not a destructive delete+recreate.
2. **Evaluate membership** via `MembershipFunction`: applicability filter →
   placement (DN rewrite) → projected attributes (transform rules + source
   anchor + DN-reference remapping).
3. **Diff** against the `sync_membership` row.
4. **Hash gate** — if the projection's content hash and target DN are unchanged
   and the row is `APPLIED`, do nothing (no target read/write). This keeps
   reads-per-transition near 1 at rest and **terminates closure cascades**.
5. **Apply** the idempotent operation (add / modify / moddn / delete) under
   `withConnectionUnreplicated` (engine writes are *uncaptured*, so they don't
   loop back into the queue).
6. **Commit** the index row: `APPLIED`, `FAILED` (+ reason), or `REVIEW`.
7. **Closure fan-out** — if the target changed, re-enqueue referrers found by a
   source reverse-query over `referenceAttributes`, hash-terminated.

**Fault isolation:** an apply failure marks *that identity* `FAILED` and never
blocks any other identity (no head-of-line blocking). Optimistic/row-contention
races retry up to 4 times.

### Reconcile (anti-entropy)
`MembershipReconciler.reconcile(set)` enumerates the source scope, recomputes
each in-scope identity, then sweeps index rows it didn't see and recomputes
those (each re-read to confirm absence before any delete). The not-seen sweep
**only runs after a complete enumeration** — a partial scan never mass-deletes.

---

## 6. States — the operator's primary signal

Each tracked identity has one `sync_membership` row with a **state**
(`MembershipState`):

| State | Colour | Meaning | Operator action |
|---|---|---|---|
| **APPLIED** | green | Target reflects the projected desired state. | None. |
| **PENDING** | grey | A transition is queued/in-progress. | None unless persistent (see §7). |
| **FAILED** | red | Last apply failed; this identity is dead-lettered and retried, isolated. The **reason** column carries the server diagnostic. | Fix the cause, then **Recompute**. |
| **REVIEW** | amber | Quarantine: ambiguous correlation or a held delete. | Resolve, then **Recompute** or **Dismiss**. |

### When `REVIEW` happens
- **Brownfield ambiguity** — `sourceAnchor` matches multiple target entries:
  `ambiguous: N target entries carry sourceAnchor=<id>`.
- **Occupied placement DN** — an unanchored entry already sits at the placement
  DN: `unanchored entry already at placement DN <dn>`. The engine won't clobber it.
- **Held delete** — `deletePolicy=REVIEW` and the entry left membership:
  `scope-exit held for review (deletePolicy=REVIEW)`.

The engine is **conservative**: it never auto-overwrites or auto-deletes an
ambiguous target; it quarantines for a human.

### `FAILED` reasons
The reason is the server's own diagnostic (collapsed, ≤300 chars), prefixed
`apply failed: <ResultCode> — <detail>` or `delete failed: …`. Examples:
`apply failed: CONSTRAINT_VIOLATION — pre-encoded passwords are not allowed`,
`apply failed: OBJECT_CLASS_VIOLATION — missing required attribute 'sn'`,
`apply failed: INSUFFICIENT_ACCESS_RIGHTS — …`.

---

## 7. Monitoring

### 7.1 The UI (Directory Sync → expand a link → a set → its inventory)
- **Link / set tables** show a colour-coded **Health** rollup: red `N failed`
  (worst), amber `N review`, green `Healthy`, grey `No data` — derived from
  membership `stateCounts`.
- **Membership inventory modal** (per set): state-filter chips (All / Applied /
  Pending / Review / Failed with counts), a search box (identity or DN),
  per-row Identity / State / Source DN / Target DN / **Reason**, and per-row
  **Recompute** and **Dismiss** actions. Header has **Reconcile now** and
  **Verify contents**.
- **Untracked search** — searching a DN/identity that isn't tracked offers
  `Recompute "<term>"` to pull it in on demand.
- **Verify contents** — a belts-and-suspenders check that re-reads *both*
  directories and compares them directly (it ignores the membership index, so it
  catches drift the index believes is already converged). Reports counts of
  in-sync / **missing on target** / **orphaned on target** / **content drift**
  entries, with sample DNs for each. Read-only; never writes.

### 7.2 REST (superadmin, `DIRECTORY_SYNC`-entitled)
```
# Links
GET    /api/v1/superadmin/sync/links
POST   /api/v1/superadmin/sync/links
PUT    /api/v1/superadmin/sync/links/{id}
DELETE /api/v1/superadmin/sync/links/{id}

# Sets
GET    /api/v1/superadmin/sync/sets[?linkId=…]
POST   /api/v1/superadmin/sync/sets
PUT    /api/v1/superadmin/sync/sets/{id}
DELETE /api/v1/superadmin/sync/sets/{id}

# Inventory + operator triggers
GET    /api/v1/superadmin/sync/sets/{id}/memberships?state=&q=&page=&size=   # size capped 1..200
GET    /api/v1/superadmin/sync/sets/{id}/preview          # dry-run reconcile plan (index-based, no writes)
GET    /api/v1/superadmin/sync/sets/{id}/verify           # independent source↔target compare (no writes)
POST   /api/v1/superadmin/sync/sets/{id}/reconcile        # → {"enumerated": N}
POST   /api/v1/superadmin/sync/sets/{id}/recompute        # body {"key": "<dn-or-identity>"} → 202
DELETE /api/v1/superadmin/sync/sets/{id}/memberships/{identity}   # dismiss
```

### 7.3 Changelog health (`CHANGELOG` links)
Exposed on the link (`changelogHealth`, `changelogLastError`,
`changelogLastPolledAt`, cursor fields):

| Health | Meaning | What to do |
|---|---|---|
| `HEALTHY` | Polling; cursor advancing. | Nothing. |
| `LAGGING` | Behind source head by > 1000. | Watch; check target/worker throughput. |
| `STALLED` | Repeated poll errors — see `changelogLastError`. | Fix source reachability/perms. |
| `GAP_DETECTED` | Cursor fell off the bottom (changelog purged before read). Engine auto-reconciles. | Verify reconcile cleared it; consider larger source changelog retention. |
| `CURSOR_RESET` | Source changelog restarted (head < cursor). Cursor reset to 0. | Expect a re-scan; confirm convergence. |
| `DISABLED_CONFIG_ERROR` | Source dir or changelog strategy unavailable. | Fix link config / source. |

### 7.4 Logs
JSON logs (`logback-spring.xml`). Useful greps:
```
"FAILED —"                        # an identity's apply failure + reason
"quarantined for REVIEW"          # quarantine + reason
"changelog gap detected"          # gap → reconcile
"Released ... stale recompute claim"   # worker crashed mid-process and recovered
"Scheduled reconcile of sync set ... failed"
```

> **Audit note (current state):** sync config/operator actions are **not yet**
> emitted as `sync.*` audit actions — that wiring is deferred. Today the
> operational record is the log + the live `sync_membership` state. Do not point
> operators at audit-log trace links for sync (those existed only in the removed
> replication subsystem).

---

## 8. Maintenance & tuning

### 8.1 Schedulers and their knobs
All are `@Value` with inline defaults; override via env (Spring relaxed binding,
e.g. `ldapportal.sync.worker.fixed-delay-ms` → `LDAPPORTAL_SYNC_WORKER_FIXED_DELAY_MS`):

| Knob (property) | Default | Effect |
|---|---|---|
| `ldapportal.sync.worker.fixed-delay-ms` | `10000` | Recompute-queue drain interval. |
| `ldapportal.sync.worker.stale-reset-ms` | `60000` | Stale-claim sweep interval. |
| (constant) batch size | `200` | Requests drained per pass. |
| (constant) stale-claim threshold | `10m` | Reclaim requests left claimed by a crashed worker. |
| `ldapportal.sync.reconcile.initial-delay-ms` | `120000` | Delay before first reconcile sweep. |
| `ldapportal.sync.reconcile.tick-ms` | `60000` | Reconcile sweep tick (checks which sets are *due*). |
| `ldapportal.sync.reconcile.default-cadence-seconds` | `3600` | Default per-set reconcile cadence (overridden per set). |
| `ldapportal.sync.changelog.fixed-delay-ms` | `15000` | Changelog poll interval. |
| (constant) changelog batch | `500` | Records per poll. |
| (constant) lag threshold | `1000` | Above this lag → `LAGGING`. |
| (constant) stale poll lease | `5m` | Reclaim a poll lease from a dead instance. |

**Tuning guidance:** lower the worker/poll delays for lower latency at higher
DB/source load; raise per-set reconcile cadence for large, stable sets to cut
enumeration cost; the reconcile floor protects correctness regardless.

### 8.2 High availability
Multi-instance safe by design: the recompute queue uses an atomic
claim/settle lease (stale claims reclaimed after 10m), and changelog polling
uses a per-link poll-lease (stale lease reclaimed after 5m). Run as many app
instances as you like; no leader election needed.

### 8.3 Schema (Flyway-owned; `db/migration/core`)
| Table | Holds |
|---|---|
| `sync_links` | source/target dirs, capture mode, changelog config + health/cursor. |
| `sync_set` | selection + projection config per set. |
| `sync_membership` | one row per identity per set: source/target DN, content hash, state, fail reason, scan epoch, version. (Renamed from `membership` in `V7`.) |
| `recompute_request` | coalescing trigger queue, PK `(sync_set_id, request_key)`. |

Deleting a set cascades its `sync_membership` + `recompute_request` rows (FK
`ON DELETE CASCADE`). A link can't be deleted until its sets are deleted.

> No entity-only schema changes — every change is a new Flyway migration;
> Hibernate is `validate` only.

### 8.4 Capacity / table growth
`sync_membership` is bounded by the number of in-scope identities (one row
each) — it does **not** grow per change like an event log. `recompute_request`
is self-draining and PK-deduped; a persistent backlog there means the worker is
behind (raise throughput or lower batch latency).

---

## 9. Troubleshooting runbook

| Symptom | Likely cause | Action |
|---|---|---|
| Feature/nav missing | `DIRECTORY_SYNC` not granted | §2; check `/me` and startup log. |
| Nothing syncs at all | Link or set disabled; source/target missing | Check **Enabled** on both; confirm directories exist. |
| New portal writes not appearing | Source is `CHANGELOG`-mode, or write was out-of-band, or scope/filter excludes it | Confirm capture mode; check scope base DN + applicability filter; **Reconcile now**. |
| One identity `FAILED` | Target rejected the op | Read the **Reason** (schema, ACL, password policy); fix target; **Recompute** that row. |
| Identity `REVIEW` | Brownfield ambiguity / occupied DN / held delete | Resolve at target (dedupe anchors, clear DN, or confirm delete); **Recompute** or **Dismiss**. |
| Many `FAILED` after target change | Target outage/ACL change | Fix target; reconcile re-drives FAILED rows automatically next cadence, or **Reconcile now**. |
| Changelog `STALLED` | Source unreachable / changelog perms | See `changelogLastError`; fix source bind/ACL. |
| Changelog `GAP_DETECTED` | Source changelog purged faster than polled | Engine auto-reconciles; raise source changelog retention or poll more often. |
| `recompute_request` backlog growing | Worker behind | Lower `worker.fixed-delay-ms`, scale instances, check DB. |
| Wrong DN on target after a rename | (shouldn't happen) engine converges renames as MODDN | If stuck, **Recompute** by identity; reconcile re-derives. |
| Member/manager points at stale DN | reference attr not declared | Add the attr to **Reference attributes**; recompute the group/referrer. |

### Operator triggers cheat-sheet
- **Recompute (row or key)** — re-run one identity/DN now; use after fixing a
  `FAILED`/`REVIEW` cause.
- **Reconcile now (set)** — full enumeration + not-seen sweep; the heavy
  "make this set correct" button. Returns the count enumerated.
- **Verify contents (set)** — independent read-only audit: enumerate + project
  the source scope, enumerate the target base, and compare them directly by
  target DN. Flags entries missing on the target, orphaned on the target, and
  content drift — *without* trusting the membership index. Use to confirm a set
  is truly converged (or to expose drift the index can't see). Never writes.
- **Dismiss (row)** — drop the index row *without touching the target*; use to
  clear a quarantine you've handled out-of-band. (The next reconcile/recompute
  may re-derive it if the source still qualifies.)

---

## 10. Maintenance SQL appendix (DB-level monitoring)

For operators with read access to the application Postgres, when the UI isn't
reachable. (Read-only queries; substitute schema if not `public`.)

```sql
-- Membership state summary per sync set
SELECT ss.name, m.state, COUNT(*) AS cnt
FROM sync_set ss
LEFT JOIN sync_membership m ON m.sync_set_id = ss.id
GROUP BY ss.name, m.state
ORDER BY ss.name, m.state;

-- Failed identities with the server diagnostic
SELECT m.sync_set_id, m.identity, m.source_dn, m.target_dn, m.fail_reason
FROM sync_membership m
WHERE m.state = 'FAILED'
ORDER BY m.sync_set_id;

-- Quarantined (REVIEW) identities and why
SELECT m.sync_set_id, m.identity, m.target_dn, m.fail_reason
FROM sync_membership m
WHERE m.state = 'REVIEW'
ORDER BY m.sync_set_id;

-- Recompute-queue backlog per set (a persistent backlog = worker behind)
SELECT ss.name, COUNT(*) AS queued,
       MIN(rr.enqueued_at) AS oldest,
       COUNT(*) FILTER (WHERE rr.claimed_at IS NOT NULL) AS claimed
FROM recompute_request rr
JOIN sync_set ss ON ss.id = rr.sync_set_id
GROUP BY ss.name
ORDER BY queued DESC;

-- Changelog poll health and lag (CHANGELOG links)
SELECT l.display_name,
       l.changelog_health,
       l.changelog_last_polled_at,
       l.changelog_last_error,
       (l.changelog_source_last_change_number - l.changelog_last_change_number) AS lag
FROM sync_links l
WHERE l.capture_mode = 'CHANGELOG'
ORDER BY l.display_name;

-- Sets overdue for reconcile (last run older than their cadence; blank cadence = 3600s)
SELECT ss.name, ss.reconcile_last_run_at, ss.reconcile_cadence_seconds
FROM sync_set ss
WHERE ss.enabled
  AND (ss.reconcile_last_run_at IS NULL
       OR ss.reconcile_last_run_at
          + make_interval(secs => COALESCE(ss.reconcile_cadence_seconds, 3600)) < now())
ORDER BY ss.reconcile_last_run_at NULLS FIRST;
```

> These are diagnostic reads only. Drive changes through the UI/REST so the
> engine stays the single writer of `sync_membership` and target directories.

---

## 11. Limitations & invariants

- **Unidirectional.** A link is one-way; run a second link for the reverse
  direction only with care (the engine's own writes are uncaptured, but two
  app-intercept links between the same pair can interact).
- **App-intercept sees only portal writes.** Out-of-band source changes need
  `CHANGELOG` mode or the reconcile floor.
- **Identity must be stable and unique per scope.** A mutable/duplicate identity
  key breaks correlation; the engine quarantines what it can't disambiguate.
- **Convergence, not history.** There is no per-change audit/event trail in the
  sync subsystem today (see §7.4) — the record is current index state + logs.
- **Changelog format:** currently `DSEE_CHANGELOG` only; other feeds (DirSync,
  syncrepl, Entra delta) are planned, not shipped.
