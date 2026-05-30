# Directory replication — migration plan (rev. 2)

> **Baseline:** `feat/directory-sync` at commit `875a1e2`. Builds on the
> shipped impl; doesn't restart.
>
> **Status:** Plan only. R1a is the next-step recommendation; R6 is
> demand-driven and won't ship without a separate spec.
>
> **Companion docs:**
> - [`2026-05-30-directory-sync-design.md`](./2026-05-30-directory-sync-design.md)
>   — design doc for the shipped impl.
> - This plan tracks the deltas between that impl and the target
>   architecture from the original `2026-05-22-directory-replication-addon-design`
>   spec; references the original for context but supersedes its
>   sequencing.

**Goal:** Close the gaps between the shipped directory-sync impl and the
target architecture while preserving the operational maturity already
in the codebase. Sequence phases so additive risk-free work lands
first; defer the architecturally-disruptive bits until demanded.

**Changed from rev. 1 (the first migration plan draft) per self-review:**

- R6 keeps post-commit capture semantics (the original plan's
  `BEFORE_COMMIT` framing was wrong — see R6 rationale below).
- R3's Flyway relocation replaced with a "tables stay, code moves"
  approach. Moving applied migrations breaks Flyway's `schema_history`.
- R1 split into R1a + R1b. Pure-structural changes ship separately
  from annotation-based architecture guards.
- New R0 "preservation contract" section anchors what shipped work
  is ratified by this plan as not up for re-litigation.
- `WriteSurfaceCoverageTest` and `JpaBoundaryArchitectureTest` use
  annotation- and package-allowlist forms; reflection-aware ArchUnit
  predicates are brittle.
- `correlation_id` plumbed via a `CorrelationContext` ThreadLocal so
  background work (HR sync, schedulers) gets a single UUID per top-
  level operation, not per LDAP call.
- `details.replicationOrigin` audit-detail field deferred (depends on
  per-replicated-write auditing which is an audit-volume increase
  decision).
- Upgrade-path verification gate made cross-cutting (every phase
  must verify against a canary tenant DB snapshot before merge).
- Distribution rename backward-compat semantics specified
  concretely (`::warning::` alias for one release).

---

## R0 — What stays (preservation contract)

**Not a coding phase.** Anchors what's already shipped and explicitly
ratified by this plan as "not up for re-litigation":

- Capture via `ReplicatingLdapInterface` wrapper at the SDK level —
  post-commit, fire-and-forget. The "source-write durability not
  coupled to replication-queue durability" property is preserved
  through every phase, including R6.
- Per-link `ReplicationLink` + `ReplicationLinkAttrMapping` config
  model. Attribute mapping (rename + value templates), DN base
  substitution, auto-create-on-missing.
- Lifecycle: `PENDING / IN_FLIGHT / DELIVERED / FAILED /
  DEAD_LETTERED / SKIPPED / ACKNOWLEDGED`.
- Snapshot pattern at the JPA boundary (`ReplicationLinkSnapshot`,
  `ReplicationEventSnapshot`, `ReplicationReadOps`).
- `claimed_at` column + settle status guards (the just-landed race fix).
- `DIRECTORY_SYNC` entitlement naming (no rename to
  `VENDOR_INTEGRATIONS_REPLICATION` — already in `/me`, frontend,
  audit actions).
- Per-event operator actions (retry/skip/acknowledge) with operator-
  identity audits.
- The two-tier toggle model (per-directory + per-link); per-link
  granularity ships in R2 even though the per-directory layer is new.
- Existing migration versions `V7`, `V8` keep their place in core's
  Flyway sequence.
- Table names (`replication_events`, `replication_links`,
  `replication_link_attr_mappings`) — table names predate the
  `addons_<addon>_` convention; renaming requires operator-visible
  downtime for zero customer-facing win.
- The outbox subsystem (`core.events`) stays in core and consumes
  `AuditRecordedEvent`. It does NOT migrate to consume
  `LdapStepExecutedEvent` in R6 — different source-of-truth, different
  timing. The two async subsystems share dispatcher mechanics
  (snapshots, `claimed_at`, settle guards) but not capture.

If a future PR proposes changing any of these, that's a separate
spec, not a refinement of this plan.

---

## Sequencing

