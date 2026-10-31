<!-- SPDX-License-Identifier: Apache-2.0 -->
# Directory Sync — operator manual

**Status:** Shipped (engine core, app-intercept, changelog capture incl. OUD cookie mode, reconcile, reconcile run history incl. incremental passes and the Summary/Detailed logging level, audited sync writes (with the attributes an update changed; filterable and exportable from the Audit Log), brownfield adoption, cross-link reference resolution, review actions, audited set and link deletes, references kept while their entries aren't synced, sync audit records naming the set and both directories, preview progress naming the entry being checked, preview notes for unsynced references on unchanged entries, not-in-set entries and live progress in the inventory, the APPLIED state shown as Synced, live counts for a running reconcile, faster full reconciles, row actions that show their result, an offer to reconcile after a set edit; 2026-10-31).

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

### Option A — config grant (self-host)
```properties
# env: LDAPPORTAL_ENTITLEMENTS_GRANT  (CSV)
ldapportal.entitlements.grant=DIRECTORY_SYNC
```
Handled by `ConfiguredEntitlementsProbe`. Allow-listed to open-source
entitlements only; logs a loud startup warning; independent of any license
file. The dev `compose.yaml` defaults this on. This is the path the
self-host runbooks (`deployment-aws.md`, `deployment-eks-gitlab-ecr.md`,
`deployment-fly.md`) use.

### Option B — license file
For builds distributed with a license file, point the app at one that includes
`DIRECTORY_SYNC` via `ldapportal.license.path` (`LDAPPORTAL_LICENSE_PATH`). It
must verify against the build's trust anchor (`license/license-public-key.pem`)
or the app **refuses to start**.

> If `ldapportal.license.path` is set, the file must exist and verify. A Docker
> bind mount of a missing path creates a *directory* there, which then fails to
> read — don't set a license path without a valid file.

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
| **Changelog format** | Required for `CHANGELOG`. `DSEE_CHANGELOG` (DSEE / `cn=changelog`, integer `changeNumber`) or `OUD_COOKIE_CHANGELOG` (OUD / OpenDJ `cn=changelog` in cookie mode). See §4.3. |
| **Changelog base DN** | Required for `CHANGELOG`, e.g. `cn=changelog`. |
| **Enabled** | When off, the link's sets are not processed (engine and poller skip a disabled link). |

Validation (`SyncConfigService.validateLink`): source ≠ target; both
directories must exist; `CHANGELOG` requires both `changelogFormat` (one of
the two above) and `changelogBaseDn`. `APP_INTERCEPT` clears any changelog
config. Changing the format or base DN of an existing link clears its cursor
and its changelog health/error: a cursor is only meaningful for the feed that
issued it. The base DN is stored trimmed.

### 3.2 Sync set — *what* (selection + projection), nested under a link

| Field | Meaning |
|---|---|
| **Name** | Label only; does not affect matching. |
| **Identity key** | Source attribute whose value identifies each entry for as long as it's synced; the sync matches a source entry to its target entry by it, across renames and moves. Blank → the source directory type's default: `entryUUID` (OpenLDAP, Oracle Unified Directory, IBM Directory Server, Generic LDAP), `objectGUID` (Active Directory), `id` (Entra ID). **Avoid mutable attributes like `mail`**: identity must never change for the same entry. |
| **Source base DN** | **Required.** Base DN under the source to read entries from, together with the source scope. Must be under one of the source directory's base DNs ([several roots](multi-root-directories.md): one set per root you want to sync). Sets saved before this field was required were given the source directory's base DN, which is what a blank meant. |
| **Source scope** | LDAP scope under the base: `SUB` (default), `ONE`, `BASE`. |
| **Applicability filter** | RFC 4515 filter selecting which source entries belong, e.g. `(&(objectClass=inetOrgPerson)(employeeType=staff))`. Blank → every entry under the source base DN. The editor's field takes several lines; an LDAP filter allows no whitespace between components, so line breaks and the whitespace around them are removed on save (the stored filter is one line). Validated for syntax at save. |
| **Target base DN** | Base DN under the target where matched entries are placed; the part of each DN below the source base DN is kept (source base `ou=people,o=src`, target base `ou=Users,o=dst`: `uid=jo,ou=people,o=src` → `uid=jo,ou=Users,o=dst`). Blank → entries keep their **source DNs**, which needs the source base DN to lie under one of the target directory's base DNs (checked on save). |
| **Reference attributes** (csv) | Attributes whose values are the DNs of other entries (a group's `member`, a user's `manager`). Blank → `member,uniqueMember,manager,owner,secDN`; a list replaces the defaults rather than adding to them. For each of these attributes the sync: **translates** each value to the DN its referent was synced to, through the membership index of **every** link (the referrer's own link first), so a reference into a subtree mirrored by another link — an IVIA `secDN` under `c=admin` pointing at a `c=us` entry when OUD exposes each suffix as its own directory connection — still remaps; **leaves out** a value whose referent no link has synced (or that different links mirror to *different* target DNs: ambiguous, logged); **re-drives** the referrers when a referent is synced, moved or renamed; and **compares** values as DNs, so case or spacing alone isn't a change. A value that doesn't translate is still **kept** where the target already holds it (compared as a DN), at: the DN the sync **last recorded** for that entry, in any state (a user whose last write failed, or one under a rewritten target base); else, for an entry inside a set's source scope on this target, its **planned** DN, but only while an entry exists there (one the sync deleted is removed from its groups too); else the DN **as written** (an entry no set covers, or a value naming no entry). A member the source no longer lists is removed, and its removal is part of the content hash, so it isn't skipped. If *none* of an attribute's values translate, the target's whole current value is kept. The preview says so either way (see §5). |
| **Source anchor attribute** | Target attribute where the sync writes each entry's source identity. On an entry's first sync the target is searched for that value: an entry carrying it is **adopted** (updated, and moved to its planned DN if elsewhere) instead of a duplicate being added. More than one match, or an entry without the anchor already at the planned DN → REVIEW (§6). Blank → no anchor: entries are matched by planned DN only (an entry already there is updated to match the source) and nothing extra is written. Set it when target entries may already carry the source identity (from an earlier sync or migration) or may sit at different DNs on the target, e.g. `employeeNumber`; the attribute must be allowed by the target entries' object classes and not used for anything else. |
| **Delete policy** | `DELETE` (remove target when an entry leaves membership) or `REVIEW` (quarantine for an operator instead of auto-deleting). Default `REVIEW`. |
| **Reconcile interval (seconds)** | How often this set is fully reconciled. Blank → this server's default, `ldapportal.sync.reconcile.default-cadence-seconds` (`3600`). Stored as `reconcileCadenceSeconds`. |
| **Attribute mapping** | Optional transform rules: `sourceAttr` → `targetAttr` (blank = same name), `valueTemplate` (blank = passthrough; `${value}` substitutes). |
| **Logging level** (`loggingLevel`) | How much of each run the run history keeps. **Summary** (default): counts and failure reasons only. **Detailed**: also every changed or flagged entry's DN (up to 25 per outcome on the run, all of them downloadable as CSV); a large run stores one row per entry. Applies to runs that start after it changes. See §5, *Run history*. |

The editor's info tips list these defaults from `GET /api/v1/superadmin/sync/sets/defaults`
(`SyncSetDefaultsProvider`), so they match the engine and the server's configuration.

Validation (`SyncConfigService.validateSet`): a source base DN is required and
must lie under one of the source directory's roots; base DNs must parse; the filter
must compile; transform rules require a valid LDAP `sourceAttr`, no duplicate
source attrs (first-wins would silently shadow), valid `targetAttr`, and a
`valueTemplate` containing no token other than `${value}`.

> **Identity key is the expensive-to-change decision.** It is written into every
> target entry as the source anchor and is the join key for the whole index.
> Get it right before any real sync runs; changing it later re-anchors everything.

**When an edit takes effect.** Saving a set doesn't touch any entry: a change to
what it manages, where it places entries or what it writes (applicability
filter, source base DN or scope, target base DN, reference or excluded
attributes, transform rules) applies at the set's next reconcile. Until then,
rows decided under the old settings stay as they are; for example, a deletion
held for review stays held even though the new filter takes the entry back. So
after such a save of an enabled set, the editor offers **Reconcile now**, which
queues a reconcile for the scheduler's next tick (within about a minute;
`POST …/sets/{id}/reconcile-request`). **Later** leaves it to the schedule: run
**Preview impact** first to see what it would do.

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
(`fixed-delay ~15s`), reading 500-record pages. For each record it
emits `recompute(targetDN)` — *including DELETE records* (the engine re-reads
the source, finds it absent, and deletes via the index). The lossy `changes`
blob is **ignored**; convergence makes a bare "this DN changed" signal
sufficient, so there is no exactly-once dedup, no FIFO, no LDIF reconstruction.

The poller maintains a cursor, an HA poll-lease, and a **health** state
(see §7.3).

- **Paging.** A pass keeps reading pages while the source has more — up to 20
  pages (10,000 records) or 5 s per link per pass — and reports `LAGGING` if it
  stops with more still pending. When the server cuts a search short at a
  size, time or lookthrough limit (the poller's own 500, or the bind account's
  server-side limits), the partial page is kept: it is an in-order prefix, so
  the cursor resumes after it. A cut-short search that returns nothing the
  cursor can advance by goes `STALLED` ("cut short by a server limit") instead
  of looping silently — raise the bind account's limits.
- **Reconcile requests.** On a detected gap (and, in cookie mode, the first
  poll or a refused cookie) the poller jumps the cursor to the source head and
  **requests** a reconcile of the link's sets: it increments their
  `reconcile_request_seq`, and the reconcile scheduler runs them on its next
  tick (~60s). A finishing reconcile records the request count it saw when it
  *started* as `reconcile_attempted_seq`, and also as `reconcile_handled_seq`
  if its source enumeration completed. So a request raised while a reconcile is
  already running (a manual "Reconcile now", or another instance) stays pending
  and gets its own run, and one whose reconcile couldn't enumerate the whole
  source is retried — at most every 5 minutes, so a persistently failing source
  isn't re-scanned every tick. These counters are written only by targeted
  updates, never by an entity save.
  The poller never reconciles inline, so a large or failing reconcile can't
  block polling or hold the cursor back.
- **Isolation.** One link's poll failure doesn't skip the other links in the
  pass.

### 4.3 Replicated sources — choosing the changelog format
The two formats differ in what the cursor means, which matters when the source
is a replication topology (e.g. clustered OUD) rather than a single server.

| | `DSEE_CHANGELOG` | `OUD_COOKIE_CHANGELOG` |
|---|---|---|
| Cursor | Integer `changeNumber` | Opaque `changeLogCookie` (persisted verbatim) |
| Issued by | Each server's own change-number indexer | Replication CSNs — global to the topology |
| Same change on two replicas | Different numbers | Same CSNs |
| Cursor survives failover / load-balanced source | **No** — a node switch looks like a gap (reconcile + jump to head) or a cursor reset (full replay); changes can be skipped until the next reconcile | **Yes** — any replica resolves the cookie |
| Head | Root DSE `firstChangeNumber` / `lastChangeNumber` | Root DSE `lastExternalChangelogCookie` |

**Use `OUD_COOKIE_CHANGELOG` for OUD (or OpenDJ) sources, and always when the
source connection has a secondary host.** With `DSEE_CHANGELOG`, point the
source at one fixed node.

> **The cursor is replica-safe; the engine's reads are not.** A change record
> only says "this DN changed" — the engine then re-reads the entry over its own
> pooled connection, ~10 s later (the worker interval). If that read lands on a
> replica that hasn't received the change yet, the engine applies the old state
> and nothing re-signals that DN: the target stays stale until the next
> reconcile (hourly by default). Normal replication lag is far below 10 s, but a
> replication backlog isn't. So for the **source**, prefer a **primary +
> secondary (failover)** connection over a round-robin / load-balanced
> address: with failover every connection uses the primary, so the exposure is
> limited to the moments around a failover; with round-robin it applies to
> every change. If you must use a load balancer, make it sticky or
> primary-preferred, and keep the reconcile cadence short.

How cookie mode works (`OudCookieChangelogStrategy`):

- The poller searches the changelog base (subtree) with the **External
  Changelog Request Control** (OID `1.3.6.1.4.1.26027.1.5.4`, marked critical)
  carrying the stored cookie, and advances to the `changeLogCookie` of the last
  entry read. A server without cookie-mode support fails the search rather than
  silently answering in change-number mode.
- **First poll** (no cursor yet): the cursor is seeded from the source's
  `lastExternalChangelogCookie` and a reconcile of every set on the link is
  requested (§4.2) — the retained changelog is not replayed. The head is read
  first, so the reconcile covers everything older than it.
- **Cookie refused** (`UNWILLING_TO_PERFORM` — older than the source's
  changelog purge window, or naming an unknown replicated domain): handled as a
  gap — re-seed from the head, request a reconcile, health `GAP_DETECTED`. If
  the freshly re-seeded cookie is refused again within an hour, the link goes
  `STALLED` ("refused again after re-seed") instead of requesting a reconcile
  on every poll.
- **Control not supported** (`UNAVAILABLE_CRITICAL_EXTENSION`): `STALLED` with
  the server's message — the source isn't serving cookie mode.
- **Lag** has no numeric form: `LAGGING` means the last pass stopped with more
  pending (§4.2). The numeric cursor columns stay empty, so cookie links don't
  contribute to the `ldapportal_sync_changelog_lag_max_changes` metric or the
  dashboard's lag figure — alert on `LAGGING` health instead (see
  `docs/observability.md`).

