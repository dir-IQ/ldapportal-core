# Sync engine — implementation plan

- **Date:** 2026-06-06
- **Status:** Not started (implementation plan for
  `docs/plans/2026-06-06-directory-sync-membership-engine-design.md`, 2026-06-06).

Implements the membership-engine architecture (the design doc). This plan
covers the locked decisions, the migration approach, how the legacy replication
subsystem is removed in place, the phased delivery, and the test/risk strategy.

## Scope & assumptions

- The membership/sync engine **replaces** the legacy app-intercept + changelog +
  reconcile replication subsystem. There is no coexistence flag and no parity
  gate — the legacy *feature was never deployed to a customer*, so its tables
  carry no meaningful data and its code/tests are deleted outright.
- **Existing databases must be preserved** (dev/staging hold real data for other
  features). So the schema change is a **forward migration**, not a Flyway
  rebaseline — rebaselining a populated DB would break recorded history and risk
  unrelated data.
- **No in-flight PRs**, so the next migration version and the package rename land
  without renumber/collision coordination.

## Locked decisions (Phase 0)

1. **Identity = source's stable server id, normalized**, via a per-`DirectoryType`
   strategy: `entryUUID` (OpenLDAP/389/OUD/OpenDJ — operational, read explicitly,
   lowercase-UUID normalize), `objectGUID` (AD — binary, canonical-string
   normalize; never `objectSID`), Graph `id` (Entra). Config-time validation:
   bind can read it + present/unique per scope; missing → quarantine. The
   normalized value is stored in `membership.identity` and written to the target
   as the namespaced `sourceAnchor`.
2. **In-stream closure from the start.** On any membership transition, re-enqueue
   referrers found by a **source reverse-query** (`(member=<dn>)` etc.) over a
   declared/defaulted `referenceAttributes` set. **Hash-gated termination:** a
   recompute that yields an unchanged `content_hash` emits nothing and triggers
   no further closure, so cascades terminate when projected outputs stabilize.
   This pulls **DN-reference remapping into the core** (closure is only useful
   with it) — the design doc's old "Phase 4" merges into Phases 1–2.
3. **Brownfield match = `sourceAnchor`** (namespaced per link), conservative —
   ambiguity (multiple candidates, or one target matching multiple identities)
   → REVIEW quarantine, never auto-overwrite. Deterministic-DN fallback only
   when the target schema can't hold the anchor.
4. **Rename `replication_* → sync_*`** across packages, tables, DTOs,
   controllers, routes, and audit-action strings — **except** identifiers
   serialized into signed licenses: keep the `Entitlement.DIRECTORY_SYNC` enum
   *value* unchanged (already "sync"-named; renaming it would break license JWT
   validation, same rule as the ISVA identifiers). Audit-action enum renames are
   fine for forward behavior; **verify** whether `audit_action` is a
   Postgres enum/constraint and whether any `REPLICATION_*` rows exist in
   preserved DBs — if so, keep the old values or data-migrate historical rows.
5. **Migration = forward drop-and-create** at the next free version (`V23`): drop
   the legacy `replication_*` schema (empty — feature undeployed), create
   `sync_links` / `sync_set` / `membership` / `recompute_request`. Existing DBs
   apply it on top of `V1..V22`; their other data is untouched. A fresh build
   create-then-drops the legacy replication tables (cosmetic; unavoidable without
   editing history, which would break populated DBs).

## The Hibernate-`validate` coupling → Phase 0 shape

`spring.jpa.ddl-auto: validate` means the schema and the JPA entities must agree
at boot. The moment `V23` removes `replication_events` /
`replication_link_attr_mappings` / `exclude_filter`, the legacy replication
entities fail validation and the app won't start. Therefore Phase 0 **must** land
as one coherent change:

- the `V23` forward migration, **and**
- deletion of the legacy replication Java that maps the old schema
  (`ReplicationScopeFilter`, `ReplicationEnqueuer`, `ReplicationDelivery`, the
  event entity/repo, the attr-mapping entity, their tests), **and**
- new `sync_*` entities/repos (membership, sync_set, sync_link) as plain JPA
  mappings (no engine logic yet) so `validate` passes and the app boots.

Phase 0 leaves a booting app on the clean schema with no legacy cruft; Phase 1
brings the engine to life on top.

## Reuse / delete / new

**Reuse (operational scaffolding, orthogonal to selection/apply):**
`LdapConnectionFactory` (incl. `withConnectionUnreplicated`), the `@Scheduled`
worker/poller skeleton + HA poll-lease pattern, `AttributeMapper`, the changelog
`Strategy` SPI (search + cursor parts), the reconcile scheduler, entitlement
gating, audit, and the changelog health/gap/cursor-reset/remediation machinery.
Extract `ReplicationDelivery.interpret()` into a shared `LdapResultInterpreter`.

**Delete:** `ReplicationScopeFilter`, the event-building `ReplicationEnqueuer`,
the op-specific `ReplicationDelivery`, the `replication_events` worker + entity,
the attr-mapping entity, and their tests.

**New (`com.ldapportal.sync` / `…ldap.sync`):** `MembershipEntity` + repo,
`SyncSet` + repo, `RecomputeRequest` (set-queue) + repo, `MembershipFunction`,
`RecomputeEngine` (the `process()` diff/apply), `RecomputeWorker`,
`MembershipReconciler`, the per-`DirectoryType` `IdentityStrategy` SPI, and the
closure resolver.