| Phase | Topic | Risk | Cost | When |
|---|---|---|---|---|
| R1a | ArchUnit `JpaBoundaryArchitectureTest`; shared `BackoffPolicy` (no jitter); bidirectional-reject (state-blind) | None — pure structural | ~1 session | Now |
| R1b | `@LdapWriteAuthorized` marker + `WriteSurfaceCoverageTest`; annotate existing chokepoints | None — additive | ~1 session | After R1a |
| R2 | Per-directory `replication_enabled`; `CorrelationContext` ThreadLocal + `correlation_id` plumbing | Low | ~2 sessions | After R1b |
| R3 | Code-only module move `core/...` → `addons/replication/` (existing migrations + tables stay in core) | Medium | ~1-2 sessions | After R2 |
| R4 | Distribution rename `community-plus-isva` → `community-plus-addons` with alias-and-deprecate | Low | ~1 session | After R3 |
| R5 | Retention scheduler; `details.replicationEnabled` audit-detail contributor; operator docs; Playwright `@smoke` | Low | ~1-2 sessions | After R3 |
| R6 | `PlanExecutor` SPI widening + `LdapStepExecutedEvent` chokepoint swap, **post-commit semantics preserved** | High — capture-path change | Spec required | Demand-driven only |

Each phase ends with a mergeable commit. R1a + R1b in one PR. R2
stands alone. R3 stands alone. R4 stands alone. R5 may be 1-2 PRs.
R6 is gated on a specific spec referencing customer demand.

---

## Phase R1a — Structural guards + bidirectional fix + shared backoff type

**Branch:** `feat/replication-r1a-guards`

Risk-free additions; no on-the-wire behavior change.

### Tasks

- [ ] **Bidirectional-rejection guard** in `ReplicationLinkService.validateRequest`
  on create. Query for any existing link where
  `(sourceDirectoryId, targetDirectoryId)` are swapped — **regardless
  of `enabled` state**. Rejecting only enabled links would let an
  operator pause a B→A link, create A→B, then re-enable B→A and have
  a hidden loop. Reject blanket; document via comment.
  ```java
  linkRepo.findFirstBySourceDirectoryIdAndTargetDirectoryId(
          req.targetDirectoryId(), req.sourceDirectoryId())
      .ifPresent(existing -> {
          throw new IllegalArgumentException(
                  "Would create reverse of existing link " + existing.getId()
                + " — bidirectional configurations require origin-stamped "
                + "events, not in v1. Disable or delete the reverse link first.");
      });
  ```
  Service test + controller-layer 400 test.

- [ ] **Shared `BackoffPolicy` type** in
  `core/src/main/java/com/ldapportal/core/util/BackoffPolicy.java`.
  Jitter is **excluded** — it's a delivery concern (when to actually
  fire), not a backoff-schedule concern (what the nominal delay is).
  The outbox subsystem's `withJitter(base)` stays at the call site;
  replication's no-jitter behaviour preserved by simply not wrapping.
  ```java
  public record BackoffPolicy(List<Duration> ladder) {
      public int maxAttempts() { return ladder.size(); }
      public Optional<Duration> delayForAttempt(int attemptsAfterFailure) {
          if (attemptsAfterFailure > ladder.size()) return Optional.empty();
          return Optional.of(ladder.get(attemptsAfterFailure - 1));
      }
  }

  public final class BackoffPolicies {
      public static final BackoffPolicy OUTBOUND_EVENTS = new BackoffPolicy(List.of(
              Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
              Duration.ofHours(1),   Duration.ofHours(6)));
      public static final BackoffPolicy REPLICATION_EVENTS = new BackoffPolicy(List.of(
              Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
              Duration.ofHours(1),    Duration.ofHours(6)));
      private BackoffPolicies() {}
  }
  ```
  Refactor `ReplicationBackoffPolicy.computeOutcome` and
  `OutboundDispatcherScheduler.resolveRow` to consume their respective
  policy via the shared type. **Behaviour byte-identical**; the two
  subsystems share the *type* even though they tune to different
  ladders. A future "harmonize the ladders" PR is a one-constant
  edit.

- [ ] **`JpaBoundaryArchitectureTest`** ArchUnit rule under
  `core/src/test/java/com/ldapportal/architecture/`. Use the coarse
  package-allowlist form (sharper ArchUnit reflection predicates are
  brittle):
  ```java
  noClasses().that().resideInAPackage("..ldap.replication..")
      .and().resideOutsideOfPackages(
              "..ldap.replication",                   // ReplicationReadOps lives here
              "..ldap.replication.snapshot..",       // Snapshot factories
              "..ldap.replication.persistence..")    // ReplicationEventPersister
      .should().dependOnClassesThat()
      .haveFullyQualifiedName("com.ldapportal.entity.ReplicationLink")
      .orShould().dependOnClassesThat()
      .haveFullyQualifiedName("com.ldapportal.entity.ReplicationEvent")
      .because("JPA entities must not cross out of the snapshot/persistence "
             + "boundary. Use a ReplicationLinkSnapshot/ReplicationEventSnapshot "
             + "obtained from ReplicationReadOps inside a @Transactional method.");
  ```
  Apply the symmetric rule to `core.events` (no class outside
  `..events`, `..events.snapshot..`, `..events.entity..` references
  `OutboxEntry` or `EventSubscription` entities). Verify both rules
  pass against the current `feat/directory-sync` baseline. If either
  surfaces a real bypass, file a follow-up issue and exempt narrowly
  with a tracking comment.