Cookie mode fixes the *source cursor* only. The engine still issues each
source/target read and write as a separate pooled operation, so for the
**target** prefer one writable node (optionally with a failover secondary), not
a round-robin/load-balanced address — replication lag between nodes can mislead
the engine's read-then-write decisions. The reconcile sweep remains the
consistency backstop. `OUD_COOKIE_CHANGELOG` is a sync-capture format only;
OUD audit sources keep using `DSEE_CHANGELOG`.

#### Other replicated directories
The per-server cursor problem isn't specific to OUD — any changelog that
numbers changes per server has it. Only OUD has a cookie mode to fix it.

| Source | Feed LDAP Portal can read | Cluster exposure | Guidance |
|---|---|---|---|
| **IBM Directory Server** | `cn=changelog` with integer `changeNumber` → `DSEE_CHANGELOG` | Same as DSEE above. IBM keeps the change log in a local, per-server database with locally assigned numbers (verify for your version), so a node switch either renumbers (gap / replay) or, if a server logs only its own writes, never shows changes made on the other node — only a reconcile catches those. There is no cookie equivalent. | Point the link at **one fixed node**: no secondary host, no load-balanced address. Keep a sensible reconcile cadence. |
| **389 DS / Red Hat DS** (as `GENERIC`, retro changelog) | `cn=changelog` with `changeNumber` → `DSEE_CHANGELOG` | Same as IBM — the retro changelog is per server. | Same as IBM. |
| **OpenLDAP** | None for sync — `slapo-accesslog` is audit-only, so sync links use `APP_INTERCEPT` + reconcile | Not exposed today (neither depends on a per-server position). An accesslog-based feed would be: each provider keeps its own accesslog, keyed by its own clock (`reqStart`). | The cluster-safe feed is syncrepl (RFC 4533), whose cookie carries a CSN per provider server ID — planned, not shipped. |
| **Active Directory** | None for sync — DirSync is audit-only | DirSync cookies are designed to be resumable against any DC. | — |

