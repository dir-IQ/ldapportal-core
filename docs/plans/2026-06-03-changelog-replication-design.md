# Changelog-driven replication — design plan

- **Date:** 2026-06-03
- **Status:** Not started (design only; reliability/observability hardening
  pass applied — see §7A, 2026-06-03).
- **Suggested branch:** `feat/changelog-replication` (already cut; this doc
  lives on it).
- **Scope:** Add a second **capture mode** to an existing replication link.
  Instead of capturing source-side writes through the in-app
  `ReplicatingLdapInterface` wrapper (`APP_INTERCEPT`), poll the source
  directory's **external changelog**, reconstruct each change into the
  existing `replication_events` payload shape, and hand it to the existing
  dispatch queue. This closes the **out-of-band write gap** — changes made
  to the source directory by anything *other* than the portal (native admin
  consoles, scripts, other IAM tools) are invisible to `APP_INTERCEPT` and
  today surface only on the next (expensive, full-subtree) reconciliation run.
- **v1 target server:** **Oracle Unified Directory (OUD)** only. OUD exposes
  the `draft-good-ldap-changelog` format at `cn=changelog`
  (`objectClass=changeLogEntry`, integer `changeNumber`) — identical to the
  `ChangelogFormat.DSEE_CHANGELOG` the audit-sources reader already speaks.
  Built so OpenLDAP `accesslog` and AD `DirSync` slot in behind the same SPI
  later (§8).
- **Builds on:**
  - `docs/plans/2026-05-30-directory-sync-design.md` — the async fan-out
    feature (`ReplicationLink`, `replication_events`, `ReplicationWorker`,
    `DnMapper`, `AttributeMapper`, JSONB payload shapes, snapshot boundary).
  - `docs/plans/2026-05-31-replication-reconciliation-design.md` — the
    periodic reconciliation engine that stays as the **safety net** (§7).
  - The **audit-sources changelog reader** (`LdapChangelogReader`,
    `ChangelogStrategy`, `DseeChangelogStrategy`) — the detection/transport
    half we generalize and reuse (§3, §4).
  Read those first; this document assumes their vocabulary.
- **Audience:** Self-contained; written to hand to a fresh Claude Code
  session. Paths are relative to the repo root.

---

## 0. Decisions (locked)

1. **Read from the link's source `DirectoryConnection`.** No separate
   `AuditDataSource`-style connection config. The changelog (`cn=changelog`)
   sits outside the directory's configured `baseDn`, but a plain LDAP search
   against the same bound connection reaches it.
2. **OUD only in v1.** Extensible by design — the format-specific logic lives
   behind the `ChangelogStrategy` SPI (generalized in §4) plus a new
   `extractChange` method; adding OpenLDAP/AD later is "implement the SPI +
   add an enum-allowed value," no schema or poller changes.
3. **Capture mode is exclusive per link:** a link captures via **either**
   `APP_INTERCEPT` **or** `CHANGELOG`, never both. This is the one knob;
   flipping it changes *how* changes are detected, not *what* is replicated
   (DN/attribute mapping, dispatch, reconciliation all unchanged).

---

## 1. What we reuse vs. what is new

**Reused unchanged** (the entire transport + delivery half):

