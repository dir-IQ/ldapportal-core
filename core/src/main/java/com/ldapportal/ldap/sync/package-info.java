// SPDX-License-Identifier: Apache-2.0

/**
 * Directory-to-directory synchronization engine — the "directory sync" feature,
 * gated on the {@code DIRECTORY_SYNC} entitlement.
 *
 * <p>This package keeps one or more <em>target</em> directory subtrees converged
 * to a <em>source</em> directory subtree: as users/groups are created, changed,
 * moved, and deleted in the source, the engine projects those entries onto the
 * target (renaming attributes, rewriting DNs, remapping group membership) and
 * keeps the two in step. It is the backend behind the superadmin "Directory
 * Sync" screens ({@code SyncLinkController} / {@code SyncSetController} and the
 * Vue {@code DirectorySyncView}).
 *
 * <h2>The one idea: convergent recompute</h2>
 *
 * There is exactly <em>one</em> operation in this engine:
 * {@link com.ldapportal.ldap.sync.RecomputeEngine#process(java.util.UUID, String)
 * recompute(syncSet, key)}. To recompute a key it:
 * <ol>
 *   <li>re-reads the <em>current</em> source entry for that key,</li>
 *   <li>computes whether it is a member and what it should look like on the
 *       target (the {@link com.ldapportal.ldap.sync.MembershipFunction}),</li>
 *   <li>diffs that desired state against the {@link com.ldapportal.entity.Membership}
 *       index row, and</li>
 *   <li>applies the single idempotent target operation that closes the gap —
 *       ADD / MODIFY / DELETE / MODDN / no-op.</li>
 * </ol>
 *
 * Because every recompute re-derives the full desired state from live source
 * data, the engine is <b>convergent</b>: it does not matter how many times, in
 * what order, or via which feed a key is triggered — the outcome is always
 * "make the target match the source's current state." This is the property that
 * makes everything else simple. A duplicate trigger is a no-op (the content-hash
 * gate sees no change); an out-of-order trigger still converges; a lost trigger
 * is caught by the next reconcile. There is deliberately <b>no</b> exactly-once
 * delivery, per-link FIFO ordering, or LDIF/delta reconstruction anywhere here —
 * a bare "this DN may have changed" signal is sufficient.
 *
 * <h2>End-to-end flow</h2>
 *
 * <pre>{@code
 *   ┌─ change feeds ──────────────────────────────┐
 *   │  SyncWriteCaptor       (portal write hook)   │
 *   │  SyncChangelogPoller   (source changelog)    │ enqueue(setId, key, cursor)
 *   │  MembershipReconciler  (periodic full scan)  │ ───────────────┐
 *   │  ClosureResolver       (referrer fan-out)    │                │
 *   └──────────────────────────────────────────────┘               v
 *                                                      RecomputeEnqueuer
 *                                              (coalescing queue: recompute_request,
 *                                               composite PK dedups bursts)
 *                                                                   │
 *                                              RecomputeWorker  (scheduled drainer,
 *                                               claim → process → settle lease)
 *                                                                   │
 *                                                                   v
 *                                              RecomputeEngine.process(setId, key)
 *                                                 ├─ read source entry + identity
 *                                                 ├─ MembershipFunction.evaluate → IN/OUT
 *                                                 ├─ diff vs Membership index row
 *                                                 ├─ apply ADD/MODIFY/DELETE/MODDN (idempotent)
 *                                                 ├─ commit index transition
 *                                                 └─ ClosureResolver.fanOut (if target changed)
 * }</pre>
 *
 * <h2>Configuration model</h2>
 *
 * <ul>
 *   <li>{@link com.ldapportal.entity.SyncLink} — a source↔target directory pair,
 *       plus the source's change-capture config (capture mode, changelog format,
 *       cursor token, and operational health columns). One link, 1..n sets.</li>
 *   <li>{@link com.ldapportal.entity.SyncSet} — one selection+projection rule:
 *       which source entries are in scope ({@code objectScope*} +
 *       {@code applicabilityFilter}), how they are placed ({@code targetBaseDn}),
 *       transformed ({@code transformRules}), correlated ({@code identityKey}),
 *       which attributes are DN references ({@code referenceAttributes}), the
 *       {@code sourceAnchorAttribute}, the {@code deletePolicy}, and the reconcile
 *       cadence.</li>
 *   <li>{@link com.ldapportal.entity.SyncTransformRule} — an attribute
 *       rename + {@code ${value}} template, stored as JSON on the set.</li>
 *   <li>{@link com.ldapportal.entity.Membership} — <b>the index</b>: one row per
 *       {@code (syncSetId, identity)} recording the source DN, target DN, content
 *       hash, {@link com.ldapportal.entity.enums.MembershipState state}, and the
 *       last reconcile epoch that saw it. This is the engine's memory and the
 *       reference-remapping translation table; the inventory UI reads it.</li>
 *   <li>{@link com.ldapportal.entity.RecomputeRequest} — one queue row per
 *       {@code (syncSetId, requestKey)}; the composite PK is what coalesces a
 *       burst of triggers for the same key into a single unit of work.</li>
 * </ul>
 *
 * <h2>The four ways a recompute gets triggered</h2>
 *
 * <ol>
 *   <li><b>App intercept</b> — {@link com.ldapportal.ldap.sync.SyncCapturingLdapInterface}
 *       wraps the source LDAP connection; after a portal-initiated write
 *       succeeds it calls {@link com.ldapportal.ldap.sync.SyncWriteCaptor}, which
 *       enqueues the affected DN for every in-scope set. Fast path; never throws
 *       back into the caller's write.</li>
 *   <li><b>Changelog poll</b> — {@link com.ldapportal.ldap.sync.SyncChangelogPoller}
 *       (scheduled) reads the source server's changelog under an HA poll-lease and
 *       enqueues each changed DN, tracking a cursor and lag/gap/cursor-reset
 *       health. Catches out-of-band changes the portal didn't make.</li>
 *   <li><b>Reconcile</b> — {@link com.ldapportal.ldap.sync.SyncReconcileScheduler}
 *       (scheduled) drives {@link com.ldapportal.ldap.sync.MembershipReconciler}
 *       over due sets: a full anti-entropy scan that recomputes every in-scope
 *       identity and then sweeps index rows it didn't see. The consistency floor
 *       under the two stream feeds.</li>
 *   <li><b>Closure</b> — {@link com.ldapportal.ldap.sync.ClosureResolver}, invoked
 *       by the engine after a change, finds source entries that <em>reference</em>
 *       the changed entry (group {@code member}, {@code manager}, …) and enqueues
 *       them, so a group re-projects when a member's target DN appears/moves.</li>
 * </ol>
 *
 * All four converge on {@link com.ldapportal.ldap.sync.RecomputeEnqueuer}, so the
 * queue and engine never need to know which feed produced a request.
 *
 * <h2>The Membership state machine</h2>
 *
 * A {@code (syncSet, identity)} index row moves between
 * {@link com.ldapportal.entity.enums.MembershipState}:
 * <ul>
 *   <li><b>PENDING</b> — transient, used only for an in-flight brownfield
 *       adoption; never the resting state of a healthy row.</li>
 *   <li><b>APPLIED</b> — the target reflects the projected desired state; the
 *       stored {@code contentHash} is what the hash gate compares against.</li>
 *   <li><b>FAILED</b> — the last apply failed; {@code failReason} carries the
 *       server diagnostic. The next trigger or reconcile retries idempotently;
 *       the failure is isolated to this identity and never blocks others.</li>
 *   <li><b>REVIEW</b> — quarantined for an operator decision rather than risking a
 *       destructive or ambiguous write: a scope-exit under
 *       {@code deletePolicy=REVIEW}, an ambiguous brownfield anchor match, or an
 *       unanchored entry already sitting at the placement DN.</li>
 * </ul>
 * An OUT decision with no row, or a converged DELETE, removes the row entirely.
 *
 * <h2>Invariants — the things that must stay true</h2>
 *
 * <ul>
 *   <li><b>Convergence over delivery guarantees.</b> The engine never trusts the
 *       trigger's payload; it re-reads the source. Treat any trigger as "this key
 *       might have changed," nothing more.</li>
 *   <li><b>Idempotency.</b> Reads-then-diffs (never blind replays), and normalizes
 *       result codes ({@link com.ldapportal.ldap.sync.LdapResultInterpreter}:
 *       ADD-exists → MODIFY, DELETE-missing → done) so replays and crash-recovery
 *       are no-ops.</li>
 *   <li><b>Two-step commit.</b> Apply to the target, <em>then</em> commit the index
 *       transition. A crash in between is self-healing: the next recompute
 *       re-derives and re-applies the same idempotent operation.</li>
 *   <li><b>Hash-gated work.</b> {@link com.ldapportal.ldap.sync.SyncContentHash}
 *       over the projected output is the universal "did anything change?" check.
 *       It suppresses redundant target writes <em>and</em> terminates closure
 *       cascades once projected outputs stabilize. It hashes the projection only,
 *       so source edits to un-synced attributes don't churn the target.</li>
 *   <li><b>Mass-delete safety.</b> The reconcile not-seen sweep runs only after a
 *       provably <em>complete</em> source enumeration, and each swept row is
 *       re-read per-identity before any delete — a partial/failed scan can never
 *       cascade into deletions.</li>
 *   <li><b>Per-identity isolation.</b> One identity's apply failure marks only its
 *       row FAILED; the batch continues.</li>
 * </ul>
 *
 * <h2>Concurrency &amp; HA (multiple app instances)</h2>
 *
 * <ul>
 *   <li><b>Changelog poll-lease.</b> {@code SyncLink.claimChangelogPoll} is an
 *       atomic conditional update; only one instance polls a link per cycle, and a
 *       stale lease (crashed poller) is reclaimable after a timeout.</li>
 *   <li><b>Queue claim/settle.</b> {@link com.ldapportal.ldap.sync.RecomputeWorker}
 *       claims a request, processes it, then deletes it <em>only if still
 *       claimed</em>; a re-trigger mid-process nulls the claim so the work repeats
 *       against newer source state (no lost update). A stale-claim sweep reclaims
 *       rows orphaned by a crashed worker.</li>
 *   <li><b>Optimistic index writes.</b> {@code RecomputeEngine} retries a bounded
 *       number of times on {@code OptimisticLockingFailure}/{@code
 *       DataIntegrityViolation} so two instances racing the same identity settle
 *       cleanly.</li>
 *   <li><b>Engine writes are uncaptured.</b> The engine applies to the target via
 *       {@code withConnectionUnreplicated}, so its own writes are <em>not</em>
 *       re-captured into the queue — that would loop. (If a target is itself a
 *       source of another link, that link's own feed handles it.)</li>
 * </ul>
 *
 * <h2>Tuning knobs (application properties)</h2>
 *
 * <ul>
 *   <li>{@code ldapportal.sync.changelog.fixed-delay-ms} (default 15000) — poll cadence.</li>
 *   <li>{@code ldapportal.sync.worker.fixed-delay-ms} (default 10000) — queue drain cadence.</li>
 *   <li>{@code ldapportal.sync.worker.stale-reset-ms} (default 60000) — stale-claim sweep cadence.</li>
 *   <li>{@code ldapportal.sync.reconcile.tick-ms} / {@code initial-delay-ms} — reconcile scheduler.</li>
 *   <li>{@code ldapportal.sync.reconcile.default-cadence-seconds} (default 3600) —
 *       per-set reconcile cadence fallback.</li>
 * </ul>
 *
 * <h2>Extension points</h2>
 *
 * <ul>
 *   <li><b>New source directory type</b> → add a
 *       {@link com.ldapportal.ldap.sync.identity.IdentityStrategy} (declare its
 *       stable-id attribute + value normalization) and register it in
 *       {@link com.ldapportal.ldap.sync.identity.IdentityStrategyRegistry}.</li>
 *   <li><b>New changelog format</b> → add a {@code ChangelogStrategy}
 *       (see {@code com.ldapportal.ldap.changelog}) and teach
 *       {@link com.ldapportal.ldap.sync.SyncChangelogCursor} how to read the
 *       persisted cursor token for that family (numeric changeNumber vs opaque
 *       cookie/delta-link).</li>
 * </ul>
 *
 * <h2>File index — where to look</h2>
 *
 * <p><b>Orchestration:</b>
 * {@link com.ldapportal.ldap.sync.RecomputeEngine} (the heart),
 * {@link com.ldapportal.ldap.sync.RecomputeEnqueuer} (queue writer),
 * {@link com.ldapportal.ldap.sync.RecomputeWorker} (queue drainer).
 *
 * <p><b>Feeds:</b>
 * {@link com.ldapportal.ldap.sync.SyncCapturingLdapInterface} +
 * {@link com.ldapportal.ldap.sync.SyncWriteCaptor} (app intercept),
 * {@link com.ldapportal.ldap.sync.SyncChangelogPoller} (changelog),
 * {@link com.ldapportal.ldap.sync.MembershipReconciler} +
 * {@link com.ldapportal.ldap.sync.SyncReconcileScheduler} (reconcile),
 * {@link com.ldapportal.ldap.sync.ClosureResolver} (closure).
 *
 * <p><b>Pure projection logic</b> (no I/O; the easiest place to start reading):
 * {@link com.ldapportal.ldap.sync.MembershipFunction} →
 * {@link com.ldapportal.ldap.sync.MembershipDecision},
 * {@link com.ldapportal.ldap.sync.SyncPlacement} (DN rewrite),
 * {@link com.ldapportal.ldap.sync.SyncIdentity} (correlation key),
 * {@link com.ldapportal.ldap.sync.SyncContentHash} (change detection),
 * {@link com.ldapportal.ldap.sync.TargetEntryDiffer} (MODIFY mods),
 * {@link com.ldapportal.ldap.sync.SyncReferenceAttributes} +
 * {@link com.ldapportal.ldap.sync.ReferenceResolver} (DN reference remapping),
 * {@link com.ldapportal.ldap.sync.LdapResultInterpreter} (convergence semantics).
 *
 * <p><b>Helpers:</b>
 * {@link com.ldapportal.ldap.sync.SyncDnUtil},
 * {@link com.ldapportal.ldap.sync.SyncScopes},
 * {@link com.ldapportal.ldap.sync.SyncExcludedAttributes},
 * {@link com.ldapportal.ldap.sync.SyncChangelogCursor},
 * {@link com.ldapportal.ldap.sync.identity.IdentityStrategy} (+ per-type impls).
 *
 * <p><b>Config/CRUD &amp; tests:</b> {@code com.ldapportal.service.SyncConfigService},
 * {@code com.ldapportal.controller.superadmin.Sync*Controller}, and the
 * {@code com.ldapportal.ldap.sync.*Test} suite — {@code SyncEngineIntegrationTest}
 * is the executable spec for the whole apply/converge path.
 *
 * <p><b>Note — Entra (Microsoft Graph) sync</b> is a separate, simpler subsystem
 * under {@code com.ldapportal.entra} (its own scheduler/service/state); it does
 * not run through this convergent engine.
 */
package com.ldapportal.ldap.sync;