#### Audit sources have the same exposure
The audit reader (`LdapChangelogReader`) keeps no cursor: each poll it scans the
changelog and skips entries it has already recorded, keyed by the linked
directory (matched from the target DN) and the entry id — for DSEE-format
changelogs (OUD in change-number mode, IBM, 389 DS) that id is the per-server
`changeNumber`. An audit source has a single host. If that host is a **load-balanced address** in front of several
nodes, a node switch silently corrupts the audit trail, with no error:

- **Lost events** — node B's change 1234 is a different change from node A's
  1234, but the reader has already recorded "1234" and skips it.
- **Duplicate events** — the same change appears under different numbers on
  each node and is recorded twice.

**Point every DSEE-format audit source at one fixed node**, never a VIP. (A
replication-safe key exists — cookie-mode entries carry `replicationCSN`, the
same on every replica — but audit sources don't read cookie mode today.)

---

## 5. The engine — what actually happens on a recompute

`RecomputeEngine.process(syncSet, key)`:

1. **Resolve** the source entry and identity. If `key` is a DN that's now gone
   but the identity is still tracked, it re-searches by identity so a **rename
   converges as a MODDN**, not a destructive delete+recreate.
2. **Evaluate membership** via `MembershipFunction`: applicability filter →
   placement (DN rewrite) → projected attributes (transform rules + source
   anchor + DN-reference remapping). Placement replaces the source scope base
   with the target base and keeps the RDNs below it **as the source writes
   them** (case, escaping), so a target entry — and every `member` / `secDN`
   reference rewritten to it — reads exactly as on the source.
3. **Diff** against the `sync_membership` row.
4. **Hash gate** — if the projection's content hash and target DN are unchanged
   and the row is `APPLIED`, do nothing (no target read/write). This keeps
   reads-per-transition near 1 at rest and **terminates closure cascades**.
5. **Apply** the idempotent operation (add / modify / moddn / delete) under
   `withConnectionUnreplicated` (engine writes are *uncaptured*, so they don't
   loop back into the queue). A MODIFY is computed by reading the target and
   comparing values: DN-valued attributes (the set's reference attributes, and
   any the target schema gives DN syntax) by **canonical DN**, so a reference
   that differs only in form — `cn=A, o=B` vs `cn=a,o=b` — is not a change;
   every other value **exactly**, so an edit that only changes case (`john` →
   `John`) still replicates. Attribute aliases are resolved through the target
   schema (read once per directory, cached 10 minutes). A change of DN form
   alone is not a move either. The same comparison drives the dry-run preview
   and **Verify contents**.
6. **Commit** the index row: `APPLIED`, `FAILED` (+ reason), or `REVIEW`.
7. **Closure fan-out** — if the target changed, re-enqueue referrers found by a
   source reverse-query over `referenceAttributes`, hash-terminated. The query
   runs against the source of every enabled link (not just the changed one), so
   a cross-link referrer re-projects as soon as its referent lands.

**Fault isolation:** an apply failure marks *that identity* `FAILED` and never
blocks any other identity (no head-of-line blocking). Optimistic/row-contention
races retry up to 4 times.

### Reconcile (anti-entropy)
`MembershipReconciler.reconcile(set)` enumerates the source scope, recomputes
each in-scope identity, then sweeps index rows it didn't see and recomputes
those (each re-read to confirm absence before any delete). The not-seen sweep
**only runs after a complete enumeration** — a partial scan never mass-deletes.
The enumeration is paged (the source directory's *paging size*), so a scope
larger than the server's size limit still reconciles completely; one entry
whose recompute faults is logged and skipped, never aborting the run.
A reconcile is single-flight per set across instances (see §8.2), and it
stamps the set with targeted updates only, so editing the set while it runs
can't fail it.

**Throughput.** A reconcile does less per entry than a single recompute:

- the set, its link and both directories are loaded once and refreshed every
  second, not read again for each entry;
- the engine is handed the entry the scan already read, instead of reading
  the source again. This holds only while no source change has been enqueued
  for the set since the scan began (`sync_set.source_change_seq`, bumped in
  its own transaction by app-intercept before each enqueue, and by the
  changelog poller once per page of change records). Once one has,
  the rest of the run reads each entry afresh, so a change made during the run
  is never overwritten with what the scan saw;
- closure fan-out waits until the end of the run (phase *queuing entries that
  refer to changed ones*), and also runs when the run stops part way, so the
  changes it did make still reach their referrers. The changed entries are
  taken in the order they changed, 50 per search, and each search returns
  referrer DNs only. A referrer in the run's own set that the run recomputed
  successfully *after* the batch's last change is skipped; it already saw
  those changes. Every other referrer is queued for the worker, including one
  whose own recompute failed;
- a group member that doesn't translate yet (kept as the target has it, §5
  step 5) is looked up in bulk: one database query for where the sync last
  wrote every such member, then one one-level search per 100 target DNs under
  each parent to see which exist, instead of a query and a target read per
  member. A search that finds none of its batch while a sample of it (first,
  middle, last) does exist is not trusted, and that batch is read entry by
  entry. The same bulk query serves the preview and **Verify contents**;
- projecting an entry translates all its reference values (a group's
  members) through the index with one query per 500 values, not one each.

Each finished reconcile logs where its time went:
`reconcile of N source entries took …  (source scan …, source reads …, target
reads and writes …, reference updates …, index, audit and other work …)`. A
large *reference updates* or *source scan* share usually means the source has
no equality index on the reference attributes (`member`, `uniqueMember`,
`manager`, `owner`, `secDN`) or on the scope's attributes. A large *target*
share with a source anchor set usually means the target has no equality index
on the anchor attribute.

**Run history:** every reconcile of an enabled set is recorded in
`sync_reconcile_run` — trigger (`SCHEDULED`, or `MANUAL` with the operator),
start/finish, whether the source scan completed, the delete guard, managed rows
before → after, and what the engine did to each entry it processed, in the
preview's vocabulary: added / modified / moved / deleted / held for review /
already matched (applied with no target write) / forgotten / unchanged /
failed. *Faulted* entries (the recompute threw) retry next run; *skipped*
entries were not processed because the set or link was disabled while the run
was in progress — the run re-reads the set and link every second, so disabling
mid-run stops the writes within about a second but the run still walks the
rest of the plan. A running row
heartbeats about every 15 s; one silent for
`ldapportal.sync.reconcile.run-stale-after-ms` (default 15 min) belonged to a
server that stopped and reads as `FAILED`. The newest
`ldapportal.sync.reconcile.history-per-set` runs per set are kept (default 20);
deleting the set removes them. A disabled or unavailable set's reconcile is a
no-op and is not recorded.

**Incremental passes:** the changes applied *between* reconciles — each
recompute-worker pass over the queue (changelog records, in-app edits, closure
fan-out) — are recorded too, one `INCREMENTAL` run per set per pass, with the
same counts, samples, failures and instance. A pass is kept only when it did
something: wrote to the target, held an entry for review, forgot one, or
failed or errored on one; a pass that found everything already applied leaves
no row. They're pruned separately from reconciles
(`ldapportal.sync.reconcile.incremental-history-per-set`, default 50 per set),
so a busy changelog can't push the reconcile runs out. An incremental pass has
no source scan, delete guard, settings snapshot or verification; its managed
count is taken at the end.

Each run also keeps: the **instance** (host name; `HOSTNAME`, i.e. the pod
name under Kubernetes) that ran it; **when the plan was ready**, so scan time
(start → plan) and apply time (plan → finish) are separate; the set's
**settings and delete-guard limits** at run time; **failed and errored entries
grouped by reason** (the reason up to its `—` detail, with a count and one full
example); at the **Detailed** logging level, up to 25 **sample target DNs per
outcome** (DNs only, no attribute values); and the **drift** found by the content verification that runs right
after a scheduled or manual reconcile (missing / orphaned / content drift),
when that verification's scans completed.

**Logging level:** at **Summary** (the default) a run keeps its counts and
failure reasons and no DNs. At **Detailed** it also keeps the samples above and
every entry it changed or flagged — every outcome but *unchanged*, in
processing order, with the reason for a failure or review — in
`sync_reconcile_run_entry`, downloadable as CSV (`outcome,dn,reason`) from the
run's details. A `FAULT` row carries the source DN or identity whose recompute
threw, since it never reached a target DN. A large detailed run stores one row
per entry. Runs recorded before this setting existed keep whatever they kept
then (samples, and every DN where *Record every DN* was on; those sets were
moved to Detailed). The rows go with their run (pruning and set deletion cascade).

**Audit:** every target write a run makes — reconcile or incremental — is an
audit record on the **target** directory: `entry.create`, `entry.update`,
`entry.move` or `entry.delete`, with no actor (the engine writes with its own
bind account, whoever started the run) and `detail.source = directory-sync`,
`detail.operation = reconcile | incremental`, `detail.trigger`,
`detail.syncSetName`, `detail.sourceDirectory` and `detail.targetDirectory`
(the set's name and the link's directories' display names, as they were when
the run started), `detail.syncSetId` and `detail.runId`. An update also lists
the attributes it changed, `detail.attributes` (names only, never values;
empty for an add or a delete). The Audit Log table shows the names and leaves
out `syncSetId`; the CSV export's `detail` JSON keeps it. The run's id is also the records'
**correlation id**, so the audit log can pull up one run's writes together.

**Every sync write in a time period:** on the **Audit Log** page, pick the
target directory, set **From** / **To**, and set **Source** to **Directory
sync** (optionally narrow the actions to create / update / move / delete). The
page shows the newest 200 matching events; **Export CSV** downloads *every*
matching event (up to 50,000, newest first — if more match, the oldest are left
out and the page says so; narrow the range and export again). The CSV columns
are `occurredAt, action, directory, targetDn, actor, actorType, source,
operation, attributes, correlationId, origin, detail` (`attributes` is
`|`-joined; `detail` is the full JSON). The same export is
`GET /api/v1/audit/export.csv` with the log's filters
(`?directoryId=…&source=directory-sync&from=…&to=…`); its
`X-Audit-Export-Matched` / `X-Audit-Export-Truncated` headers give the match
count and whether it was cut short. With no `to`, the window ends when the
export starts.
Outcomes that wrote nothing (already matched, held for review, forgotten,
failed) aren't audited; the run history counts them. A held delete an operator
approves is audited separately with the operator as actor (§6). An initial
reconcile of a large set writes one audit record per entry it adds.

**Target ownership:** a target DN has exactly one owning index row per set
(`sync_membership` is unique on `(sync_set_id, target_dn)`). When an identity
leaves membership, the engine first checks whether another live row — in this
or any set writing to the same target directory — still owns that target DN;
if so it drops only the stale index row and leaves the entry alone. This is
what keeps a source entry that was deleted and re-created at the same DN (a
new `entryUUID`) from having its target deleted by the old identity's row.

**Renames:** a failed target MODDN (e.g. the new parent OU does not exist yet)
marks the row `FAILED` with the *old* target DN retained, so the retry is a
rename again — never an ADD at the new DN that would orphan the old entry.

---

## 6. States — the operator's primary signal

Each tracked identity has one `sync_membership` row with a **state**
(`MembershipState`). The UI shows `APPLIED` as **Synced**; the API, the
database and the logs keep the `APPLIED` name.

| State | Colour | Meaning | Operator action |
|---|---|---|---|
| **Synced** (`APPLIED`) | green | The sync wrote the projected desired state to the target and tracks it. It doesn't re-check the target: **Verify contents** does. | None. |
| **PENDING** | grey | A transition is queued/in-progress. | None unless persistent (see §7). |
| **FAILED** | red | Last apply failed; this identity is dead-lettered and retried, isolated. The **reason** column carries the server diagnostic. | Fix the cause, then **Retry**. |
| **REVIEW** | amber | Quarantine: ambiguous correlation or a held delete. | See *Resolving `REVIEW`* below. |

### When `REVIEW` happens
- **Brownfield ambiguity** — `sourceAnchor` matches multiple target entries:
  `ambiguous: N target entries carry sourceAnchor=<id>`.
- **Occupied placement DN** — an unanchored entry already sits at the placement
  DN: `unanchored entry already at placement DN <dn>`. The engine won't clobber it.
- **Held delete** — `deletePolicy=REVIEW` and the entry left membership:
  `scope-exit held for review (deletePolicy=REVIEW)`; or the reconcile's delete
  guard tripped: `scope-exit held for review (delete guard tripped)`.

The engine is **conservative**: it never auto-overwrites or auto-deletes an
ambiguous target; it quarantines for a human.

The inventory API classifies each `REVIEW` row as `reviewKind`
`PENDING_DELETE`, `AMBIGUOUS_MATCH`, `PLACEMENT_CONFLICT` (or `OTHER` for an
unrecognized reason), and the inventory names each row's actions for it.

### Resolving `REVIEW` (and the other row actions)

| Row | Actions |
|---|---|
| **Held delete** (`PENDING_DELETE`) | **Delete target** — approves the deletion: deletes the target entry now, whatever the set's delete policy or the delete guard. The source is re-read first: an entry back in scope is refused (400); a target entry another identity owns is kept and only this row dropped. Audited as `entry.delete` on the target directory (`detail.source = directory-sync`, `operation = approved-review-delete`, with the set and directory names as for a run's writes). Refused while the set or link is disabled. **Keep entry, stop syncing** — dismiss: the target entry stays but is no longer managed (verification then reports it *orphaned on target*). **Re-check** — recompute: re-applies it if the entry came back into scope; otherwise leaves it held under `deletePolicy=REVIEW`, or deletes it under `DELETE` (a single recompute doesn't apply the guard). |
| **Ambiguous match / placement conflict** | **Retry match** (recompute, after cleaning up the conflicting target entries) · **Stop tracking** |
| **FAILED** | **Retry** (recompute) · **Stop tracking** |
| **Synced / Pending** (`APPLIED` / `PENDING`) | **Re-sync** (recompute) · **Stop tracking** |

**Re-check**, **Retry match**, **Retry** and **Re-sync** queue a recompute of the
row, which the worker picks up within about ten seconds. The inventory then
watches the row for up to 20 seconds, refreshes it when it changes and says what
it became. If nothing changed, it says so; for a held delete that means the
entry is still outside the set's filter or scope.

**Stop tracking** (dismiss) drops only the index row — the target isn't touched.
An in-scope entry is picked up again from scratch by the next reconcile; an
out-of-scope one is left unmanaged. Destructive actions ask for confirmation.

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
- **Membership inventory modal** (per set): state-filter chips (All / Synced /
  Pending / Review / Failed with counts, each with a tooltip saying what it means, and **Not in set**, below), a search box (identity or DN),
  per-row Identity / State / Source DN / Target DN / **Reason**, and per-row
  actions named for the row's situation (see §6, *Resolving `REVIEW`*). Header has **Reconcile now**,
  **Re-check target** (see §9, *Target restored from a backup*) and **Verify contents**;
  each button's tooltip says how it differs from the other two (see the
  *Operator triggers cheat-sheet*, §9).
