<!-- SPDX-License-Identifier: Apache-2.0 -->
# Directory Sync — operator manual

**Status:** Shipped (engine core, app-intercept, changelog capture incl. OUD cookie mode, reconcile, reconcile run history, brownfield adoption, cross-link reference resolution; 2026-10-20).

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
| **Identity key** | Source attribute used as each entry's stable identity (`entryUUID`, `objectGUID`, …). Blank → directory-type default. **Avoid mutable attributes like `mail`** — identity must never change for the same entry. |
| **Source scope base DN** | Base DN under the source to enumerate / match against. Blank → the directory's own base DN. |
| **Source scope** | LDAP scope under the base: `SUB` (default), `ONE`, `BASE`. |
| **Applicability filter** | RFC 4515 filter selecting which source entries belong, e.g. `(&(objectClass=inetOrgPerson)(employeeType=staff))`. Validated for syntax at save. |
| **Target base DN** | Base DN under the target where matched entries are placed. |
| **Reference attributes** (csv) | DN-valued attributes whose values are rewritten to target DNs and whose referrers are re-driven on change, e.g. `member,uniqueMember,manager`. Blank → `member,uniqueMember,manager,owner,secDN`. Values are resolved through the membership index of **every** link (the referrer's own link first), so a reference into a subtree mirrored by another link — an IVIA `secDN` under `c=admin` pointing at a `c=us` entry when OUD exposes each suffix as its own directory connection — still remaps. A referent no link has synced is dropped until it lands; one that different links mirror to *different* target DNs is ambiguous and dropped (logged). |
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

**Run history:** every reconcile of an enabled set is recorded in
`sync_reconcile_run` — trigger (`SCHEDULED`, or `MANUAL` with the operator),
start/finish, whether the source scan completed, the delete guard, managed rows
before → after, and what the engine did to each entry it processed, in the
preview's vocabulary: added / modified / moved / deleted / held for review /
already matched (applied with no target write) / forgotten / unchanged /
failed. *Faulted* entries (the recompute threw) retry next run; *skipped*
entries were not processed because the set or link was disabled while the run
was in progress — the engine checks on every entry, so disabling mid-run stops
the writes but the run still walks the rest of the plan. A running row
heartbeats about every 15 s; one silent for
`ldapportal.sync.reconcile.run-stale-after-ms` (default 15 min) belonged to a
server that stopped and reads as `FAILED`. The newest 20 runs per set are kept;
deleting the set removes them. A disabled or unavailable set's reconcile is a
no-op and is not recorded.

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
- **Last reconcile** (top of the inventory modal) — the latest recorded run:
  status, trigger, when it started and how long it took, its outcome counts,
  managed rows before → after, and warnings for an incomplete scan, a tripped
  delete guard, faulted entries, or entries skipped because the set was
  disabled mid-run. **Recent runs** lists up to the last 10. Refreshes after
  **Reconcile now**.
- **Untracked search** — searching a DN/identity that isn't tracked offers
  `Recompute "<term>"` to pull it in on demand.
- **Verify contents** — a belts-and-suspenders check that re-reads *both*
  directories and compares them directly (it ignores the membership index, so it
  catches drift the index believes is already converged). Reports counts of
  in-sync / **missing on target** / **orphaned on target** / **content drift**
  entries, with sample DNs for each. Read-only; never writes.
- **Impact preview** (set editor) — counts of what a reconcile would add and
  delete, plus sample DNs to be deleted. Works on a **disabled** set, and runs
  automatically as the confirmation step when you enable a set: if any deletes
  are planned (or the delete guard would trip) you're asked before it turns on.
- **Show changes** (set editor; needs *manage directory sync*) — the full,
  paged list of what a reconcile would do to **each entry**: **Add**,
  **Modify**, **Move**, **Delete**, **Review**, **No change**, **Forget**,
  **Unchanged**, or **Error**. Click a row for the attribute-level changes
  (values removed `−` and added `+`), the source DN, the previous DN of a move,
  or the reason an entry would be held for review. *Changes only* (the default)
  hides entries that won't change: **Unchanged** (already applied, or already
  held for review) and **No change** (the target already matches). Details:

  - **A background run.** Opening the dialog shows the set's latest preview, or
    starts one if there is none; **Run again** starts a fresh one. A run scans
    the source once, dry-runs every entry and stores the result, so paging
    reads a fixed snapshot — every page comes from the same plan, and nothing
    is re-scanned. The dialog shows progress while it runs. One run per set at
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
DELETE /api/v1/superadmin/sync/links/{id}

# Sets
GET    /api/v1/superadmin/sync/sets[?linkId=…]
POST   /api/v1/superadmin/sync/sets
PUT    /api/v1/superadmin/sync/sets/{id}
DELETE /api/v1/superadmin/sync/sets/{id}

# Inventory + operator triggers
GET    /api/v1/superadmin/sync/sets/{id}/memberships?state=&q=&page=&size=   # size capped 1..200
GET    /api/v1/superadmin/sync/sets/{id}/preview          # dry-run reconcile plan (index-based, no writes; works on a disabled set/link)
POST   /api/v1/superadmin/sync/sets/{id}/preview/run      # start a per-entry dry run in the background (joins a running one) → 202 + status; manage permission
GET    /api/v1/superadmin/sync/sets/{id}/preview/run      # latest run: status (RUNNING / COMPLETE / FAILED), progress, summary; manage permission
GET    /api/v1/superadmin/sync/sets/{id}/preview/entries?page=&size=&changesOnly=   # page of the completed run (404 if none); size 1..200 (default 50), changesOnly default true; a page past the end returns the last page; manage permission
GET    /api/v1/superadmin/sync/sets/{id}/verify           # independent source↔target compare (no writes)
POST   /api/v1/superadmin/sync/sets/{id}/reconcile        # → {"enumerated": N}; recorded as a MANUAL run
GET    /api/v1/superadmin/sync/sets/{id}/reconcile-runs?limit=   # recent reconcile runs, newest first; limit 1..20 (default 10)
POST   /api/v1/superadmin/sync/sets/{id}/recompute        # body {"key": "<dn-or-identity>"} → 202
DELETE /api/v1/superadmin/sync/sets/{id}/memberships/{identity}   # dismiss
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
claim/settle lease (stale claims reclaimed after 10m), and changelog polling
uses a per-link poll-lease (stale lease reclaimed after 5m). Run as many app
instances as you like; no leader election needed.

### 8.3 Schema (Flyway-owned; `db/migration/core`)
| Table | Holds |
|---|---|
| `sync_links` | source/target dirs, capture mode, changelog config + health/cursor. |
| `sync_set` | selection + projection config per set; reconcile cadence, last run, and the reconcile request counters (`reconcile_request_seq` / `reconcile_attempted_seq` / `reconcile_handled_seq`). |
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
| Changelog `GAP_DETECTED` | Source changelog purged faster than polled | A reconcile is requested automatically (next scheduler tick); raise source changelog retention or poll more often. |
| `recompute_request` backlog growing | Worker behind | Lower `worker.fixed-delay-ms`, scale instances, check DB. |
| Wrong DN on target after a rename | (shouldn't happen) engine converges renames as MODDN | If stuck, **Recompute** by identity; reconcile re-derives. |
| Member/manager points at stale DN | reference attr not declared | Add the attr to **Reference attributes**; recompute the group/referrer. |
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
