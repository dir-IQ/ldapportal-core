# Changelog-driven replication — design plan

- **Date:** 2026-06-03
- **Status:** Not started (design only, 2026-06-03).
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
  ADD COLUMN changelog_last_change_number BIGINT,
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
run (or an operator-triggered initial load) — the same bootstrap story the
`APP_INTERCEPT` path already relies on. Document this in the UI help text.

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
- Per-link **single-flight** guard + per-link consecutive-failure backoff and
  config-error disable — copy the proven shape from `LdapChangelogReader`
  (`ConcurrentMap<UUID,Integer>` + `Set<UUID> configErrors`).
- Wrap each link's poll in `CorrelationContext.withCorrelation(...)` so
  emitted events share a trace id (consistent with the rest of the system).

### 6.2 Per-link poll

1. Load the `ReplicationLinkSnapshot` (`snapshotById`) for DN/attr mapping.
2. Open the **source** directory connection via `LdapConnectionFactory`
   (the link's `sourceDirectory`). Reads don't trigger capture, so no
   `unreplicated` wrapper is required — but use the read-only path.
3. First-run seed if `changelogLastChangeNumber == null` (§2.1): set cursor =
   `max(changeNumber)`, persist, return.
4. Build `ChangelogReadContext(changelogBaseDn, link.sourceBaseDn,
   cursor)` and the strategy's incremental search; cap at
   `MAX_CHANGES_PER_POLL` (e.g. 500), ordered by `changeNumber` ascending.
5. For each entry, in `changeNumber` order:
   - `strategy.extractChange(entry)` → `ChangelogChange` (skip if empty).
   - `DnMapper.map(sourceDn, link)` → target DN; `null` ⇒ out of scope, skip
     (but still advance the cursor past it).
   - Map the payload's attributes/modifications via `AttributeMapper`
     (factor the enqueuer's `mappedAddAttributes`/`mappedModifications` into a
     shared `ReplicationPayloadMapper` so both call sites use one
     implementation — see §6.3).
   - Build a `PendingReplicationEvent` with
     `enqueueSource = SOURCE_CHANGELOG`.
6. `persister.saveAll(pending)` then **advance the cursor** to the highest
   `changeNumber` processed — in that order, so a crash between persist and
   cursor-advance replays a bounded prefix. Idempotency: re-enqueuing the same
   `changeNumber` is harmless because the target write is effectively
   idempotent (MODIFY/REPLACE, ADD-with-auto-create), and a tighter guard can
   key on `(link_id, source changeNumber)` if needed (see §6.5).

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

### 6.5 Idempotency hardening (optional, recommend including)

To make replay strictly exactly-once at the queue, add a partial unique index
on `replication_events (link_id, (payload->>'sourceChangeNumber'))` for
`SOURCE_CHANGELOG` rows, and stamp `sourceChangeNumber` into the payload. The
persister already swallows nothing here, so the cleaner route is a
`findMaxSourceChangeNumberForLink` check folded into the cursor read. Keep this
behind a flag if it complicates the v1 persister; the bounded-replay +
idempotent-delivery story (§6.2.6) is correct without it.

---

## 7. Interplay with reconciliation & loop safety

### 7.1 Reconciliation stays the safety net

Changelog capture is **low-latency steady-state**; reconciliation is the
**catch-up / repair** path. They compose cleanly because changelog events land
in the *same* `replication_events` queue, so reconciliation's existing
"suppress findings shadowed by undelivered events" logic
(`findUndeliveredTargetDns`) already accounts for in-flight changelog events —
no change needed.

Two gaps reconciliation explicitly covers, so we don't have to:

- **Changelog trim window.** If the poller is down longer than OUD's changelog
  retention (`cn=changelog` purges old `changeLogEntry`s), the cursor can point
  past the oldest surviving entry → missed changes. The next reconciliation run
  detects and repairs the drift. Document the operational requirement: poll
  interval ≪ changelog retention.
- **Initial state.** Changelog only carries changes from the seed point
  forward; reconciliation (or initial load) seeds pre-existing entries (§2.1).

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
  read-only `changelogLastChangeNumber` (operator visibility into cursor
  progress).
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
  confirms `changelogBaseDn` is readable, returns the current max
  `changeNumber` + elapsed ms. `@PreAuthorize("hasRole('SUPERADMIN')")` +
  `@Entitled(DIRECTORY_SYNC)` to match the rest of Directory Sync.

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
- Show read-only **cursor progress** (`changelogLastChangeNumber`) on the link
  row / detail so operators can confirm the poller is advancing.
- Help text: explains exclusivity, the seed-from-now behavior, and that
  reconciliation remains the backstop.
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
- **Frontend:** `DirectorySyncView.spec.ts` — mode toggle reveals fields,
  format restriction, test-changelog call + result rendering.

---

## 12. Phasing & rough LOE (one engineer)

| Phase | Content | Est. |
|---|---|---|
| **C1** | Schema (`V15`), entity/enum, snapshot field, DTO+service plumbing & validation | ~3 days |
| **C2** | Generalize `ChangelogStrategy` (neutral context) + `extractChange` SPI + `OudChangelogChangeParser` + tests | ~5–6 days |
| **C3** | `ReplicationChangelogPoller` (poll, seed, cursor, single-flight, backoff, entitlement) + `ReplicationPayloadMapper` extract + enqueuer skip + `creatorsName` loop guard | ~5 days |
| **C4** | `test-changelog` endpoint, frontend modal + cursor surfacing, docs, integration/MockMvc/Vitest | ~4 days |

**≈ 3.5–4 weeks** for the OUD MVP. OpenLDAP `extractChange` is a later
~1-week add behind the same SPI; AD `DirSync` is a larger follow-on (cursor
model + state-not-ops semantics, §8).

---

## 13. Open questions / call-outs

1. **Cursor uniqueness hardening (§6.5)** — ship the partial unique index in
   v1, or rely on bounded-replay + idempotent delivery? Recommend the index if
   the persister can surface a conflict cleanly; otherwise defer.
2. **Per-source vs. per-link cursor** — this plan uses **per-link** (simplest,
   matches per-link scope/mapping/mode). Two `CHANGELOG` links sharing a source
   each scan `cn=changelog` independently. If that redundancy matters at scale,
   a later optimization can share one source-level reader fanning out to links
   — out of scope for v1.
3. **`creatorsName` loop guard** — recommended in v1 (§7.2). Confirm the
   portal's source bind DN is the value OUD records as `creatorsName` for
   portal-originated writes (it is, but worth a smoke check against a real OUD).

---

## 14. Component map (to be filled in as built)

| Area | Lives in |
|---|---|
| Schema | `db/migration/core/V15__replication_changelog_capture.sql` |
| Entity / enums | `entity/ReplicationLink` (+fields), `entity/enums/ReplicationCaptureMode`; reuse `ChangelogFormat` |
| SPI | `ldap/changelog/ChangelogStrategy` (`ChangelogReadContext`, `extractChange`), `ChangelogChange`, `OudChangelogChangeParser`; `DseeChangelogStrategy.extractChange` |
| Poller | `ldap/replication/ReplicationChangelogPoller`, `ReplicationPayloadMapper` |
| Enqueuer | `ReplicationEnqueuer` (skip CHANGELOG), `ReplicationLinkSnapshot` (+captureMode) |
| Repo | `ReplicationLinkRepository.findChangelogCaptureLinkIds`, cursor read/advance |
| API | `ReplicationLinkController.test-changelog`; `ReplicationLinkRequest/Response` fields; `ReplicationLinkService` validation |
| Frontend | `views/superadmin/DirectorySyncView.vue`, `api/replication.js`, `DirectorySyncView.spec.ts` |
| Docs | this file; update `docs/directory-replication.md` |