**Acceptance:** Reactor build green. Bidirectional creates return 400
regardless of either link's enabled state. ArchUnit JPA-boundary rules
pass. `BackoffPolicy` type shared by both subsystems with no behaviour
change.

**Upgrade-path verification (cross-cutting):** Apply the new ArchUnit
rules to a clean checkout of `feat/directory-sync`; both pass. No
schema changes, no data verification needed.

---

## Phase R1b — `@LdapWriteAuthorized` chokepoint markers

**Branch:** `feat/replication-r1b-write-chokepoints`

Annotation-based ArchUnit guard for the LDAP write surface. Cleaner
than a package allowlist as the codebase grows.

### Tasks

- [ ] **`@LdapWriteAuthorized` marker annotation** in
  `core/src/main/java/com/ldapportal/ldap/annotation/`. Class-level
  or method-level. Documents intent that this site is one of the
  approved chokepoints for issuing mutating LDAP calls.

- [ ] **Apply the annotation** to the existing chokepoints:
  - `com.ldapportal.core.provisioning.PlanExecutor` (class-level)
  - `com.ldapportal.ldap.replication.ReplicationDelivery` (class-level
    — uses `withConnectionUnreplicated` for replay)
  - `com.ldapportal.ldap.replication.ReplicatingLdapInterface`'s
    passthrough methods (each `add`/`modify`/`delete`/`modifyDN`
    method-level — the wrapper itself issues the underlying writes)
  - `com.ldapportal.ldap.LdapConnectionFactory` (class-level —
    constructs the wrapper; doesn't issue writes itself, but holds
    the write surface)

- [ ] **`WriteSurfaceCoverageTest` ArchUnit rule**:
  ```java
  classes().that().callMethodWhere(
          target(name("add").or(name("modify"))
                  .or(name("delete")).or(name("modifyDN")))
          .and(target(owner(assignableTo("com.unboundid.ldap.sdk.LDAPInterface")))))
      .should().beAnnotatedWith(LdapWriteAuthorized.class)
      .orShould().beMetaAnnotatedWith(LdapWriteAuthorized.class)
      .because("Direct UnboundID writes must originate from an "
             + "@LdapWriteAuthorized chokepoint so capture (replication, "
             + "audit) is reliable. Tighten further in R6.");
  ```
  Note: this is a **transitional** constraint. R6 will additionally
  require that LdapWriteAuthorized classes are themselves reachable
  only via `PlanExecutor`. For now, the annotation marks the
  inventory; R6 narrows the inventory.

- [ ] **Verify** the rule passes against the current baseline. If a
  caller surfaces, it's either (a) a chokepoint that needs the
  annotation, or (b) a real bypass. The latter is rare but possible —
  document any found, exempt narrowly with a tracking comment.

- [ ] **`docs/architecture/ldap-write-surface.md`** — single-page
  inventory of every `@LdapWriteAuthorized` site with a one-line
  justification each. Updated whenever a new site is added or
  removed. This doc IS the audit trail for the chokepoint set.

**Acceptance:** Every class issuing direct UnboundID mutating calls
either carries `@LdapWriteAuthorized` or has a tracking issue. Rule
passes. Inventory doc reflects the current set.

**Upgrade-path verification:** No runtime changes; the annotation is
metadata only.

---

## Phase R2 — Per-directory toggle + `correlation_id` via `CorrelationContext`

**Branch:** `feat/replication-r2-correlation`

### Tasks

- [ ] **Per-directory `replication_enabled` toggle.** Flyway
  forward-only migration `V10__directory_connection_replication_enabled.sql`:
  `ALTER TABLE directory_connections ADD COLUMN replication_enabled
  BOOLEAN NOT NULL DEFAULT false`. Add field to `DirectoryConnection`
  entity + getter. Short-circuit in
  `LdapConnectionFactory.doWithConnection`: if
  `!dc.isReplicationEnabled()`, skip the wrapper entirely.
  Behaviour: two gates, two different pause semantics:
  - per-directory off → no capture happens; no event rows
    accumulate
  - per-link off → capture happens; that link's dispatch pauses
  - **Community-edition graceful degradation**: in builds without
    the `DIRECTORY_SYNC` entitlement, the toggle stays writable in
    the DB (forward-compat) but is hidden from the UI and is treated
    as `false` by the factory regardless of value. An entitlement
    downgrade from commercial → community → commercial round-trips
    cleanly.
  - UI: add the toggle to the directory edit form, only visible
    when `auth.isDirectorySyncEnabled === true`.