- **Progress while they run.** **Reconcile now**, **Re-check target** and
  **Verify contents** show what's happening, updated about every second
  (`ldapportal.sync.reconcile.progress-ms`, `ldapportal.sync.verify.progress-ms`):
  - a reconcile: *scanning the source*, then *applying entries: N of M* with a
    progress bar, then *checking managed entries no longer in the source*, then
    *queuing entries that refer to changed ones*, naming the entry it's on (a large group can take a while on its own: each
    member is looked up);
  - a content check: *reading the source* (entries read), *reading the
    target*, then *comparing entries: N of M*, naming the entry it's comparing.
    **Reconcile now** shows the check that follows it too;
  - **Re-check target** only queues a reconcile, so it shows *waiting for the
    next reconcile to start*, then that run's progress, and says what it did
    when it finishes (it stops waiting after 5 minutes).

  Each line shows the time elapsed and how long ago the run last reported. A
  reconcile already running when the inventory opens (a scheduled one) shows
  the same way. `GET …/sets/{id}/activity` returns the latest reconcile and
  content check with their progress.
- **Not in set** — why a reconcile's **Source entries** outnumber the set's
  **Managed** ones: the in-scope source entries the set doesn't manage, because
  they don't match the **applicability filter** (the scope base and other OUs,
  entries of other object classes, ones a clause excludes) or **can't be placed**
  under the target base. Entries without an identity value aren't counted as
  source entries at all. The chip counts them as of the latest complete
  reconcile and lists them with the reason; **Last reconcile** shows the count
  as *N not in set*, a link to the same list. Each run keeps the count per
  reason and up to 25 source DNs per reason whatever the logging level; at
  **Detailed** logging it keeps every one, so the list is complete. A run's
  **Details** show them by reason too. Entries are re-evaluated by every
  reconcile; this is a snapshot, not live.