| Asset | Role in this feature |
|---|---|
| `ReplicationEnqueueSource.SOURCE_CHANGELOG` | Already defined; already permitted by the `replication_events_enqueue_source_check` CHECK (V13). No enum/migration churn. |
| `PendingReplicationEvent` → `ReplicationEventPersister.saveAll(List)` | The enqueue seam. We build the same record the `APP_INTERCEPT` path builds; the persister, worker, delivery, retry/dead-letter, and dashboard are all downstream and untouched. |
| `DnMapper.map(sourceDn, link)` | Source→target DN rewrite + scope filtering (out-of-scope → `null` → skip). |
| `AttributeMapper.mapAttributes / mappingFor` | Attribute rename + value-template mapping. |
| `ReplicationReadOps.snapshotsForSource / snapshotById` | Link snapshots (immutable boundary records). |
| `ReplicationWorker` / `ReplicationDelivery` | Drain + apply to target. Provenance `SOURCE_CHANGELOG` rides along for diagnostics; the worker treats all events identically (per the enum's own javadoc). |
| Reconciliation engine | Stays the drift safety net (§7). |
| `LdapConnectionFactory` (source dir pooled connection) | Opening/binding the source for the changelog search. |

**New** (the detection→payload half):

| New artifact | Why |
|---|---|
| Link config: `capture_mode`, `changelog_format`, `changelog_base_dn`, `changelog_last_change_number` (cursor) | §2 |
| Generalized `ChangelogStrategy` (decoupled from `AuditDataSource`) + new `extractChange()` returning a structured op | §4 |
| `OudChangelogChangeParser` — parse the `changes` LDIF blob into UnboundID `Attribute[]`/`Modification[]` | §5 — **the genuinely new, highest-risk work** |
| `ReplicationChangelogPoller` — scheduled, per-link, cursor-advancing | §6 |
| Enqueuer must **skip** `CHANGELOG`-mode links (avoid double-capture) | §6.4 |
| DTO/service/controller fields + "test changelog" endpoint | §9 |
| Frontend: capture-mode selector + changelog fields in the link modal | §10 |

---

## 2. Schema & config (Phase C1)

New Flyway migration **`V15__replication_changelog_capture.sql`** (next free
number; V14 is the current head) under `db/migration/core/`. Adds to
`replication_links`:

```sql
ALTER TABLE replication_links
  ADD COLUMN capture_mode              VARCHAR(20)  NOT NULL DEFAULT 'APP_INTERCEPT',
  ADD COLUMN changelog_format          VARCHAR(25),
  ADD COLUMN changelog_base_dn         VARCHAR(500),
  ADD COLUMN changelog_last_change_number BIGINT,          -- cursor (high-water mark)
  -- ── liveness / health surfacing (see §7A) ──
  ADD COLUMN changelog_source_last_change_number BIGINT,   -- last observed source head; lag = this − cursor
  ADD COLUMN changelog_last_polled_at      TIMESTAMPTZ,
  ADD COLUMN changelog_last_error          TEXT,
  ADD COLUMN changelog_last_error_at       TIMESTAMPTZ,
  ADD COLUMN changelog_health              VARCHAR(24) NOT NULL DEFAULT 'HEALTHY',
  -- ── DB-backed single-flight lease for HA (mirror ReconciliationTxOps, §7A.5) ──
  ADD COLUMN changelog_poll_claimed_at     TIMESTAMPTZ,
  ADD CONSTRAINT replication_links_capture_mode_check
      CHECK (capture_mode IN ('APP_INTERCEPT','CHANGELOG')),
  -- changelog_format constrained to the v1-supported value; widen when
  -- OpenLDAP/AD strategies land (mirrors the audit chk_changelog_format style).
  ADD CONSTRAINT replication_links_changelog_format_check
      CHECK (changelog_format IS NULL OR changelog_format IN ('DSEE_CHANGELOG')),
  -- CHANGELOG mode requires its config; APP_INTERCEPT must leave it null.
  ADD CONSTRAINT replication_links_changelog_cfg_check
      CHECK (
        (capture_mode = 'APP_INTERCEPT' AND changelog_format IS NULL
                                         AND changelog_base_dn IS NULL)
        OR
        (capture_mode = 'CHANGELOG'     AND changelog_format IS NOT NULL
                                         AND changelog_base_dn IS NOT NULL)
      );
```

Entity changes — `entity/ReplicationLink.java`:

```java
@Enumerated(EnumType.STRING)
@Column(name = "capture_mode", nullable = false, length = 20)
private ReplicationCaptureMode captureMode = ReplicationCaptureMode.APP_INTERCEPT;

@Enumerated(EnumType.STRING)
@Column(name = "changelog_format", length = 25)
private ChangelogFormat changelogFormat;          // reuse existing enum

@Column(name = "changelog_base_dn", length = 500)
private String changelogBaseDn;                   // default 'cn=changelog' (set in service)

/** Cursor / high-water mark: highest changeNumber already enqueued. */
@Column(name = "changelog_last_change_number")
private Long changelogLastChangeNumber;
```

New enum `entity/enums/ReplicationCaptureMode.java` → `{ APP_INTERCEPT, CHANGELOG }`.
Reuse the existing `ChangelogFormat` enum (already has `DSEE_CHANGELOG` =
the OUD format); v1 validation rejects every value but `DSEE_CHANGELOG`.

Add `captureMode` to `ReplicationLinkSnapshot` (needed by the enqueuer skip in
§6.4 and by the poller).

### 2.1 Cursor semantics & first-run seeding

`changelog_last_change_number` is the highest `changeNumber` already turned
into events for this link. The poller searches `changeNumber > cursor`.

**First-run seed:** when a link is switched into `CHANGELOG` mode (or created
in it), do **not** replay the entire changelog history. On the link's first
poll where `changelogLastChangeNumber IS NULL`, seed the cursor to the
**current max `changeNumber`** in the source and persist it without emitting
events. Existing entries are brought into parity by an initial reconciliation
run (auto-triggered on the mode switch, §7A.8) — the same bootstrap story the
`APP_INTERCEPT` path already relies on. Document this in the UI help text.

**Cursor writes are CAS, never a full-entity save.** `ReplicationLink` has no
`@Version`; advancing the cursor with `repo.save(link)` would clobber a
concurrent operator edit (and vice-versa) and is unsafe across instances.
Advance through a dedicated
`UPDATE replication_links SET changelog_last_change_number = :new,
changelog_last_polled_at = now() WHERE id = :id AND
changelog_last_change_number IS NOT DISTINCT FROM :expected` (compare-and-set).
This is the `ChangelogCursorStore` seam (§8) and keeps the int→cookie swap for
AD local. **The cursor is `changeNumber`-based, not time-based — immune to
clock skew between portal and directory; treat that as a deliberate property.**

---

## 3. How OUD's changelog maps to our payload

An OUD `cn=changelog` entry (`objectClass=changeLogEntry`) carries:

| Attribute | Use |
|---|---|
| `changeNumber` (int) | cursor key + idempotency |
| `changeType` (`add`/`modify`/`delete`/`modrdn`) | → `ReplicationOperationType` |
| `targetDN` | source DN (→ `DnMapper`) |
| `changes` (LDIF blob) | the per-attribute change content (§5) |
| `newRDN`,`deleteOldRDN`,`newSuperior` | MODIFY_DN parts |
| `changeTime` | diagnostics (already parsed by `DseeChangelogStrategy.GENERALIZED_TIME`) |
| `creatorsName` | optional loop-guard signal (§7.2) |

Operation → payload (the shapes `ReplicationEvent` documents):

- `add`   → `ADD`,       payload `{ attributes: { name: [values] } }`
- `modify`→ `MODIFY`,    payload `{ modifications: [ { type, name, values } ] }`
- `delete`→ `DELETE`,    payload `{}`
- `modrdn`→ `MODIFY_DN`, payload `{ newRdn, deleteOldRdn, newSuperiorDn }`

Then `DnMapper` + `AttributeMapper` rewrite DN and attributes exactly as the
`APP_INTERCEPT` path does, so dispatch downstream is byte-identical.

---

## 4. Generalizing the changelog SPI (Phase C2, part 1)

Today `ChangelogStrategy.buildSearchRequest(AuditDataSource, int)` and
`LdapChangelogReader` are hard-wired to `AuditDataSource` and to
`AuditService`. Decouple so both the audit reader and the replication poller
drive the same strategies.

**4.1 Introduce a neutral read context.** New record
`ldap/changelog/ChangelogReadContext`:

```java
public record ChangelogReadContext(
        String changelogBaseDn,
        String branchFilterDn,   // null when none; replication uses link.sourceBaseDn
        Long   afterChangeNumber // null = no lower bound; poller passes the cursor
) {}
```

Change the SPI signature to
`SearchRequest buildSearchRequest(ChangelogReadContext ctx, int sizeLimit)`.
Adapt `LdapChangelogReader` to build a `ChangelogReadContext` from its
`AuditDataSource` (the audit path keeps passing `afterChangeNumber = null` —
it dedups via the `audit_events` table rather than a cursor). This is a
**mechanical refactor**; gate it behind the existing `AccesslogStrategyTest`
and add a `DseeChangelogStrategyTest` if absent.

> Note: `DseeChangelogStrategy` currently filters `SearchScope.ONE` on
> `(objectClass=changeLogEntry)` with an optional `targetDN=<branch>*` clause.
> Add an `afterChangeNumber` clause → `(&(objectClass=changeLogEntry)
> (changeNumber>=<cursor+1>) …)`. OUD indexes `changeNumber`, so this is the
> efficient incremental query (vs. the audit reader's scan-and-dedup).

**4.2 Add the structured-change method** to `ChangelogStrategy`:

```java
/**
 * Reconstruct the entry into a replication-ready operation, or empty if
 * this format/entry cannot be turned into a structured write (default for
 * strategies that only support audit detection, not replication).
 */
default Optional<ChangelogChange> extractChange(SearchResultEntry entry) {
    return Optional.empty();
}
```

`ChangelogChange` (new record): `ReplicationOperationType operation`,
`String sourceDn`, `Map<String,Object> rawPayload` (pre-mapping, in the same
shape the enqueuer's `buildPayload` produces, before `DnMapper`/`AttributeMapper`).

Only `DseeChangelogStrategy` overrides it in v1 (delegating to §5). OpenLDAP/AD
strategies keep the default empty until their parsers land — so they remain
usable for audit but not yet for replication, and the poller's validation
(§9) refuses to enable `CHANGELOG` mode for a format whose strategy returns
empty.

---

## 5. OUD change parsing (Phase C2, part 2) — the new, risky bit

The `changes` attribute on a `changeLogEntry` is an **LDIF fragment** (RFC
2849). Rather than hand-roll a parser, reuse UnboundID's LDIF machinery:

- **modify:** wrap as
  `dn: <targetDN>\nchangetype: modify\n<changes>` and parse via
  `com.unboundid.ldif.LDIFReader.decodeChangeRecord(...)` →
  `LDIFModifyChangeRecord.getModifications()` → `Modification[]`.
- **add:** the `changes` blob is LDIF attribute lines; parse via
  `LDIFReader.decodeEntry(...)` (prefix with `dn: <targetDN>`) →
  `Entry.getAttributes()`.
- **delete:** no `changes` content needed.
- **modrdn:** read `newRDN`/`deleteOldRDN`/`newSuperior` attributes directly.

New `ldap/changelog/OudChangelogChangeParser` (a `@Component`-free static
helper, mirroring `DnMapper`/`AttributeMapper` style) does this and returns the
`ChangelogChange.rawPayload` in our shape. `DseeChangelogStrategy.extractChange`
delegates here.

**Edge cases that need explicit unit tests** (`OudChangelogChangeParserTest`):

- base64-encoded values (`attr:: …`) and values needing base64 on the way out.
- folded/continued LDIF lines.
- multi-valued and `delete`-the-whole-attribute modifications
  (`Modification` with `null` values — the enqueuer already guards this; keep
  the same `null` → `List.of()` contract when translating to JSONB).
- `modrdn` with and without `newSuperior` (rename vs. move).
- attribute names with options/`;binary`.
- empty/whitespace `changes`.

This component is the bulk of the v1 test surface and the main source of
format edge-case risk. Treat parser correctness as the acceptance bar for the
phase.

---

## 6. The poller (Phase C3)

New `ldap/replication/ReplicationChangelogPoller`, modeled on
`LdapChangelogReader` but driving the replication enqueue path.

### 6.1 Schedule & gating

```java
@Scheduled(
  fixedDelayString   = "${ldapportal.replication.changelog.poll-ms:30000}",
  initialDelayString = "${ldapportal.replication.changelog.initial-delay-ms:20000}")
public void pollAll() { … }
```

- Entitlement gate: early-return when `DIRECTORY_SYNC` is not entitled
  (mirror `ReplicationEnqueuer` / `ReconciliationScheduler` — corrective writes
  must not flow after a commercial→community downgrade).
- Select links where `enabled = true AND capture_mode = 'CHANGELOG'`
  (new `ReplicationLinkRepository.findChangelogCaptureLinkIds()`).
- Per-link **single-flight** guard. **DB-backed, not in-JVM** — a
  `ConcurrentMap` is unsafe once the portal runs >1 instance (two pollers
  double-process the same link). Mirror `ReconciliationTxOps.tryStart`: claim
  via `UPDATE … SET changelog_poll_claimed_at = now() WHERE id = :id AND
  (changelog_poll_claimed_at IS NULL OR changelog_poll_claimed_at < :staleCutoff)`
  returning rows-affected; release in a `finally`. A companion stale-claim
  sweep (like the worker's `resetStaleInFlight`) recovers leases orphaned by a
  crash. Per-link consecutive-failure backoff + config-error disable follow the
  proven `LdapChangelogReader` shape.
- Wrap each link's poll in `CorrelationContext.withCorrelation(...)` so
  emitted events share a trace id (consistent with the rest of the system).

### 6.2 Per-link poll

1. Load the `ReplicationLinkSnapshot` (`snapshotById`) for DN/attr mapping.
2. Open the **source** directory connection via `LdapConnectionFactory`
   (the link's `sourceDirectory`). Reads don't trigger capture, so no
   `unreplicated` wrapper is required — but use the read-only path.
3. **Read the source head** from the root DSE (`firstChangeNumber`,
   `lastChangeNumber` — OUD/DSEE publish both; access via `conn.getRootDSE()`
   as `LdapCapabilityProbeService` already does). Persist `lastChangeNumber`
   into `changelog_source_last_change_number` (drives the lag gauge, §7A).
   Run the **gap / reset guards (§7A.1–.2) before reading any entries**:
   - `lastChangeNumber < cursor` ⇒ **cursor reset** (source restored/reinit) →
     alert, set health `CURSOR_RESET`, do **not** advance, await operator
     reseed.
   - `cursor + 1 < firstChangeNumber` ⇒ **gap** (entries trimmed before we read
     them) → alert, set health `GAP_DETECTED`, auto-trigger reconciliation,
     then fast-forward the cursor to `firstChangeNumber − 1` so the stream
     resumes (reconciliation repairs the skipped span).
4. First-run seed if `changelogLastChangeNumber == null` (§2.1): set cursor =
   `lastChangeNumber`, persist, return.
5. Build `ChangelogReadContext(changelogBaseDn, link.sourceBaseDn,
   cursor)` and the strategy's incremental search, ordered by `changeNumber`
   ascending. **Catch-up budget, not a hard cap:** drain successive pages
   (e.g. 500/page) within a per-poll wall-clock budget
   (`ldapportal.replication.changelog.poll-budget-ms`, default ~10s) until
   caught up or the budget elapses — so a large backlog after downtime
   recovers in minutes, not hours. A hard 500/poll cap at a 30s interval drains
   only 1k/min.
6. For each entry, in `changeNumber` order:
   - `strategy.extractChange(entry)` → `ChangelogChange`.
     **Poison-entry policy (§7A.3):** if parsing throws, **do not** silently
     skip and **do not** wedge the link. Persist a `DEAD_LETTERED`
     `replication_event` carrying the raw changelog entry + the parse error
     (recoverable + audited), emit `REPLICATION_CHANGELOG_ENTRY_DEAD_LETTERED`,
     then continue past it (reconciliation also re-derives that DN). Empty
     `extractChange` for a non-recordable entry is a normal skip.
   - `DnMapper.map(sourceDn, link)` → target DN; `null` ⇒ out of scope, skip
     (still advance the cursor past it).
   - Map via the shared `ReplicationPayloadMapper` (§6.3).
   - Stamp `sourceChangeNumber` into the payload (ordering + dedup key, §6.5).
   - Build a `PendingReplicationEvent` with `enqueueSource = SOURCE_CHANGELOG`.
7. `persister.saveAll(pending)` then **CAS-advance the cursor** (§2.1) to the
   **highest contiguously-processed `changeNumber`** — never the highest *seen*
   (a mid-page connection drop must resume from the last good one, not leave a
   hole). Persist-before-advance means a crash in between replays a bounded
   prefix; the §6.5 dedup index makes that replay exactly-once.

### 6.3 Shared payload mapping (small refactor)

`ReplicationEnqueuer.buildPayload / mappedAddAttributes / mappedModifications`
is exactly what the poller needs post-parse. Extract these into
`ldap/replication/ReplicationPayloadMapper` (pure, static) and have **both**
the enqueuer and the poller call it. Gate behind existing enqueuer tests.

### 6.4 Enqueuer must skip CHANGELOG links

`ReplicationEnqueuer.enqueue` fans out to every enabled link whose source is
the directory being written. A `CHANGELOG`-mode link must **not** also capture
the app's own writes (that's the whole point of "exclusive"). Add
`captureMode` to `ReplicationLinkSnapshot` and skip
`captureMode == CHANGELOG` links in `buildEvent`. Without this, a portal write
to a `CHANGELOG` link's source would be enqueued twice (once by the
interceptor, once by the poller).

### 6.5 Exactly-once dedup (MANDATORY)

Replay is **not** harmless in general: a `MODIFY` of the form `add: member=X`
applied twice duplicates/errors, and a replayed `ADD` hits
`entryAlreadyExists`. "Idempotent delivery" only covers REPLACE/auto-create
shapes — not the additive/delete modification forms OUD emits. So the dedup
guard is a **correctness requirement, not hygiene**.

Stamp `sourceChangeNumber` into every `SOURCE_CHANGELOG` payload and add a
partial unique index so the queue itself enforces exactly-once even under
crash-replay, concurrent pollers, or a stale-lease double-claim:

```sql
CREATE UNIQUE INDEX replication_events_changelog_dedup
  ON replication_events (link_id, ((payload->>'sourceChangeNumber')))
  WHERE enqueue_source = 'SOURCE_CHANGELOG';
```

The persister must treat a unique-violation on this index as a **benign skip**
(already enqueued), not an error — i.e. insert-if-absent semantics for these
rows. This is what lets §6.2 safely persist-then-advance and lets the DB-backed
lease (§6.1) be advisory rather than perfect.

---

## 7. Interplay with reconciliation & loop safety

### 7.1 Reconciliation stays the safety net

Changelog capture is **low-latency steady-state**; reconciliation is the
**catch-up / repair** path. They compose cleanly because changelog events land
in the *same* `replication_events` queue, so reconciliation's existing
"suppress findings shadowed by undelivered events" logic
(`findUndeliveredTargetDns`) already accounts for in-flight changelog events —
no change needed.

Reconciliation is the **repair** path, but it must not be the *only* line of
defence — a scheduled reconcile may be an hour away, and "we silently dropped
changes until someone noticed drift" is exactly the failure this feature must
not have. So the poller **actively detects** the two danger conditions and
triggers repair immediately rather than waiting:

- **Changelog trim window.** If the poller is down longer than OUD's changelog
  retention (`cn=changelog` purges old `changeLogEntry`s), the cursor points
  past the oldest surviving entry → missed changes. **Detected each poll** via
  the root-DSE `firstChangeNumber` guard (§6.2.3 / §7A.1): on detection, alert,
  auto-trigger reconciliation, and resume — never a silent skip. Still document
  the operational requirement (poll interval ≪ changelog retention) so the gap
  path stays rare.
- **Initial state.** Changelog only carries changes from the seed point
  forward; reconciliation (auto-triggered on mode switch, §7A.8) seeds
  pre-existing entries (§2.1).

### 7.2 Loop safety

- **Unidirectional single link:** no loop. The poller reads the *source*
  changelog; worker writes to the *target*. Target writes never appear in the
  source changelog.
- **Multi-hop / a directory that is both a source and a target:** a delivery
  into directory D (as target of link B) appears in D's changelog and would be
  re-captured if D is also a `CHANGELOG`-mode source of link A. v1 **documents
  this as a limitation.** Optional hardening: skip `changeLogEntry`s whose
  `creatorsName` equals the link's bind DN (the portal's own writes) — the
  attribute is already read by `DseeChangelogStrategy`. Recommend implementing
  the `creatorsName` filter since it's nearly free and prevents the foot-gun.

---

## 7A. Reliability & observability hardening (must-have for v1)

Replication must be **trustworthy** and **loud when wrong**. The guiding
principle: *never lose a change silently, and surface every degradation through
the existing alert pipeline within one poll interval — not on a dashboard
someone has to remember to open.* The items below are v1 acceptance criteria,
not nice-to-haves.

### 7A.1 Gap detection (no silent skip on changelog trim)
Each poll reads the root-DSE `firstChangeNumber`. `cursor + 1 < firstChangeNumber`
⇒ entries were trimmed before we read them. Action: set health `GAP_DETECTED`,
emit `REPLICATION_CHANGELOG_GAP_DETECTED` (audit → SIEM → alert), auto-trigger a
reconciliation run for the link, fast-forward the cursor to
`firstChangeNumber − 1`, resume. The skipped span is repaired by reconciliation;
the operator is told it happened.

### 7A.2 Cursor-reset detection (no silent stall on source restore)
`lastChangeNumber < cursor` ⇒ the source changelog was reinitialized
(backup restore, server rebuild). Without this check the cursor never matches
again and replication stalls **with zero errors** — the nastiest failure mode.
Action: set health `CURSOR_RESET`, emit `REPLICATION_CHANGELOG_CURSOR_RESET`,
**stop advancing**, and require an explicit operator reseed (a deliberate,
audited decision — auto-reseeding could replay or skip an entire DIT).

### 7A.3 Poison-entry policy (flow + no loss + audit)
A single unparseable `changes` blob must neither silently advance past the entry
(loss) nor wedge the link forever (availability). Policy: **dead-letter the raw
entry** as a `DEAD_LETTERED` `replication_event` (raw attributes + parse error in
the payload), emit `REPLICATION_CHANGELOG_ENTRY_DEAD_LETTERED`, continue.
Recoverable, audited, and reconciliation re-derives that DN anyway. Operators get
the existing dead-letter retry/skip controls.

### 7A.4 Exactly-once
Mandatory dedup index + insert-if-absent persister semantics (§6.5). Makes
crash-replay, concurrent pollers, and stale-lease double-claims safe.

### 7A.5 HA single-flight
DB-backed lease + stale-claim sweep (§6.1), not an in-JVM map. Safe across
multiple portal instances.

### 7A.6 Cursor integrity
CAS advance to the highest *contiguously*-processed `changeNumber` (§2.1, §6.2.7).
No lost updates vs. operator edits; no holes on mid-page failures.

### 7A.7 Liveness surfacing — *lag is the headline signal*
Per-link, persisted and exposed on `ReplicationLinkResponse` + the Directory
Sync dashboard row:

| Field | Meaning |
|---|---|
| `changelogHealth` | `HEALTHY` / `LAGGING` / `STALLED` / `GAP_DETECTED` / `CURSOR_RESET` / `DISABLED_CONFIG_ERROR` |
| **lag** = `sourceLastChangeNumber − cursor` | how many source changes are un-replicated — the primary at-a-glance health number |
| `changelogLastPolledAt` | liveness; `STALLED` if stale beyond N intervals |
| `changelogLastError` / `…At` | last poll/parse/connection error, for fast diagnosis |

Health transitions: `LAGGING` when lag or oldest-undelivered age exceeds a
configurable threshold; `STALLED` when `lastPolledAt` is older than N×interval
while the source head advanced. Surface a `lastDeliveredAt`/`pending`/`failed`/
`deadLettered` rollup too — `LinkHealth` already computes these for the worker;
reuse it.

### 7A.8 Mode-switch seam closure
Flipping `APP_INTERCEPT`↔`CHANGELOG` (or enabling a link in either mode) leaves a
race window where a write is caught by neither path. On every capture-mode
transition, `ReplicationLinkService` **auto-triggers a reconciliation run** to
close the seam, and resets the cursor so the new mode re-seeds cleanly. Switching
*away* from `CHANGELOG` first lets the queue drain.

### 7A.9 Alert-pipeline wiring (reach people, not screens)
Route every degradation through what already exists rather than inventing a
channel:
- **New `AuditAction`s:** `REPLICATION_CHANGELOG_GAP_DETECTED`,
  `…_CURSOR_RESET`, `…_POLL_DISABLED`, `…_ENTRY_DEAD_LETTERED`,
  `…_CAPTURE_ENABLED` / `…_CAPTURE_DISABLED`. Audit rows export to **SIEM**
  through the existing `AuditService` path automatically.
- **Dashboard alert counts:** extend the `AlertSummaryProvider` /
  `AlertingDashboardProvider` surfacing (as reconciliation drift already does via
  `RECONCILIATION_DRIFT_OPEN`) with a changelog-lag / stalled-link signal so
  unhealthy links roll into the critical/high alert tiles.
- **Metrics:** the repo ships **no Micrometer** today, so v1 does **not** add a
  metrics dependency — surfacing rides audit + SIEM + dashboard. If a metrics
  stack lands later, expose `replication_changelog_lag` (gauge per link),
  poll duration, and parse-failure counters; noted so it's a clean add.

### 7A.10 Connection isolation
Poll the changelog over a **dedicated, bounded-timeout** connection — not the
source directory's shared live pool — so a hung or slow changelog read can't
starve interactive traffic. Mirror `LdapChangelogReader`'s short-lived
connection with explicit connect/response timeouts.

### 7A.11 Config-time validation (fail before enabling, not after)
`test-changelog` (§9) must verify, and refuse to enable otherwise: `cn=changelog`
is readable, `changeNumber` is present on entries, **and** the root DSE exposes
`firstChangeNumber`/`lastChangeNumber` (without them, gap/reset detection is
blind — hard-fail with a clear message). Warn if the configured poll interval is
not comfortably below the server's changelog retention where discoverable.

### 7A.12 Operator remediation controls (act fast)
Beyond the existing event retry/skip/acknowledge, add per-link actions:
**reseed cursor to now**, **rewind cursor to N**, **force reconcile**,
**re-enable after config error**. Each is audited. These turn a 2 a.m. incident
into a one-click recovery instead of a DB surgery.

---

## 8. Extensibility (OpenLDAP / AD later)

The SPI shape makes later servers additive:

- **OpenLDAP `accesslog`:** `AccesslogStrategy` already does audit detection;
  add `extractChange` parsing `reqMod`/`reqNewRDN`/etc. into our payload, widen
  the `changelog_format` CHECK to include `OPENLDAP_ACCESSLOG`, and allow it in
  service validation. No poller, schema-shape, or enqueue changes.
- **AD `DirSync`:** harder — DirSync returns *current attribute state*, not
  per-attribute operations, and uses an opaque binary **cookie** instead of an
  integer cursor. Two accommodations when it lands: (a) a
  `changelog_dirsync_cookie BYTEA` column (cursor is format-specific —
  generalize `changelogLastChangeNumber` into a small per-format cursor
  abstraction at that point), and (b) `extractChange` modeling changes as
  REPLACE-style MODIFY / re-read-and-ADD / DELETE. Deferred — called out so v1
  doesn't paint the cursor model into a corner (keep the cursor read/write
  behind a tiny `ChangelogCursorStore` seam so swapping int→cookie is local).

v1 ships the int-`changeNumber` cursor concretely; §6.2 routes all cursor
read/advance through one place to keep that swap cheap.

---

## 9. API & service (Phase C4, backend)

- **DTOs** (`dto/replication/ReplicationLinkRequest` + `…Response`): add
  `captureMode`, `changelogFormat`, `changelogBaseDn`. Response also exposes
  read-only health surface (§7A.7): `changelogLastChangeNumber`,
  `changelogSourceLastChangeNumber`, derived **`lag`**, `changelogHealth`,
  `changelogLastPolledAt`, `changelogLastError`/`…At`.
- **Validation** (`ReplicationLinkService`):
  - `captureMode` required; default `APP_INTERCEPT`.
  - If `CHANGELOG`: `changelogFormat` must be `DSEE_CHANGELOG` (v1) — reject
    others with `IllegalArgumentException` ("changelog capture supports OUD /
    cn=changelog only in this version"); `changelogBaseDn` required (default
    `cn=changelog` if blank). If `APP_INTERCEPT`: null out changelog fields.
  - Switching modes resets the cursor (`changelogLastChangeNumber = null`) so a
    newly-enabled `CHANGELOG` link re-seeds (§2.1).
  - Emit the existing `AuditAction.REPLICATION_*` config-update audit.
- **Test-changelog endpoint** on `ReplicationLinkController`:
  `POST /api/v1/superadmin/replication-links/{id}/test-changelog` (and a
  pre-save variant taking a request body, mirroring
  `AuditDataSourceService.testConnection`): opens the source connection,
  confirms `changelogBaseDn` is readable, `changeNumber` is present, **and the
  root DSE exposes `first/lastChangeNumber`** (§7A.11 — hard-fail otherwise),
  returns current head + elapsed ms.
- **Remediation endpoints** (§7A.12), all `@PreAuthorize SUPERADMIN` +
  `@Entitled(DIRECTORY_SYNC)`, all audited:
  `…/{id}/changelog/reseed` (cursor → current head),
  `…/{id}/changelog/rewind` (cursor → operator-supplied N),
  `…/{id}/reconcile` (force; reuse existing reconcile trigger),
  `…/{id}/changelog/re-enable` (clear config-error disable).
- All Directory-Sync endpoints stay `@PreAuthorize("hasRole('SUPERADMIN')")` +
  `@Entitled(DIRECTORY_SYNC)`.

---

## 10. Frontend (Phase C4, UI)

`frontend/src/views/superadmin/DirectorySyncView.vue` link create/edit modal:

- **Capture-mode** radio / segmented control: *App writes (default)* vs.
  *Source changelog*.
- When *Source changelog* is selected, reveal: **format** dropdown (only
  *OUD (cn=changelog)* enabled in v1; other entries disabled with a "coming
  soon" hint), **changelog base DN** input (default `cn=changelog`, with the
  same auto-default UX `AuditSourcesView.vue` uses), and a **Test changelog**
  button wired to the new endpoint (reuse the audit-source test-result display:
  success/failure + elapsed ms + current max changeNumber).
- **Health-first row surfacing (§7A.7):** a **lag** number + a colored
  `changelogHealth` badge (`HEALTHY`/`LAGGING`/`STALLED`/`GAP_DETECTED`/
  `CURSOR_RESET`/`DISABLED_CONFIG_ERROR`) on each link row, plus
  `lastPolledAt` and last-error tooltip — so a degraded link is obvious at a
  glance, not buried. Unhealthy links also roll into the dashboard alert tiles
  (§7A.9).
- **Remediation controls (§7A.12)** on the link/detail: *Reseed to now*,
  *Rewind to…*, *Force reconcile*, *Re-enable* — wired to the §9 endpoints with
  confirm dialogs, so recovery is one click.
- Help text: explains exclusivity, the seed-from-now behavior, that
  reconciliation remains the backstop, and what each health state means / how to
  act on it.
- Conventions: `<script setup lang="ts">`, project utility classes
  (`.input`/`.btn-*`), Pinia notification store for results, spec updates in
  `DirectorySyncView.spec.ts`, `api/replication.js` gains
  `testReplicationChangelog(...)`. Follow `docs/frontend-conventions.md`.

---

## 11. Testing

- **Unit (bulk):** `OudChangelogChangeParserTest` (§5 edge cases) —
  add/modify/delete/modrdn, base64, folded lines, whole-attr delete,
  `newSuperior` present/absent, options/`;binary`.
- **Unit:** `ReplicationPayloadMapper` parity test — same input must yield the
  same payload via the enqueuer path and the poller path.
- **Unit:** strategy `extractChange` for `DseeChangelogStrategy`; refactor
  regression via existing `AccesslogStrategyTest`.
- **Slice/integration:** poller emits the right `PendingReplicationEvent`s for
  a sequence of `changeLogEntry`s, advances the cursor, seeds on first run,
  skips out-of-scope DNs, and is idempotent on replay. Use an in-memory
  UnboundID `InMemoryDirectoryServer` seeded with `changeLogEntry`s if feasible,
  else mock the strategy output.
- **MockMvc:** link create/update validation (mode exclusivity, format
  rejection), `test-changelog` authz/shape, enqueuer-skip for `CHANGELOG`
  links.
- **Reliability scenarios (§7A) — first-class tests, not afterthoughts:**
  - gap detected (`cursor+1 < firstChangeNumber`) → alert audit + reconcile
    triggered + cursor fast-forwarded.
  - cursor reset (`lastChangeNumber < cursor`) → halts, alerts, no advance.
  - poison entry → dead-lettered with raw payload, stream continues.
  - exactly-once: replaying the same `changeNumber` (crash sim / double poll)
    inserts no duplicate (dedup index).
  - HA lease: two concurrent `pollAll()` invocations process each link once.
  - cursor CAS: concurrent operator edit + cursor advance both persist (no lost
    update).
  - lag/health transitions: `HEALTHY → LAGGING → STALLED` thresholds.
- **Frontend:** `DirectorySyncView.spec.ts` — mode toggle reveals fields,
  format restriction, test-changelog call + result rendering, **lag/health
  badge + remediation controls** render and call the right endpoints.

---

## 12. Phasing & rough LOE (one engineer)

| Phase | Content | Est. |
|---|---|---|
| **C1** | Schema (`V15`: capture/changelog cols **+ liveness/lease/health cols**, dedup index), entity/enum, snapshot field, DTO+service plumbing & validation | ~3–4 days |
| **C2** | Generalize `ChangelogStrategy` (neutral context) + `extractChange` SPI + `OudChangelogChangeParser` + tests | ~5–6 days |
| **C3** | `ReplicationChangelogPoller` (poll, seed, **catch-up budget**, CAS cursor, **DB lease + stale sweep**, backoff, entitlement) + `ReplicationPayloadMapper` extract + enqueuer skip + `creatorsName` loop guard | ~5 days |
| **C3R** | **Reliability/observability (§7A):** gap + reset detection, poison dead-letter, exactly-once dedup wiring, health state machine + lag, `AlertSummaryProvider`/SIEM/audit-action wiring, mode-switch auto-reconcile, operator remediation controls | ~5 days |
| **C4** | `test-changelog` (incl. root-DSE capability check), frontend modal + lag/health surfacing + remediation controls, docs, integration/MockMvc/Vitest (incl. §7A scenarios) | ~5 days |

**≈ 4.5–5 weeks** for a *production-hardened* OUD MVP (the §7A work is the
delta over the earlier ~3.5–4w estimate, and is what makes the feature
trustworthy). OpenLDAP `extractChange` is a later ~1-week add behind the same
SPI; AD `DirSync` is a larger follow-on (cursor model + state-not-ops
semantics, §8).

---

## 13. Open questions / call-outs

*Resolved by the §7A hardening pass: exactly-once dedup is now **mandatory**
(§6.5), not optional; the `creatorsName` loop guard is **in v1** (§7.2); gap
detection is **active**, not reconciliation-only (§7A.1).* Remaining:

1. **Per-source vs. per-link cursor** — this plan uses **per-link** (simplest,
   matches per-link scope/mapping/mode). Two `CHANGELOG` links sharing a source
   each scan `cn=changelog` independently. If that redundancy matters at scale,
   a later optimization can share one source-level reader fanning out to links
   — out of scope for v1.
2. **`creatorsName` reliability smoke check** — confirm the portal's source bind
   DN is the value OUD records as `creatorsName` for portal-originated writes
   (expected, but verify against a real OUD before trusting the loop guard).
3. **Root-DSE `firstChangeNumber`/`lastChangeNumber` on the target OUD build** —
   gap/reset detection (§7A.1–.2) depends on them. Confirmed standard for OUD;
   `test-changelog` (§7A.11) hard-fails config if absent, so a non-conforming
   server can't be silently enabled.
4. **Lag/stall thresholds** — pick sensible defaults for `LAGGING`/`STALLED`
   (e.g. lag > 1000 or oldest-undelivered > 5 min; stalled > 3× interval) and
   make them configurable. Operator-tunable per environment.

---

## 14. Component map (to be filled in as built)

| Area | Lives in |
|---|---|
| Schema | `db/migration/core/V15__replication_changelog_capture.sql` (capture + changelog + **liveness/lease/health** cols, **dedup index**) |
| Entity / enums | `entity/ReplicationLink` (+fields incl. health/lag), `entity/enums/ReplicationCaptureMode`, `…/ChangelogHealth`; reuse `ChangelogFormat`; new `AuditAction.REPLICATION_CHANGELOG_*` |
| SPI | `ldap/changelog/ChangelogStrategy` (`ChangelogReadContext`, `extractChange`), `ChangelogChange`, `OudChangelogChangeParser`; `DseeChangelogStrategy.extractChange` |
| Poller | `ldap/replication/ReplicationChangelogPoller` (gap/reset/poison guards, catch-up budget), `ReplicationPayloadMapper`, `ChangelogCursorStore` (CAS), `ChangelogPollLease` (DB single-flight + stale sweep) |
| Enqueuer | `ReplicationEnqueuer` (skip CHANGELOG), `ReplicationLinkSnapshot` (+captureMode) |
| Repo | `ReplicationLinkRepository`: `findChangelogCaptureLinkIds`, CAS cursor advance, lease claim/release/stale-reset; dedup-aware persister insert-if-absent |
| Health / alerts | health state machine on `ReplicationLinkResponse` + `LinkHealth` reuse; `AlertSummaryProvider`/`AlertingDashboardProvider` lag signal; SIEM via `AuditService` |
| API | `ReplicationLinkController`: `test-changelog`, remediation (`reseed`/`rewind`/`force-reconcile`/`re-enable`); `ReplicationLinkRequest/Response` fields; `ReplicationLinkService` validation + mode-switch auto-reconcile |
| Frontend | `views/superadmin/DirectorySyncView.vue` (lag/health badge, remediation controls), `api/replication.js`, `DirectorySyncView.spec.ts` |
| Docs | this file; update `docs/directory-replication.md` |
