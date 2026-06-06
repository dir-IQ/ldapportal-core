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
- **transformRules UI editor:** transform rules are API-configurable and preserved
  on edit, but the Phase 2 form has no attribute-mapping editor. Severity: medium
  (UI completeness).
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