- [ ] **`CorrelationContext` ThreadLocal** in
  `core/src/main/java/com/ldapportal/core/observability/CorrelationContext.java`:
  ```java
  public final class CorrelationContext {
      private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
      public static Optional<UUID> current() { return Optional.ofNullable(CURRENT.get()); }
      public static UUID currentOrGenerate() {
          UUID id = CURRENT.get();
          if (id == null) { id = UUID.randomUUID(); CURRENT.set(id); }
          return id;
      }
      public static <T> T withCorrelation(UUID id, Supplier<T> body) {
          UUID prev = CURRENT.get();
          CURRENT.set(id);
          try { return body.get(); }
          finally { if (prev == null) CURRENT.remove(); else CURRENT.set(prev); }
      }
      // void variant for Runnable
      public static void withCorrelation(UUID id, Runnable body) { /* ... */ }
      private CorrelationContext() {}
  }
  ```
  This handles three real cases that wrapper-only generation can't:
  - **API requests**: set at request entry by a Spring filter
    (`@Component CorrelationFilter`) reading `X-Correlation-Id`
    header if present, else generating; one UUID per top-level
    request.
  - **Scheduled tasks**: HR sync, lifecycle playbooks, retention
    schedulers wrap their work in
    `CorrelationContext.withCorrelation(UUID.randomUUID(), () -> {...})`
    so the whole tick shares one trace ID.
  - **Async dispatchers**: `ReplicationWorker.deliverAndSettle`
    enters its own correlation scope (a new UUID per event), so
    delivery-side audit rows pivot independently of source-side
    audit rows. The original source `correlationId` is preserved on
    the `ReplicationEvent` payload from enqueue time; delivery audit
    detail includes BOTH (`correlationId` for delivery,
    `sourceCorrelationId` for the originating action).

- [ ] **`audit_events.correlation_id`** migration
  `V11__audit_events_correlation_id.sql`:
  ```sql
  ALTER TABLE audit_events ADD COLUMN correlation_id UUID;
  CREATE INDEX audit_events_correlation_id_idx
      ON audit_events (correlation_id)
      WHERE correlation_id IS NOT NULL;
  ```
  The partial index is the kill switch: if it dominates index size on
  a busy install, drop it (`DROP INDEX audit_events_correlation_id_idx`)
  and pivot via sequential scan during forensics. The column itself
  is cheap.

- [ ] **`AuditService.record(...)` reads from `CorrelationContext`
  by default**; explicit `record(..., correlationId)` overload for
  cross-thread cases where the ThreadLocal won't propagate. Most
  callers pass nothing and inherit from context.

- [ ] **Wire correlation through the replication capture path.** At
  the wrapper, read `CorrelationContext.currentOrGenerate()` per
  `add/modify/delete/modifyDN` call (not per `search` — reads don't
  enter the trace). Stamp it on the `CapturedWrite` record (new
  field). Stamp on the `ReplicationEvent` payload at enqueue time.
  Stamp on every audit row emitted from the dispatch path.

- [ ] **Audit-pivot UI**: event-detail drawer in
  `DirectorySyncView.vue` shows the `correlationId` (and the
  delivery-side correlation if different); "see related audit rows"
  link filters audit log by the UUID.

- [ ] **Tests**:
  - `CorrelationFilter` round-trips request `X-Correlation-Id` header
    → ThreadLocal → audit row → response header.
  - Scheduled task entering `withCorrelation` shares the UUID across
    all audit rows it emits.
  - `correlationId` flows from `CapturedWrite` → `ReplicationEvent`
    payload → audit detail → audit DB row.
  - Pivot query by `correlationId` returns both the source-side
    `USER_UPDATE` and the dispatch-side `REPLICATION_EVENT_*` rows.

**Acceptance:** Operator can pivot from a dead-lettered replication
event in the UI to the original audit row via `correlationId`.
Per-directory toggle short-circuits capture without affecting per-link
state. Community-edition build correctly treats the toggle as `false`
even if the DB value is `true`.

**Upgrade-path verification:** Apply V10 + V11 to a canary tenant's DB
snapshot before merge. Verify zero rows changed in
`directory_connections` (the new column defaults `false`). Verify zero
rows changed in `audit_events` (the new column defaults `NULL`).
Verify the partial index estimate from `EXPLAIN` is sensible for the
current row count.

---

## Phase R3 — Code-only module move (migrations stay in core)

**Branch:** `feat/addons-replication-module-move`

**Important correction from rev. 1**: tables stay where they are;
existing migrations stay where they are. Only the Java source code
moves. Flyway's `schema_history` is not disturbed.

### Tasks

- [ ] Create `addons/replication/pom.xml` modeled on
  `addons/isva/pom.xml`. Library-jar packaging,
  `<skip>true</skip>` on repackage, antrun guard, depends on core
  only.

- [ ] Add `<module>addons/replication</module>` to root reactor and
  dependency-management entry in parent POM.