- **Last reconcile** (top of the inventory modal) — the latest recorded run:
  status, trigger, when it started and how long it took, its outcome counts,
  managed rows before → after, and warnings for an incomplete scan, a tripped
  delete guard, faulted entries, or entries skipped because the set was
  disabled mid-run, plus the instance that ran it, scan vs. apply time, and the
  drift found right after it. **Recent runs** lists up to the last 10
  reconciles; **Incremental changes** lists the last 20 passes between them.
  **Details** (or a click on a run) shows everything recorded about that run:
  every count, failures grouped by reason, sample DNs per outcome (Detailed
  logging), and the settings it ran with — plus **Download all entries (CSV)**
  when the run recorded every DN. Refreshes after **Reconcile now**. While the
  latest run is still going (yours or a scheduled one), its counts read *So far
  (N of M entries)*, Managed shows *→ when the run finishes*, and the box reloads
  every few seconds until the run ends; the counts are saved about every second
  (`ldapportal.sync.reconcile.progress-ms`).
- **Untracked search** — searching a DN/identity that isn't tracked offers
  `Recompute "<term>"` to pull it in on demand.
- **Verify contents** — a belts-and-suspenders check that re-reads *both*
  directories and compares them directly (it ignores the membership index, so it
  catches drift the index believes is already converged). Reports counts of
  in-sync / **missing on target** / **orphaned on target** / **content drift**
  entries, with sample DNs for each. Read-only; never writes.
- **Saving in the set editor.** **Save** keeps the editor open, so you can
  edit, save and preview in one place. The preview runs on the **saved** set,
  so Preview impact is unavailable while the form holds unsaved edits. Saving a
  new set turns the editor into its edit view, with the preview available.
  **Close**, ×, or Escape asks before discarding unsaved edits.
- **Impact preview** (set editor) — the editor shows the set's latest
  preview run: when it ran and who started it, and its counts per outcome
  (would add, modify, move or delete, held for review, errors and no change),
  flagged *Preview again for current counts* when it's outdated. The counts
  are taken from the run's stored entries, so they always match its list.
  Works on a **disabled** set, and runs automatically as the confirmation step
  when you enable a set: if the run would delete entries (or the delete guard
  would trip) you're asked before it turns on.
