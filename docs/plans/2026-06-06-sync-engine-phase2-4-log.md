# Sync engine — Phases 2–4 decisions & deferrals log

Running record of design decisions made (best-judgment) and review findings
deferred while implementing Phases 2–4 of
`docs/plans/2026-06-06-sync-engine-implementation-plan.md`. Presented as a
summary at the end of the effort.

## Phase 2 — rich config + identity + brownfield + UI

### Decisions
- **Config columns (V3 migration):** added `sync_set.reconcile_cadence_seconds`,
  `sync_set.reconcile_last_run_at`, and a `REVIEW` `membership.state` (check
  constraint widened). The rest of the projection/selection config already
  landed in Phase 1's V2.
- **identityKey override:** a per-set `identityKey` attribute overrides the
  directory-type SPI default, threaded everywhere via a new `SyncIdentity`
  helper (engine reads/searches, membership function, reconciler).
- **Brownfield adoption (sourceAnchor):** on a first encounter (or while held in
  REVIEW), an exact+unique `sourceAnchor=identity` match adopts the existing
  target entry (converge + MODDN to placement); >1 matches, or an unanchored
  entry already sitting at the placement DN, → REVIEW quarantine (never
  auto-overwrite). Greenfield (no match, free placement) → normal ADD.
- **deletePolicy=REVIEW** now quarantines a scope-exit as a REVIEW row (surfaces
  in the inventory) instead of silently retaining the target (fixed in review).
- **Per-set reconcile cadence:** `SyncReconcileScheduler` ticks ~60s and
  reconciles only *due* sets (`reconcile_last_run_at + cadence` elapsed); cadence
  null → global default (`ldapportal.sync.reconcile.default-cadence-seconds`,
  3600). Replaces Phase 1's coarse global cadence.
- **API:** `SyncLinkController` + `SyncSetController` under
  `/api/v1/superadmin/sync`, `@Entitled(DIRECTORY_SYNC)` + superadmin. CRUD +
  membership inventory + operator triggers (reconcile / recompute / dismiss).
  Quarantine resolution = recompute (re-evaluate after the operator anchors the
  right target entry) or dismiss (drop tracking, target retained).
- **Frontend:** rewrote `DirectorySyncView` (links + sets CRUD, membership
  inventory with state filter, operator triggers), new typed `api/sync.ts`,
  removed the stale `api/replication.js` + old view/spec.

### Review findings — fixed
- deletePolicy=REVIEW scope-exit now creates a REVIEW membership row so it
  surfaces in the inventory (was a silent log+retain).

### Review findings — deferred
- **Config-time identity present+unique LDAP probe** (a `validate-identity`
  endpoint): only structural validation (DN/filter syntax, non-blank key) is
  done. The engine quarantines *ambiguous anchor matches* at runtime but does not
  pre-validate that the identity key is unique in scope; a non-unique key (e.g.
  `identityKey=mail`) would collide membership rows. Severity: medium (operator
  footgun); mitigated by the runtime quarantine for anchor ambiguity.
- **Mutable-key destructive-churn warning:** the design wants a loud warning when
  an operator sets `identityKey` to a mutable attribute (rename → delete+recreate).
  Not surfaced in UI/validation. Severity: low/medium.
- **Quarantine resolution actions:** only recompute/dismiss provided; an explicit
  "force-adopt to DN X" (write the anchor for the operator) and "confirm delete"
  are deferred. Severity: low (recompute-after-manual-anchor works).
- **transformRules UI editor:** **Shipped (2026-06-07).** A collapsible "Attribute
  mapping" section in the sync set modal, backed by a reusable
  `TransformRulesEditor` component (`v-model:rules`), lets operators author the
  rename + `${value}` rules that were previously API-only. `SyncConfigService` now
  validates (non-blank/unique `sourceAttr`, attribute-name syntax, `${value}`-only
  template) and normalizes the rules. Originally deferred from Phase 2 — UI
  completeness, medium.
- **`@Entitled` 403 enforcement test:** the `@WebMvcTest` slice doesn't load the
  entitlement aspect, so the 403-without-DIRECTORY_SYNC path is unverified by the
  controller test (the engine-level gate is tested). Severity: low.
- **Config-edit concurrency:** link/set updates are last-writer-wins (the DTO
  carries no version for a conditional update). Severity: low.
- **Perf:** brownfield anchor search adds one target read per first-encounter ADD
  when `sourceAnchor` is configured; the captor's per-write `sync_links` lookup is
  still uncached (carried from Phase 1). Severity: low.

## Phase 3 — changelog adapter + changestamp reconcile

### Decisions
- **Changelog-capture adapter (`SyncChangelogPoller`):** the headline Phase 3
  feature. Scheduled, `DIRECTORY_SYNC`-gated, HA-leased; for each CHANGELOG-mode
  link it reads the source changelog via the existing `ChangelogStrategy` SPI and
  emits `recompute(targetDN)` per change record — **including DELETE records**
  (the engine re-reads the source → absent → OUT → deletes via the index). The
  design's key simplification: the lossy `changes` blob is ignored, so there is
  **no exactly-once dedup, no per-link FIFO, no LDIF reconstruction** —
  convergence makes a bare "this DN changed" sufficient. Cursor advance, lag/gap/
  cursor-reset health, and a reconcile trigger on gap are kept.