- [ ] **Move source files** (preserve git history with `git mv`):
  - `core/src/main/java/com/ldapportal/ldap/replication/` →
    `addons/replication/src/main/java/com/ldapportal/addons/replication/`
  - `core/src/main/java/com/ldapportal/entity/ReplicationLink.java`,
    `ReplicationLinkAttrMapping.java`, `ReplicationEvent.java` →
    `addons/replication/src/main/java/com/ldapportal/addons/replication/entity/`
  - Repositories, services, DTOs, controllers — same pattern.
  - Update package declarations across all moved files.

- [ ] **DO NOT MOVE Flyway migrations.** `V7__directory_sync.sql`
  and `V8__replication_events_claimed_at.sql` STAY in
  `core/src/main/resources/db/migration/core/`. Reasoning:
  - Flyway tracks migrations by version + checksum in
    `schema_history`. Renaming or relocating a file changes its
    identity; Flyway treats the old entry as "deleted" (validation
    fails) and the new entry as "to apply" (DDL fails — table
    exists).
  - The `core/community` build runs Flyway against core's migration
    directory. The replication tables get created even when the
    addon JAR is absent. **This is acceptable** — empty tables on a
    community install cost nothing.
  - **From R3 onwards**, new migrations for replication go in
    `addons/replication/src/main/resources/db/migration/addons/replication/V1__...sql`,
    `V2__...sql`, etc. Register the location in
    `application.yml`'s `spring.flyway.locations` (conditional on
    addon presence — see ISVA pattern).
  - Document this explicitly in `addons/replication/README.md`:
    "table names predate the convention; existing migrations stay in
    core; new migrations follow `addons_replication_` prefix going
    forward."

- [ ] **Table naming**: existing tables (`replication_events`,
  `replication_links`, `replication_link_attr_mappings`) keep their
  current names. New tables created in this addon (if any) use
  `addons_replication_` prefix per convention. Document the
  inconsistency.

- [ ] **`ReplicationAddonProbe implements AddonProbe`** returning
  `Set.of(Entitlement.DIRECTORY_SYNC)`. `DIRECTORY_SYNC` stays in
  core's `Entitlement` enum (every addon's grant must be declared
  where both core and the addon can see it).

- [ ] **`LdapWriteInterceptor` SPI in core** — **specification deferred
  to a separate PR**. R3 punts on this and instead does the
  minimum-viable decoupling: `LdapConnectionFactory` exposes a
  `setWrapper(Function<LDAPConnection, FullLDAPInterface>)` extension
  point; the addon registers a no-arg `@PostConstruct`-time wrapper
  installation. Single wrapper only. Multi-interceptor ordering,
  failure semantics, and an explicit SPI shape land in a follow-up
  "interceptor SPI" PR before any second consumer ships. **Document
  this as transitional** in the addon's main config class.

- [ ] **Move tests** to `addons/replication/src/test/java/...`,
  preserving package shape. Update ArchUnit rules from R1a/R1b for
  the new package paths (`..addons.replication..` replaces
  `..ldap.replication..`).

- [ ] Update `.github/workflows/ci.yml` surefire-reports upload paths
  to include `addons/replication/target/surefire-reports/`.

- [ ] **Run full reactor**: `./mvnw clean install` from a fresh
  checkout, all four modules green (core, addons-isva,
  addons-replication, community, community-plus-isva, commercial).

**Acceptance:** Full reactor build green with the move. ArchUnit
rules pass with new package paths.
`distribution/community/` JAR grep-clean of
`com.ldapportal.addons.replication.*` (addon code absent). Empty
replication tables present in the community-distribution DB — this is
fine; documented.

**Upgrade-path verification:** Start an existing
`feat/directory-sync` deployment, take a DB snapshot. Upgrade to the
R3 build. Verify (a) no Flyway validation errors, (b) existing
`replication_links` / `replication_events` rows still queryable via
the addon's UI, (c) no rows mutated unexpectedly.

---

## Phase R4 — Distribution rename `community-plus-isva` → `community-plus-addons`

**Branch:** `feat/distribution-community-plus-addons`

Same scope as the original plan's P5 with concrete backward-compat
semantics.

### Tasks

- [ ] Rename module + POM artifactId + finalName + comments. Add
  replication dependency alongside ISVA. Same pattern in
  `distribution/commercial/pom.xml`.

- [ ] Rename Dockerfile path, Fly tomls (`-ci` → `-ca`).

- [ ] Update `.github/workflows/deploy-fly.yml` deploy steps. The
  `components` input parameter gains alias handling:
  ```yaml
  - name: Resolve alias components
    run: |
      case "${{ inputs.components }}" in
        *community-plus-isva*)
          echo "::warning::components=community-plus-isva is deprecated; \
                use community-plus-addons. Aliasing for this release; \
                removed in the next."
          # Rewrite to new name for downstream steps
          ;;
      esac
  ```
  Concretely: **`components=community-plus-isva` continues to deploy
  the new `-ca` apps with a `::warning::` annotation for one release;
  removed in the release after**. Same alias treatment for `-ci`
  component tokens. Document the deprecation window in the PR
  description and in `docs/deployment-fly.md`.