- **Preview impact** (set editor; needs *manage directory sync*) — opens the
  full, paged list of what a reconcile would do to **each entry**: **Add**,
  **Modify**, **Move**, **Delete**, **Review**, **No change**, **Forget**,
  **Unchanged**, or **Error**. Click a row for the attribute-level changes
  (values removed `−` and added `+`), the source DN, the previous DN of a move,
  or the reason an entry would be held for review. *Changes only* (the default)
  hides entries that won't change: **Unchanged** (already applied, or already
  held for review) and **No change** (the target already matches). Details:

  - **A reference that can't be translated yet.** When none of a reference
    attribute's values point to a synced entry (say, an IVIA registration's
    `secDN` whose group isn't synced), the target's current value is kept,
    not deleted. The entry shows the note *"secDN kept as it is on the target:
    it points to …, which isn't synced yet"*, and is **No change** if that was
    its only difference (untick *Changes only* to see it). A new entry is added
    without the attribute (*"secDN left out"*). When only *some* values don't
    translate (a group with one member whose entry isn't synced), each such
    value the target already holds is kept (*"member: kept 1 value as on the
    target …"*) and the rest are left out (*"member: left out 1 value …"*);
    see *Reference attributes* in §3.2 for which DN it's kept at.
    The sync writes the values once the entries they point to are synced.
    An entry the set already applied and that hasn't changed since (**Unchanged**,
    which the preview doesn't re-read on the target) still says which values
    it doesn't write: *"member: 1 value not written, pointing to …, which isn't
    synced yet"* — the usual reason a group's new member doesn't appear on the
    target is that no set has synced (or adopted) that member's entry yet;
    one that merely exists on the target doesn't count.
    **Verify contents** doesn't count a kept value as drift.
  - **References follow this plan.** A referent this preview places resolves
    to where the plan puts it, and one the plan takes out of the set doesn't
    resolve, so its groups show the members rewritten or removed as a
    reconcile's follow-up passes would leave them.
  - **DN values are listed by DN.** For DN-valued attributes the list shows
    only values really removed or added: a REPLACE that writes
    `uid=a,ou=People,…` over `uid=a, ou=People, …` doesn't show that member as
    removed and added back.

  - **A background run.** Opening the dialog shows the set's latest preview, or
    starts one if there is none or the latest is outdated; **Run again** starts
    a fresh one. A run scans
    the source once, dry-runs every entry and stores the result, so paging
    reads a fixed snapshot — every page comes from the same plan, and nothing
    is re-scanned. The dialog shows progress while it runs: entries checked
    of the total (updated about every second, `ldapportal.sync.preview.progress-ms`),
    the entry being checked now, the time elapsed, and how long ago the run
    last reported. A large group can take a while on its own (each member is
    looked up), so the count can pause on it while *Now checking* names it.
    One run per set at
    a time (across app instances): starting while one is running joins it. A
    result is marked **outdated** once, after the run started, the set is
    edited or reconciled, its link is pointed at different directories, or
    either directory's connection settings are edited. Changes live sync
    applies between reconciles don't mark it outdated; run it again for those.
  - **How it predicts.** It dry-runs the sync engine's own decision code —
    brownfield adoption, the hash gate, ownership checks, the delete policy and
    the delete guard — with real target reads and recorded (not performed)
    writes. Nothing is written to the target or the index, and it works on a
    disabled set or link.
  - **Errors stay local.** An entry whose prediction fails (e.g. the target read
    for it times out) is listed as **Error** with the reason; the rest of the
    run carries on. If the set's filter can't be evaluated for an entry (an
    extensible-match filter the engine can't evaluate locally), the preview
    does what a reconcile does: it stops planning at that entry, marks the scan
    incomplete, and predicts no deletes — the note says which entry.
  - **Order.** Entries are listed by target DN, parents before their children.
  - **Values.** Secret attributes are always shown as `(redacted)`: any
    attribute whose name contains `password`, `pwd`, `secret`, `passphrase` or
    `privatekey` (`userPassword`, `unicodePwd`, `sambaNTPassword`,
    `pwdHistory`, …) plus known key and credential attributes (`userPKCS12`,
    `krbPrincipalKey`, `supplementalCredentials`, `msDS-KeyCredentialLink`,
    `olcRootPW`, `ibm-slapdAdminPw`, …). Binary values show as
    `(binary, N bytes)`, and each attribute lists at most 50 removed and 50
    added values (the rest are counted). Because a preview shows attribute
    values, it needs the *manage directory sync* permission; view-only
    superadmins still get the counts-only **Impact preview**.
  - **References.** DN references such as `member` resolve to target DNs through
    the membership index, and through entries this same run would add, matching
    the end state a reconcile reaches once groups are re-driven after their
    members land.
  - **Limits.** Each entry is predicted against the target and index as they
    were when the run reached it. Effects that only arise *during* a real run —
    a child whose parent is added in the same run failing because of apply
    order, a write the server rejects (schema, ACLs, password policy) — show as
    the planned action, not as the failure a real run might hit. Changes made
    after the run aren't reflected until you run it again.
  - **Cost and retention.** A run reads the whole source scope and the target
    entries that need comparing — about what a reconcile reads, without the
    writes. Runs execute on a small bounded pool (2 per app instance by
    default); when it's full, a new run fails at once with *try again*. Stored
    results (which hold the redacted attribute values) are deleted after 24
    hours, and with the set.

### 7.2 REST (superadmin, `DIRECTORY_SYNC`-entitled)
```
# Links
GET    /api/v1/superadmin/sync/links
POST   /api/v1/superadmin/sync/links
PUT    /api/v1/superadmin/sync/links/{id}
DELETE /api/v1/superadmin/sync/links/{id}                      # 400 while the link has sets; audited as sync.link_delete (see §9)

# Sets
GET    /api/v1/superadmin/sync/sets[?linkId=…]
POST   /api/v1/superadmin/sync/sets
PUT    /api/v1/superadmin/sync/sets/{id}
DELETE /api/v1/superadmin/sync/sets/{id}                       # audited as sync.set_delete (see §9)
GET    /api/v1/superadmin/sync/sets/{id}/delete-impact         # what a delete discards → {"name", "managed", "pendingReview", "reconcileRuns", "otherSets"}

# Inventory + operator triggers
GET    /api/v1/superadmin/sync/sets/{id}/memberships?state=&q=&page=&size=   # size capped 1..200
GET    /api/v1/superadmin/sync/sets/{id}/preview          # dry-run reconcile plan (index-based, no writes; works on a disabled set/link)
POST   /api/v1/superadmin/sync/sets/{id}/preview/run      # start a per-entry dry run in the background (joins a running one) → 202 + status; manage permission
GET    /api/v1/superadmin/sync/sets/{id}/preview/run      # latest run: status (RUNNING / COMPLETE / FAILED), progress (processed, total, currentDn, heartbeatAt, asOf), summary; manage permission
GET    /api/v1/superadmin/sync/sets/{id}/preview/entries?page=&size=&changesOnly=   # page of the completed run (404 if none); size 1..200 (default 50), changesOnly default true; a page past the end returns the last page; manage permission
GET    /api/v1/superadmin/sync/sets/{id}/verify           # independent source↔target compare (no writes)
GET    /api/v1/superadmin/sync/sets/{id}/activity         # latest reconcile run (phase, processed, currentDn, heartbeatAt) + latest content check (phase, processed, total, currentDn), asOf
GET    /api/v1/superadmin/sync/sets/{id}/not-in-set?q=&page=&size=   # in-scope source entries the set doesn't manage, as of the latest complete reconcile: total, reasons, entries (sourceDn, reason), allDnsRecorded
POST   /api/v1/superadmin/sync/sets/{id}/reconcile        # → {"enumerated": N}; recorded as a MANUAL run
GET    /api/v1/superadmin/sync/sets/{id}/reconcile-runs?limit=&kind=   # recent runs, newest first; limit 1..20 (default 10); kind RECONCILE | INCREMENTAL (default both)
GET    /api/v1/superadmin/sync/sets/{id}/reconcile-runs/{runId}  # one run in full: summary, settings, failureReasons, samples, allDnsRecorded (404 if not this set's run)
GET    /api/v1/superadmin/sync/sets/{id}/reconcile-runs/{runId}/entries.csv   # every entry the run changed or flagged: outcome,dn,reason (400 if the run kept only samples)
POST   /api/v1/superadmin/sync/sets/{id}/recompute        # body {"key": "<dn-or-identity>"} → 202
POST   /api/v1/superadmin/sync/sets/{id}/reconcile-request # queue a reconcile for the next scheduler tick, without waiting → {"reconcileQueued"}; manage permission
POST   /api/v1/superadmin/sync/sets/{id}/recheck-target   # forget applied content hashes + request a reconcile → {"entries", "reconcileQueued"}; manage permission
DELETE /api/v1/superadmin/sync/sets/{id}/memberships/{identity}   # dismiss ("stop tracking")
POST   /api/v1/superadmin/sync/sets/{id}/memberships/{identity}/approve-delete   # approve a held delete → {"outcome": DELETE|FORGET|ERROR, "targetDn", …}; 400 if not a held delete, back in scope, or the set is disabled
```

### 7.3 Changelog health (`CHANGELOG` links)
Exposed on the link (`changelogHealth`, `changelogLastError`,
`changelogLastPolledAt`, cursor fields):

| Health | Meaning | What to do |
|---|---|---|
| `HEALTHY` | Polling; cursor advancing. | Nothing. |
| `LAGGING` | Behind source head by > 1000 (cookie mode: the last pass stopped with more pending). | Watch; persistent `LAGGING` means the source writes faster than a pass drains (20 pages / 5 s per 15 s) — check the bind account's limits and source/network latency. |
| `STALLED` | Repeated poll errors — see `changelogLastError`. | Fix source reachability/perms. |
| `GAP_DETECTED` | Cursor fell off the bottom (changelog purged before read; cookie mode: source refused the cookie). Cursor jumps to the head; a reconcile is requested and runs on the next scheduler tick. | Verify reconcile cleared it; consider larger source changelog retention. With `DSEE_CHANGELOG` on a replicated source, a node switch also lands here — see §4.3. |
| `CURSOR_RESET` | Source changelog restarted (head < cursor). Cursor reset to 0. | Expect a re-scan; confirm convergence. |
| `DISABLED_CONFIG_ERROR` | Source dir or changelog strategy unavailable (cookie mode: Root DSE returned no `lastExternalChangelogCookie`). | Fix link config / source; enable the source's external changelog, and check the bind account can read that Root DSE attribute. |

### 7.4 Logs
JSON logs (`logback-spring.xml`). Useful greps:
```
"FAILED —"                        # an identity's apply failure + reason
"quarantined for REVIEW"          # quarantine + reason
"changelog gap detected"          # gap → reconcile requested
"changelog cookie refused"        # cookie-mode gap → reconcile requested
"Released ... stale recompute claim"   # worker crashed mid-process and recovered
"Scheduled reconcile of sync set ... failed"
"reconcile of ... source entries took"  # where a reconcile's time went (§5)
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
| `ldapportal.sync.reconcile.default-cadence-seconds` | `3600` | Default per-set reconcile interval (overridden per set). |
| `ldapportal.sync.reconcile.history-per-set` | `20` | Reconcile runs kept per set. |
| `ldapportal.sync.reconcile.incremental-history-per-set` | `50` | Incremental passes kept per set (pruned separately from reconciles). |
| `ldapportal.sync.changelog.fixed-delay-ms` | `15000` | Changelog poll interval. |
| (constant) changelog page | `500` | Records per changelog search. |
| (constant) pages per pass | `20` (or `5s`, whichever first) | Per link per poll; more pending → `LAGGING`. The poller shares the app's single scheduler thread, so this also bounds how long a busy link delays other scheduled jobs. |
| (constant) reconcile-request retry spacing | `5m` | Retry interval for a requested reconcile whose source enumeration didn't complete. |
| (constant) lag threshold | `1000` | Above this lag → `LAGGING`. |
| (constant) stale poll lease | `5m` | Reclaim a poll lease from a dead instance. |
| `ldapportal.sync.preview.parallelism` | `2` | Per-entry preview runs executing at once per instance; more are refused with *try again*. |
| `ldapportal.sync.preview.stale-after-ms` | `120000` | A running preview with no heartbeat this long reads as interrupted and can be replaced. |
| `ldapportal.sync.preview.heartbeat-ms` | `15000` | Heartbeat interval of running previews (on the preview's own timer, so a long reconcile sweep on the shared scheduler can't delay it). |
| `ldapportal.sync.preview.retention-hours` | `24` | Stored preview results are deleted after this long. |
| `ldapportal.sync.preview.purge-interval-ms` | `3600000` | How often expired previews are deleted. |

**Tuning guidance:** lower the worker/poll delays for lower latency at higher
DB/source load; raise per-set reconcile cadence for large, stable sets to cut
enumeration cost; the reconcile floor protects correctness regardless.

### 8.2 High availability
Multi-instance safe by design: the recompute queue uses an atomic
claim/settle lease (stale claims reclaimed after 10m), changelog polling
uses a per-link poll-lease (stale lease reclaimed after 5m), and a set's
reconcile holds a per-set lease (`sync_set.reconcile_lease_*`, heartbeat every
30s, stale lease reclaimed after 15m). Every instance runs the reconcile
scheduler, but only the instance that claims a due set reconciles it; the
others skip it, and **Reconcile now** while it runs returns 409 *A reconcile of
this sync set is already running*. Run as many app instances as you like; no
leader election needed.

> Before the reconcile lease, each instance reconciled the same due set at
> once: N instances did the work N times, and all but the first to finish
> failed with *Row was updated or deleted by another transaction* when saving
> the set (they show as **Failed** runs in the reconcile history).

### 8.3 Schema (Flyway-owned; `db/migration/core`)
| Table | Holds |
|---|---|
| `sync_links` | source/target dirs, capture mode, changelog config + health/cursor. |
| `sync_set` | selection + projection config per set; reconcile cadence, last run, the reconcile request counters (`reconcile_request_seq` / `reconcile_attempted_seq` / `reconcile_handled_seq`), and `source_change_seq` (source changes enqueued; `V43`). |
| `sync_membership` | one row per identity per set: source/target DN, content hash, state, fail reason, scan epoch, version. (Renamed from `membership` in `V7`.) |
| `recompute_request` | coalescing trigger queue, PK `(sync_set_id, request_key)`. |
| `sync_reconcile_run` | run history: reconciles and incremental passes, with counts, samples and failures. |
| `sync_reconcile_run_entry` | every DN a run changed or flagged, PK `(run_id, seq)`; written only while the set records every DN. |

Deleting a set cascades its `sync_membership` + `recompute_request` rows (FK
`ON DELETE CASCADE`). A link can't be deleted until its sets are deleted.

> No entity-only schema changes — every change is a new Flyway migration;
> Hibernate is `validate` only.

### 8.4 Capacity / table growth
`sync_membership` is bounded by the number of in-scope identities (one row
each) — it does **not** grow per change like an event log. `recompute_request`
is self-draining and PK-deduped; a persistent backlog there means the worker is
behind (raise throughput or lower batch latency).

`sync_reconcile_run_entry` grows with the **Detailed** logging level: one row per changed
or flagged entry per kept run, so roughly *entries changed per run × runs
kept*. A first reconcile of a large set is the big one; later runs record only
what changed. Set the logging level to Summary, or lower the history knobs, to
cap it. The audit log grows by one record per target write, whatever the level.

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
| Changelog `GAP_DETECTED` | Source changelog purged faster than polled | A reconcile is requested automatically (next scheduler tick); raise source changelog retention or poll more often. |
| Full reconcile slow (well under ~20 entries/s) | Unindexed searches: the reference attributes on the source, or the anchor attribute on the target | Read the run's `reconcile of … took` log line (§5); add equality indexes for the attributes in its largest share. |
| `recompute_request` backlog growing | Worker behind | Lower `worker.fixed-delay-ms`, scale instances, check DB. |
| Wrong DN on target after a rename | (shouldn't happen) engine converges renames as MODDN | If stuck, **Recompute** by identity; reconcile re-derives. |
| Member/manager points at stale DN | reference attr not declared | Add the attr to **Reference attributes**; recompute the group/referrer. |
| Target restored from a backup; verification shows missing / stale entries but reconciles change nothing | The index still records what was written before the restore, so the hash gate skips those entries | **Re-check target**, then review orphans — see *Target restored from a backup* below. |
| Reference into another link's subtree missing on target (e.g. `secDN`) | referent not yet synced by any link, or mirrored to different target DNs by two links (see the `ambiguous` warning in logs) | Sync the referent (or disable the conflicting link); the closure fan-out or next reconcile re-projects the referrer. |

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
- **Re-check target (set)** — forget what the set last wrote for every
  `APPLIED` row (clears its content hash) and request a reconcile. That
  reconcile reads each target entry instead of trusting the index, and re-adds
  or re-modifies the ones that differ. Writes nothing itself; the reconcile
  runs on the scheduler's next tick (~60s), under the set's lease, once the set
  and link are enabled.

### Target restored from a backup

A reconcile trusts the membership index: an `APPLIED` row whose source
projection is unchanged is skipped without reading the target (the hash gate,
§5). After the target is restored from a backup, that index describes the
target *before* the restore, so a plain reconcile — and a per-row
**Recompute** — leaves the restored state in place:

| Since the backup, the sync had… | After the restore | Plain reconcile | After **Re-check target** |
|---|---|---|---|
| left the entry alone | correct | skipped | read, no write (already matched) |
| added the entry | missing | skipped | **re-added** |
| modified the entry | stale values | skipped | **re-modified** |
| moved / renamed the entry | at the old DN | skipped | **re-added at the new DN**; old-DN copy is an orphan |
| deleted the entry | back (no index row) | never visited | still there — an orphan |

Procedure:
1. **Re-check target** on each affected set (inventory header). Wait for the
   next reconcile (or **Reconcile now**); its run should show the re-adds and
   re-modifies.
2. **Verify contents** and review **Orphaned on target**: entries the sync had
   deleted, and old-DN copies of moved entries. The sync never deletes an entry
   it has no index row for — remove them on the target if they shouldn't exist.
3. Verify again: missing on target and content drift should be 0.

### Deleting a sync set

Deleting a set removes everything the portal keeps for it — its membership
index (including rows held for review, discarded undecided), reconcile run
history, saved preview and queued recomputes — but **not** the entries it
wrote: they stay on the target, unmanaged, and the portal can no longer tell
they came from the set. The UI asks first, with the counts (managed entries,
pending reviews, recorded runs) from `GET …/sets/{id}/delete-impact`.

Two knock-on effects:
- DN references (`member`, `manager`, `secDN`, …) are remapped through every
  set's index, so a reference from another set to an entry only the deleted
  set mirrored is dropped from that set's targets on its next recompute.
- A new set over the same scope later finds those entries already present.
  With a **source anchor attribute**, an entry carrying the identity is
  adopted, and an entry at the placement DN without it is held for REVIEW.
  Without one, the ADD falls through to a MODIFY: the new set takes the
  entry over and rewrites it to match the source, with no review.

The delete is audited as `sync.set_delete` on the link's **target** directory
(target DN = the set's target base), with `detail` = `syncSetName`,
`sourceDirectory`, `targetDirectory`, `syncSetId`, `linkId`, `managedEntries`, `pendingReview`, `reconcileRuns` —
the record outlives the set's own history.

### Deleting a sync link

A link can only be deleted once it has no sync sets — the server refuses
(400) until they're gone, and the UI says so instead of asking. Deleting an
empty link changes neither directory; what goes is the link's configuration
and, for `CHANGELOG` capture, its position in the source changelog (cursor,
last change number, health), so a link created to replace it starts afresh
rather than resuming. The UI asks first.

The delete is audited as `sync.link_delete` on the link's **target**
directory (no target DN), with `detail` = `linkId`, `linkName`,
`sourceDirectory`, `targetDirectory`, `sourceDirId`, `targetDirId`, `captureMode`, and for `CHANGELOG` links
`changelogFormat`, `changelogBaseDn` and `changelogLastChangeNumber` (when
set). A refused delete isn't audited.

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

-- Changelog poll health and lag (CHANGELOG links; lag is NULL in cookie mode)
SELECT l.display_name,
       l.changelog_health,
       l.changelog_last_polled_at,
       l.changelog_last_error,
       (l.changelog_source_last_change_number - l.changelog_last_change_number) AS lag
FROM sync_links l
WHERE l.capture_mode = 'CHANGELOG'
ORDER BY l.display_name;

-- Sets due for reconcile (unattempted request; unhandled request with the last
-- run 5+ minutes ago; or last run older than their cadence, blank = 3600s)
SELECT ss.name, ss.reconcile_last_run_at, ss.reconcile_cadence_seconds,
       ss.reconcile_request_seq > ss.reconcile_attempted_seq AS reconcile_requested,
       ss.reconcile_request_seq > ss.reconcile_handled_seq  AS reconcile_unhandled
FROM sync_set ss
WHERE ss.enabled
  AND (ss.reconcile_request_seq > ss.reconcile_attempted_seq
       OR ss.reconcile_last_run_at IS NULL
       OR (ss.reconcile_request_seq > ss.reconcile_handled_seq
           AND ss.reconcile_last_run_at + interval '5 minutes' <= now())
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
- **References resolve across links.** A DN reference is remapped through any
  link's index, which assumes links that mirror the same source DN agree on its
  target DN. When they don't, the value is dropped as ambiguous rather than
  guessed.
- **App-intercept sees only portal writes.** Out-of-band source changes need
  `CHANGELOG` mode or the reconcile floor.
- **Identity must be stable and unique per scope.** A mutable/duplicate identity
  key breaks correlation; the engine quarantines what it can't disambiguate.
- **Convergence, not history.** There is no per-change audit/event trail in the
  sync subsystem today (see §7.4) — the record is current index state + logs.
- **Changelog format:** `DSEE_CHANGELOG` and `OUD_COOKIE_CHANGELOG`; other feeds (DirSync,
  syncrepl, Entra delta) are planned, not shipped.
- **Per-server changelogs need a fixed node.** Only OUD cookie mode survives a
  replica switch. `DSEE_CHANGELOG` links (OUD change-number mode, IBM, 389 DS)
  and DSEE-format audit sources must point at one node — see §4.3.