- **V4 migration** adds the changelog columns (format, base, cursor, source-head,
  health, poll-lease, errors) + cfg/health/format check constraints + the
  capture index to `sync_links`; new `SyncChangelogHealth` enum.
- **Link config API** extended with `captureMode` + `changelogFormat` /
  `changelogBaseDn` (required + validated for CHANGELOG); response carries
  changelog status. Frontend link form gained a capture-mode selector + changelog
  config.

### Review findings — fixed
- **Changelog rename = MODDN, not delete+recreate (general engine fix):** a
  changelog modrdn record reports the *pre-move* DN; a DN-keyed recompute that
  finds the entry gone *but the identity is tracked* now re-searches by identity
  to catch a move, so a rename converges as a MODDN instead of a destructive
  delete+recreate (which would break the stable-identity guarantee and drop
  references). Benefits every feed that reports a pre-move DN.

### Review findings — deferred
- **Changestamp-driven reconcile optimization** (minimal-attr enumeration of
  DN/identity + `entryCSN`/`modifyTimestamp`/`uSNChanged`, deep-read only drifted
  entries): the design pairs this with the changelog adapter, but the existing
  reconcile is correct (just O(N) source reads), and the scheduled reconcile +
  changelog adapter cover correctness. **Deferred — Severity: medium (perf/steady-
  state read amplification, the design's Risk a).**
- **Changelog cursor ordering assumption:** the poller advances the cursor to the
  max `changeNumber` in the batch, assuming the strategy returns records in
  ascending `changeNumber` order (DSEE/UnboundID do). A server returning >batch
  records unordered could skip; the scheduled reconcile is the backstop.
  Severity: medium (mitigated).
- **Only `DSEE_CHANGELOG` wired for sync capture** (the OpenLDAP-accesslog / AD-
  DirSync strategies exist for audit but aren't wired here; the format constraint
  allows only DSEE). Phase 4 generalizes. Severity: low.
- **Changelog health UI** is minimal (the response carries health/cursor/lag, but
  the view shows capture mode without a dedicated health panel). Severity: low.

## Phase 4 — heterogeneous feeds (cursor generalization + seam)

### Decisions / scope
- **Delivered the design's enabling change: generalize the changelog cursor to an
  opaque token.** V5 adds `sync_links.changelog_cursor_token TEXT` (backfilled
  from the numeric cursor); the poller now treats the token as the canonical
  cursor via a `SyncChangelogCursor` codec (DSEE family ↔ decimal changeNumber).
  The numeric `changelog_last_change_number` is retained as a per-format lag/
  observability mirror. Cookie-based feeds (DirSync / syncrepl / Entra delta)
  persist their cookie verbatim in the same token column — the codec documents
  the per-feed interpretation seam.
- **Scope decision (best judgment): deferred the concrete heterogeneous feed
  adapters** (AD DirSync, syncrepl, Entra-Graph-delta). Rationale: (1) cookie-
  based protocol feeds need source-specific read/search paths (the DirSync
  control, syncrepl) that the UnboundID in-memory test harness can't exercise, so
  wiring them would be untested / low-confidence; (2) **Entra is non-LDAP** and
  the engine's source-read path is LDAP-only (`withConnectionUnreplicated`) —
  wiring Entra into the membership engine needs a non-LDAP source-read seam, a
  larger architectural addition (the codebase keeps Entra in a separate Graph-
  delta subsystem). The cursor generalization + token column + codec seam are the
  enabling foundation these adapters build on; that foundation is delivered and
  tested. **Severity: this is the largest deferral — the feeds themselves are not
  implemented, only their cursor/seam.**

### Review findings — fixed
- None urgent (small, clean generalization).

### Review findings — deferred
- **AD DirSync / syncrepl / Entra-Graph-delta adapters** — not implemented (see
  scope decision above). The opaque-token cursor + `SyncChangelogCursor` seam are
  the plug-in point.
- **SPI-level cursor generalization:** `ChangelogStrategy` / `ChangelogReadContext`
  still use a `Long afterChangeNumber` (shared with the audit reader). Generalizing
  the strategy's read context to an opaque cursor (needed for cookie feeds) is the
  remaining step; deferred to avoid destabilizing the shared audit SPI in this
  increment. Severity: medium (prerequisite for the cookie adapters).

## Closing summary (Phases 2–4)

Phases 2 and 3 are delivered effectively complete (rich config + identity +
brownfield + UI; the changelog-capture adapter). Phase 4 delivers the cursor
generalization + seam but **defers the concrete heterogeneous feed adapters** for
the reasons above — that is the single largest piece of the original plan left
unbuilt. Three review findings were fixed inline (deletePolicy=REVIEW quarantine;
changelog-rename = MODDN; the Phase-1 hardening that preceded this batch). The
highest-value remaining items are: the changestamp-driven reconcile (read-
amplification, Phase 3 deferral), config-time identity present+unique validation
(Phase 2 deferral), and the heterogeneous feed adapters (Phase 4 deferral).