- [ ] Update `docs/deployment-fly.md` — stack table, app-creation
  blocks, secrets-set loop, IPv6 allocation, teardown, cost table,
  distribution row, troubleshooting. Add a one-paragraph
  "Distribution rename" section explaining the alias window.

- [ ] Tolerant pre-flight gating on the `-ca` deploy jobs (matches the
  ISVA P5 follow-up pattern).

- [ ] Update `verify-no-ee-bytecode` antrun assertion message text for
  both bundled addons.

- [ ] **JAR-name backward compatibility decision**: producing both old
  and new finalName JARs for one release is mechanically possible
  (Maven `<id>` per `<execution>`) but doubles the artifact-store
  cost. **Pick: single-JAR rename, document as breaking for any
  external consumer reading the JAR by name.** Likelihood of external
  consumers is low; the cost of the dual-output is real. Reverse if a
  customer surfaces a breakage.

- [ ] Smoke-build locally:
  `./mvnw -pl distribution/community-plus-addons -am package` →
  JAR; `docker build` → image; `/me` reports both
  `directorySyncEnabled: true` and `isvaIntegrationEnabled: true`.

**Acceptance:** Fresh checkout builds the renamed distribution. Fly
workflow deploys under new app names. Old token names continue to
work with a warning for one release.

**Upgrade-path verification:** Run `deploy-fly.yml` against an
existing `-ci` deployment with `components=community-plus-isva`; the
warning fires and the new `-ca` apps are targeted. Then run with
`components=community-plus-addons` directly; clean.

---

## Phase R5 — Operability: retention, docs, audit-detail, Playwright

**Branch:** `feat/replication-operability`

### Tasks

- [ ] **`ReplicationEventRetentionScheduler`** (`@Scheduled` cron,
  default `0 30 2 * * *`):
  ```sql
  -- Floor: keep N days of delivered events per enabled link
  DELETE FROM replication_events
   WHERE status = 'DELIVERED'
     AND delivered_at < now() - INTERVAL ':floor_days days'
     AND link_id IN (SELECT id FROM replication_links WHERE enabled = true);

  -- Cap: hard-delete anything older than the cap regardless of state
  DELETE FROM replication_events
   WHERE enqueued_at < now() - INTERVAL ':cap_days days';
  ```
  Properties:
  ```yaml
  app:
    replication:
      retention-floor-days: 30
      retention-cap-days: 90
      retention-cron: "0 30 2 * * *"
  ```
  The cap deliberately catches `DEAD_LETTERED`/`SKIPPED`/`ACKNOWLEDGED`
  rows — operators who haven't triaged in 90 days aren't going to.

- [ ] **Retention test strategy** (made concrete per the rev. 1
  review): integration test using Testcontainers Postgres with
  ~10k rows fixture exercising the query plan. Avoid the "snapshot
  of production-shaped data" framing — too vague. The fixture must
  include rows in every status × every age bucket; assert the right
  counts get deleted by each query.

- [ ] **Audit-detail contributor** stamps `details.replicationEnabled
  = true` on audit rows from a `replication_enabled` source.
  **`details.replicationOrigin` is deferred** (depends on
  per-replicated-write auditing, which would be an audit-volume
  increase — separate PR with its own spec). Compliance reports
  filter on `replicationEnabled` without schema changes.

- [ ] **`docs/directory-replication.md`** operator-facing doc:
  - Prerequisites (target reachable, schema compatibility, mapping
    rules, per-directory toggle vs per-link `enabled`)
  - How to configure a link with mapping rules
  - DN base substitution with worked example
  - Dead-letter semantics; operator actions (`RETRY`/`SKIP`/
    `ACKNOWLEDGE`) and when to use which
  - Per-link FIFO + head-of-line blocking explanation
  - Auto-create-on-missing semantics + replica-lag retry
  - `correlation_id` end-to-end trace (covers the
    `CorrelationContext` model from R2 — operators set
    `X-Correlation-Id` header for traceable bulk operations)
  - Retention knobs
  - Unidirectional-only constraint
  - **Capture timing limitation**: the wrapper captures
    post-LDAP-commit; a journal-write failure means the source change
    is durable but the replication never happens. Operator surface:
    enqueue-failure log alert (no dashboard widget in v1; defer to
    future work).

- [ ] `docs/edition-boundary.md` — add `addons/replication`
  subsection.

- [ ] `docs/enterprise-roadmap.md` — shipped row.

- [ ] `THIRD-PARTY-LICENSES` — mention replication alongside ISVA.