## Target schema sketch

```sql
-- one row per identity per sync set (the materialized membership / join table)
CREATE TABLE membership (
  sync_set_id UUID NOT NULL, identity TEXT NOT NULL,
  source_dn TEXT NOT NULL, target_dn TEXT NOT NULL,
  content_hash BYTEA NOT NULL, state TEXT NOT NULL, fail_reason TEXT,
  last_src_cursor BIGINT, last_scan_epoch BIGINT, version BIGINT NOT NULL,
  PRIMARY KEY (sync_set_id, identity)
);
CREATE INDEX idx_membership_srcdn ON membership(sync_set_id, source_dn);
CREATE INDEX idx_membership_state ON membership(sync_set_id, state);
CREATE INDEX idx_membership_epoch ON membership(sync_set_id, last_scan_epoch);

-- coalescing trigger queue: PK gives free dedup; upsert keeps max cursor
CREATE TABLE recompute_request (
  sync_set_id UUID NOT NULL, key TEXT NOT NULL,  -- identity or source DN
  src_cursor BIGINT, claimed_at TIMESTAMPTZ, enqueued_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (sync_set_id, key)
);

-- sync_set: selection + projection config (Phase 2 fills the rich columns)
-- sync_links: source/target dirs + capture mode + health (reshaped replication_links)
```

## Phases

### Phase 0 — clean baseline (one coherent PR)
`V23` forward migration; `replication_*→sync_*` rename; delete legacy
replication Java + tests; add `sync_*` entities/repos (mappings only);
`IdentityStrategy` SPI skeleton. Acceptance: `flyway migrate` on a populated DB
+ a fresh DB both green; app boots under `validate`; tree compiles with no
legacy references.

### Phase 1 — engine core + app-intercept cutover + closure (the heart)
`MembershipFunction` (identity via the SPI; placement = DN rewrite; transform =
`AttributeMapper` + `sourceAnchor` + **DN-reference remapping** via the
membership `source_dn` index); `RecomputeEngine.process()` (read→membership→diff
→apply, per-identity row lock, token-bucket backpressure, idempotent ops via
`LdapResultInterpreter`); `RecomputeWorker`; `MembershipReconciler` (enumerate +
not-seen sweep, gated behind a complete scan); **closure resolver** (reverse-query
on `referenceAttributes`, hash-terminated); rewire the app-intercept path to emit
`recompute(dn)` (passing ADD post-images to skip the read). Tests on UnboundID
`InMemoryDirectoryServer`: the full diff matrix, delete-without-attrs,
convergence under duplicate/out-of-order triggers, per-identity fault isolation,
closure cascade + termination, reference remapping.

### Phase 2 — rich selection + identity + anchor/brownfield + UI
`SyncSet` config (applicability expression, `identityKey`, placement template,
transform rules, `referenceAttributes`, deletePolicy, reconcileCadence) +
DTO/validation/controller; config-time identity validation; brownfield adoption
by `sourceAnchor` with REVIEW quarantine; `DirectorySyncView` SyncSet editor +
the membership **inventory view** ("where is user X, last synced when").

### Phase 3 — changelog adapter → recompute + changestamp reconcile
Repurpose the `Strategy` SPI to emit `(changeNumber, targetDN)` only; **delete**
the exactly-once/FIFO/LDIF-reconstruction machinery (convergence makes it
unnecessary; no legacy links need it). Keep gap/health/remediation. Changestamp-
driven reconcile: minimal-attr enumeration + deep-read only drifted entries.

### Phase 4 — heterogeneous feeds
Generalize the changelog cursor from integer to opaque token; add DirSync /
syncrepl / Entra-Graph-delta as adapters emitting `recompute(identity)`.

## Testing strategy

UnboundID `InMemoryDirectoryServer` for source + target (no Docker) across the
engine integration tests. Unit tests for `MembershipFunction`, the diff state
machine, the `IdentityStrategy` normalizers (incl. AD binary GUID), closure
termination, and DN-reference remapping. MockMvc for the SyncSet API. Migration
tested on both a populated-DB upgrade and a fresh build.

## Risk gates (do not reorder)

- **Phase 1 before Phase 3.** Dropping exactly-once/FIFO is only safe *because*
  of Phase 1's convergence + idempotency — prove those under fault injection
  before deleting the dedup machinery.
- **Identity key + brownfield (Phase 2)** is the "expensive to change later"
  decision — most validation here; the anchor is written into every target
  entry, so the strategy must be right before any real sync runs.
- **Read-amplification budget.** Closure + re-reads sharpen the source-load risk;
  hold reads-per-transition near 1 at rest via hash-suppression, queue
  coalescing, ADD post-image piggyback, and changestamp reconcile, all behind
  backpressure. Instrument it from Phase 1.

## Open items to confirm during Phase 0

- `audit_action` column type + whether `REPLICATION_*` rows exist in preserved
  DBs (keep old enum values vs data-migrate).
- The default `referenceAttributes` set (`member`, `uniqueMember`, `manager`,
  `owner`, `secDN`, …).
- `sync` package/namespace layout and the `sourceAnchor` attribute name +
  namespacing scheme.