- [ ] **Playwright `@smoke` spec**
  `frontend/tests/e2e/spec/directory-sync.spec.ts`:
  - GET links on a fresh fixture returns empty array.
  - POST a valid link returns 200.
  - POST reverse direction returns 400 with the bidirectional
    message. **This explicitly verifies the rev. 1 review item that
    the rejection must surface through the controller layer, not
    just the service**.
  - POST self-source-equals-target returns 400.
  - Event retry/skip/ack endpoints round-trip correctly.
  - Audit pivot from event detail → audit log filtered by
    `correlationId` returns ≥1 row (R2 wiring smoke-checked
    end-to-end through the deployed Fly app).

**Acceptance:** Retention scheduler runs on the Fly demo (verify via
Postgres row count over a 24-hour window). Operator can find
replication docs from `docs/edition-boundary.md` in one click.
Playwright `@smoke` passes including the audit-pivot leg.

**Upgrade-path verification:** Run the retention queries against a
canary tenant's DB snapshot; verify the row deletion counts match
expectations. Add a `DRY_RUN=true` flag on the scheduler for the
first month of production rollout.

---

## Phase R6 (optional, demand-driven) — Architectural completion

**Branch:** `feat/plan-mediated-writes-replication`

**Reframed from rev. 1**: this phase changes the *capture chokepoint*
(wrapper → `PlanExecutor` event listener), NOT the *commit timing*.
Post-commit semantics are preserved. The original plan's
`BEFORE_COMMIT` framing was wrong: BEFORE_COMMIT rolls back the
*audit row*, not the LDAP change (LDAP isn't in the XA tx), producing
an **audit gap on a real directory change** — worse for compliance
than the impl's "audit succeeds, replication may not."

R6 still has real value — better chokepoint discipline, observability
for other consumers, ArchUnit guarantee tightened — but the framing is
"improve observability and prevent bypass," not "improve atomicity."

Only land when there's a specific demand: a customer requiring LDIF
journal interop, a second consumer of LDAP-write events (SIEM exporter,
analytics pipeline), or an audit-compliance need that the wrapper's
post-commit log-and-swallow can't satisfy.

### Tasks (sketch — flesh out per the spec at the time)

- [ ] **`PlanExecutor` SPI widening** per the original plan's P0:
  extend `ProvisioningInterceptor` with `planUserModify`,
  `planUserMove`, `planGroupCreate`, `planGroupDelete`,
  `planGroupModify`. Refactor `LdapUserService` / `LdapGroupService`.
  Behind `app.provisioning.legacy-direct-writes` feature flag for
  two-release rollback window. **Tighten the
  `WriteSurfaceCoverageTest` from R1b**: classes annotated
  `@LdapWriteAuthorized` must additionally be reachable only via
  `PlanExecutor` (or be `PlanExecutor` itself).

- [ ] **`LdapStepExecutedEvent`** published from `PlanExecutor`,
  carrying source-side step + `correlationId` (already plumbed in R2)
  + `AuthPrincipal`. Listener is `@TransactionalEventListener(phase =
  AFTER_COMMIT)` — **post-commit**, matching the wrapper's semantics
  and preserving the "source-write durability not coupled to
  replication-queue durability" property from R0.

- [ ] **`ReplicationCaptureBridge`** in `addons/replication/`
  subscribes via `@TransactionalEventListener(AFTER_COMMIT) @Async`,
  reads context from the event, calls into `ReplicationEnqueuer`. The
  wrapper-fed enqueuer keeps working in parallel as a safety net.

- [ ] **Parallel-run divergence counter**: two Micrometer counters
  per directory, tagged with capture source:
  - `replication.capture.events_total{source="wrapper"}`
  - `replication.capture.events_total{source="bridge"}`
  An alert fires when the per-hour delta per directory exceeds
  **zero** (any divergence is a bug — both capture paths see the same
  writes). **Clean parallel-run = zero divergence for two release
  cycles**, measured by the alert never firing. Threshold is exact
  zero because per-event semantics demand it; non-zero indicates one
  path is missing or duplicating a write.

- [ ] **Wrapper retirement** after the clean parallel-run window: rip
  out `ReplicatingLdapInterface`, remove the
  `LdapConnectionFactory.setWrapper` extension point, tighten the
  ArchUnit guard to forbid the wrapper class itself.

- [ ] **Optional LDIF journal**, only if interop is the demand
  driver. Additive: new `addons_replication_journal` table
  alongside the existing `replication_events`; populated by
  `ReplicationCaptureBridge` in parallel with event enqueue. The
  existing JSON-payload path stays — no migration risk. LDIF is for
  export and external replay; events drive dispatch. Retention story
  is independent (journal can age out separately from events).

**Risk**: this is the original plan's P0/P1/P2 collapsed into one
phase, with the capture-path swap as the destructive change. Land
behind feature flags; deprecate the wrapper across two releases via
the parallel-run window.

**Upgrade-path verification (when this lands)**: enable the flag on
canary; verify the divergence counter stays at zero for one full
business cycle (typically two weeks) before promoting to GA.

---

## Cross-cutting requirements

These apply to every phase, not just R6:

- **Upgrade-path verification gate**: every phase's acceptance criteria
  includes a "run this PR's migrations + code against a snapshot of
  the canary tenant's DB; verify zero unexpected row changes" step.
  CI doesn't enforce this directly — it's a human checklist item on
  the PR template, signed off by the deploying engineer.
- **Architectural intent tests** live in
  `core/src/test/java/com/ldapportal/architecture/`:
  `JpaBoundaryArchitectureTest` (R1a), `WriteSurfaceCoverageTest`
  (R1b), and any future ArchUnit additions.
- **`@LdapWriteAuthorized` inventory documentation**: a single-page
  doc enumerating every site bearing the annotation, with a one-line
  justification each. Updated whenever a new site is added or
  removed. Lives at `docs/architecture/ldap-write-surface.md`.

## Cross-phase invariants

- After R1a: `JpaBoundaryArchitectureTest` passes; bidirectional
  creates return 400 regardless of either link's enabled state; both
  subsystems consume the shared `BackoffPolicy` type.
- After R1b: every direct UnboundID mutating call originates from an
  `@LdapWriteAuthorized` site.
- After R2: every `ReplicationEvent` has a non-null `correlationId`
  on its payload; the `CorrelationContext` `X-Correlation-Id` header
  round-trips through API requests; scheduled tasks correctly enter
  correlation scopes.
- After R3: `core/` has zero references to `..addons.replication..`
  (ArchUnit); `addons/replication/` has zero references to `..ee..`;
  every Java file under `addons/replication/` carries SPDX
  Apache-2.0; `distribution/community/` JAR grep-clean of the addon's
  classes; existing tables and migrations remain in core; new
  migrations land in the addon.
- After R4: `distribution/community-plus-addons/` JAR contains both
  `addons.isva.*` and `addons.replication.*`, clean of `ee.*`;
  alias-and-deprecate window active for one release.
- After R5: retention scheduler runs without manual intervention on
  the Fly demo; operator docs link-reachable from
  `docs/edition-boundary.md`; Playwright `@smoke` includes the
  controller-layer bidirectional rejection and audit pivot.
- After R6 (if landed): the wrapper is removed from the codebase;
  every LDAP mutation reaches the directory only via `PlanExecutor`;
  `LdapStepExecutedEvent` is the single observability surface for
  LDAP writes; the divergence counter framework stays in place for
  future capture-path migrations.

## Risk + rollback

- **R1a**: zero behavior change; rollback by revert.
- **R1b**: annotation-only change; ArchUnit failure on rule
  application surfaces as a CI failure, not a runtime failure;
  rollback by revert.
- **R2**: per-directory toggle defaults to `false`. `CorrelationContext`
  ThreadLocal is bounded per thread; no leak risk. `audit_events.correlation_id`
  partial index can be dropped at any time without functional impact.
  Rollback by revert + manual `DROP INDEX` if the column is causing
  load.
- **R3**: large mechanical diff. Risk is missed package-rename
  imports breaking distribution-level builds. Mitigation: smoke-build
  every distribution module after the move; CI gate before merge.
  Tables and migrations stay in core, so DB state is not affected by
  the move. Rollback by revert.
- **R4**: rename touches Fly + CI + Docker simultaneously. Alias
  window mitigates external consumer break. Rollback by reverting
  the workflow if Fly apps haven't been created under new names yet.
- **R5**: net-new code; doesn't touch hot paths. Retention scheduler
  has `DRY_RUN=true` flag for the first month of production.
  Rollback by disabling the `@Scheduled` annotation via property.
- **R6**: the architecturally disruptive phase. Feature flag for
  `PlanExecutor` widening; parallel-run window with zero-divergence
  alert gates wrapper retirement; explicit spec required before this
  branch opens. Rollback by flipping the flag and restoring the
  wrapper (which stays in the codebase during overlap).

## What's deliberately out of scope

- Bidirectional replication.
- Schema-driven attribute transforms (multi-valued joins, password
  format conversion, conditional logic).
- HA dispatch (single-instance assumption holds across both
  subsystems; SKIP LOCKED gives us the primitive when it's needed).
- Shared `ClaimableQueueWorker<T>` base class extracted from both
  subsystems (deferred to a separate unification PR after R5 proves
  both subsystems are stable).
- Per-replicated-write audit rows (gates `details.replicationOrigin`
  field; demand-driven follow-up).
- LDIF journal table (only lands if R6's interop demand driver
  materialises; the current JSON-payload path stays canonical).
