# Directory sync — membership-engine architecture

- **Date:** 2026-06-06
- **Status:** Not started (proposal for discussion, 2026-06-06).

## Summary

A greenfield architecture for source→target directory synchronization that
models replication as **maintaining a materialized view**: the synced set is
defined by a single rich predicate per object type, and a per-identity
**membership index** (a CDC-style state store) drives every target operation
from *membership transitions* rather than from interpreting raw change records.

The core move: **stop deciding "is this entry in scope?" at the moment of the
change** (when the entry may be deleted or only partially known) **and instead
remember the prior decision.** Every trigger re-reads the current source state,
recomputes membership, and diffs against the index. ADD / MODIFY / DELETE /
MODDN / scope-enter / scope-exit / attribute-exit all collapse into one diff and
become exact and timely.

This subsumes the two weaker designs we considered (a flat list of
subtree-scoped exclude rules; a two-tier "DN scope + reconcile-authoritative
content filter") and unlocks capabilities neither can reach: exact in-stream
attribute-exit, identity decoupled from DN (heterogeneous directories,
restructuring), DN-reference remapping, referential closure, churn suppression,
per-identity (not per-link) fault isolation, and a queryable sync inventory.

It is, frankly, a **synchronization engine**, not a subtree mirror. That is the
right foundation iff directory *synchronization* (not just structural
replication) is a strategic surface — see "When not to build this."

## Why not the obvious approaches

Every predicate-at-change-time design (exclude filters, DN allow/deny tiers)
shares one root flaw: it evaluates selection against the change record, exactly
when the entry may be unavailable.

- **DELETE** carries no attributes → an attribute predicate can't be evaluated →
  the system either "always propagates deletes" (a hack) or guesses.
- **MODIFY** carries only a delta → a predicate over *untouched* attributes
  forces a source re-read or silently mis-evaluates.
- **Operational / computed attributes** can't be evaluated offline at all.
- **MODIFY_DN across a scope boundary** is genuinely ambiguous.

These designs *manage* the flaw (push it to reconciliation, special-case
deletes) but never remove it, and they require a fragile global invariant —
"all capture paths must evaluate selection identically or the target flaps."

Remembering the prior membership decision removes the root cause: you always
evaluate the predicate against a *known* image (or a known-absent state), so the
predicate can be arbitrarily rich and every operation is exact.

## Core architecture

Replication = a maintained materialized view. The only operation in the system
is **`recompute(syncSet, identity)`**. Change feeds and reconciliation both
reduce to a stream of recompute requests; one engine consumes them.

```
                 ┌───────────── recompute(syncSet, identity|dn) ─────────────┐
   app-intercept ┤                                                           │
   changelog     ┤──► recompute queue ──► ENGINE.process() ──► target op     │
   sync protocol ┤        (a set,          read source                       │
   reconcile     ┘     not a log)          membership()                      │
                                            diff vs membership index         │
                                            apply + commit index transition  │
```

## Model

- **Link** — one directional source→target sync (source dir, target dir,
  enabled). Unchanged from today.
- **SyncSet** — the unit of selection + projection, 1..n per link. Replaces
  "a link with an exclude filter." Fields:
  - **`objectScope`** — base DN + LDAP scope. Bounds *enumeration/search* only;
    it is a performance hint, **not** the selector.
  - **`applicability`** — the membership predicate (objectClass + an arbitrary
    expression). Evaluated in-engine against a known entry, so it can be as
    rich as needed (multi-attribute, regex, computed) — not limited to RFC 4515
    or to what a change feed happens to carry.
  - **`identityKey`** — attribute(s) forming the correlation key. Default DN;
    set to a stable server id (`entryUUID` / `objectGUID` / Entra `id`) for
    stability and heterogeneity. See Risk (b).
  - **`placement`** — DN template computing the *target* DN from the source
    entry/identity (generalizes prefix-rewrite to restructuring).
  - **`transform`** — attribute mapping/rename/template + DN-reference
    remapping (see below). Generalizes today's `AttributeMapper`.
  - **`deletePolicy`** (REVIEW | DELETE), **`reconcileCadence`**.

Selection is one rich predicate over a typed object; placement and transform are
projection. No DN-tier/content-tier dualism.

## Membership index (the state store)

One row per in-scope source identity, per SyncSet. This is the "shadow" /
connector-object table — the whole trick.

```sql
CREATE TABLE membership (
  sync_set_id     UUID  NOT NULL,
  identity        TEXT  NOT NULL,   -- normalized correlation-key value
  source_dn       TEXT  NOT NULL,   -- current source DN (changes on rename/move)
  target_dn       TEXT  NOT NULL,   -- where we placed it
  content_hash    BYTEA NOT NULL,   -- hash of the *projected desired* target state
  state           TEXT  NOT NULL,   -- APPLIED | PENDING | FAILED
  fail_reason     TEXT,
  last_src_cursor BIGINT,           -- changeNumber/USN/seq that last touched it
  last_scan_epoch BIGINT,           -- reconcile sweep generation
  version         BIGINT NOT NULL,  -- optimistic lock
  PRIMARY KEY (sync_set_id, identity)
);
CREATE INDEX idx_membership_srcdn  ON membership(sync_set_id, source_dn);
CREATE INDEX idx_membership_state  ON membership(sync_set_id, state);
CREATE INDEX idx_membership_epoch  ON membership(sync_set_id, last_scan_epoch);
```

The index is a **cache of recomputable truth**, not the source of truth. The
source directory is truth; the index is the materialized membership +
identity→target map that makes incremental, exact transitions possible. Lose
it → reconcile rebuilds it.

## Membership function (pure)

```
membership(syncSet, sourceEntryOrAbsent) ->
    OUT
  | IN { identity, targetDn, desiredAttrs, hash }
```

- absent (deleted / not found) → OUT
- present but wrong objectClass / fails applicability → OUT
- else IN: `identity = extract(identityKey)`, `targetDn = placement(entry)`,
  `desiredAttrs = transform(entry)`,
  `hash = H(canonical(targetDn, desiredAttrs))`

Always evaluated against a known image or known-absent. That single property
kills the entire "entry unavailable at DELETE/MODIFY time" problem class.

## The engine — recompute pipeline & state machine

```
process(syncSet, key):
  lock row for (syncSet, identity)          # per-identity serialization
  entry   = readSource(key)                 # by DN, or search by identity if DN moved
  desired = membership(syncSet, entry)      # IN{...} or OUT
  current = index.lookup(syncSet, identity) # may be null
  switch (current, desired):
    (none/OUT, IN)         -> target.ADD(desired);    index.upsert(APPLIED, hash)
    (IN, IN) hash changed  -> target.MODIFY(desired); index.update(hash)
    (IN, IN) hash same      -> noop                    # free churn suppression
    (IN, OUT)              -> target.DELETE(current.target_dn); index.remove()
    (none/OUT, OUT)        -> noop
  # target_dn changed under stable identity -> MODDN (placement moved the entry)
  on target failure: index.mark(FAILED, reason)        # blocks only this identity
```

ADD / MODIFY / DELETE / MOVE / scope-enter / scope-exit / attribute-exit are not
separate code paths — they are outcomes of one diff, each exact and timely.

## Convergence properties (what this simplifies)

Because `process()` re-reads *current* source state and converges (rather than
replaying the delta an event carried):

- **At-least-once is sufficient.** Duplicate triggers recompute the same state →
  no-op. No exactly-once dedup needed.
- **Order doesn't matter for correctness.** An out-of-order trigger still
  converges to current source state. No per-link FIFO / CAS-ordered cursor
  *for correctness* — cursors only bound scan progress and suppress redundant
  work.
- **The unit of ordering/blocking is the identity, not the link.** A poison
  entry FAILs its own identity and retries; it never wedges the whole link's
  queue (a real defect in the current per-link-FIFO design).

This is the CDC / log-compaction insight: maintain *current state*, don't
replay a perfectly-ordered delta log. It deletes a large amount of the existing
exactly-once / FIFO / dedup machinery.

## Change-feed adapters

Every capture mechanism reduces to *"identity/DN X may have changed → enqueue a
recompute."* None needs to parse change content, evaluate exclusion, or
reconstruct LDIF.

| Feed | Adapter emits |
|---|---|
| **App-intercept** (`ReplicatingLdapInterface`) | `recompute(dn)`; may pass the post-image it just wrote to skip the read (ADD) |
| **Changelog poller** (OUD / IBM / OpenLDAP-accesslog) | `recompute(targetDN)` per record, *including* DELETE records (engine reads source → absent → OUT → deletes target via index `target_dn`, needing zero source attrs) |
| **Sync protocols** (future DirSync / syncrepl) | `recompute(identity)` per cookie-batch object; deletes / scope-exit come free in the protocol |
| **Reconcile** | `recompute` per enumerated identity + a sweep for index rows not seen |

This is the decoupling that answers the multi-vendor problem: adding Entra
Graph-delta later is "translate its signal into `recompute(identity)`," nothing
more. The changelog's lossy `changes` blob becomes optional — re-read the
authoritative entry instead of trusting the log to carry full state.

## Reconciliation = anti-entropy over the same function

```
reconcile(syncSet):
  epoch = next()
  for entry in scanSource(objectScope, objectClass):   # bounded enumeration
      process(syncSet, identityOf(entry)); stamp last_scan_epoch = epoch
  for row in index where last_scan_epoch < epoch:       # in index, not in scan
      process(syncSet, row.identity)   # re-read confirms absent -> OUT -> delete
  optionally: deep-verify target_dn content_hash to catch out-of-band target drift
```

Same membership function as the stream, so stream and reconcile cannot disagree
on *selection* — only on latency. Reconcile demotes to a consistency backstop
(missed events, gaps, brownfield seed, target drift), not the selection
authority. Never delete on a stale epoch without confirming source absence (a
partial scan must not mass-delete — gate sweep-deletes behind a complete
enumeration).

## Identity & correlation

- Keying on `identity` (not DN) means rename/move = same identity, new
  `source_dn` → no special MODDN handling; placement decides if `target_dn`
  changes.
- Target structure can differ arbitrarily from source (flatten, re-parent, RDN
  change) — the index *is* the join table from source identity to target DN.
- **Brownfield adoption:** reconcile can match a pre-existing target entry to an
  identity before deciding ADD vs MODIFY, so you adopt rather than duplicate.
  See Risk (b) for the matching rules.
- Pick a **stable** key (`entryUUID` etc.). A mutable key turns ordinary edits
  into destructive delete+recreate. Validate present + unique per scan;
  duplicates → quarantine.

## Placement, transform, and DN-reference remapping

- **placement** — a DN template (`cn=${uid},ou=Users,${targetBase}`); the
  current prefix-rewrite is a special case. Subsumes `DnMapper`, generalizes to
  restructuring.
- **transform** — attribute rename/template/computed → the desired target
  attribute set. The content hash is over this *projected* set, so source-side
  changes to un-synced attributes don't churn the target.
- **DN-reference remapping** — `member` / `manager` / `secDN` etc. are DN-valued.
  The index is exactly the translation table: during transform, rewrite each
  DN-valued attribute by looking up the *referenced* identity's `target_dn`
  (via the `source_dn` index). A group's `member: uid=bob,ou=src` becomes
  `member: cn=bob,ou=people,ou=dst`; an unsynced referent is dropped or held
  (a closure trigger re-emits when it lands). Today's system can only rewrite an
  entry's own DN — this is a major correctness leap for group sync.

## Referential closure (Phase 2, but the model carries it)

Declare dependencies: "when group G changes, recompute its members" or "user U's
projection references groups." On `process(G)`, enqueue recompute of dependent
identities. Closure becomes "more recompute triggers," not a new evaluation
regime — impossible in per-entry-predicate models.

## Failure, idempotency, crash-consistency

- **All target ops idempotent:** ADD-exists → success/converge-to-modify;
  DELETE-missing → success; MODIFY-missing → optional auto-create. (Today's
  `interpret()` already normalizes these result codes — reuse it.)
- **Two-step commit:** apply to target, then commit the index transition. A
  crash between → the next trigger or reconcile re-derives current state and
  re-applies idempotently. No lost/dup writes survive a reconcile.
- **Index corruption/loss → full reconcile rebuilds it** (it's a cache).
- **Per-identity dead-letter:** FAILED after N retries with reason; surfaced;
  never blocks other identities.

## Observability

The index *is* an answer to "what is synced, and is it healthy?" — count,
per-identity state, `last_applied`, drift. An operator can search "where is user
X on the target, and when did it last sync?" — a standing inventory, not just an
event log. Metrics: membership size, transition rates per op, source-head lag,
failed-identity count, reconcile add/remove/repair report.

## Risks & mitigations

### (a) Re-read-per-trigger source load

**Why it exists.** Correctness comes from re-reading current source state and
converging instead of replaying deltas. The price: one change → 1+ source reads;
a reconcile cycle → a read per in-scope entry. On a production auth directory
those reads compete with live binds.

**Amplification shapes:** fan-out across SyncSets; duplicate/at-least-once
triggers; reconcile = reads × entries; closure/reference resolution.

**Mitigations (by leverage):**
1. **Don't read when you hold an authoritative image.** App-intercept ADD has
   the full entry → skip the read; DELETE needs no entry (index has
   `target_dn`). *Catch:* MODIFY's delta is insufficient when applicability
   depends on untouched attributes → still a read (or a full-entry cache).
   Changelog `changes` is the full entry for ADD only.
2. **Coalesce at the queue.** Make the recompute queue a *set*, not a log;
   collapse bursts; `last_src_cursor` drops behind-cursor triggers.
3. **Changestamp-driven reconcile** (the big steady-state win). Enumerate
   minimal attrs (DN/identity + `entryCSN`/`modifyTimestamp`/`uSNChanged`) and
   deep-read only entries whose stamp moved. "Read every entry" → "read DN+stamp
   every entry, full-read the drifted few." Page searches; lower full-sweep
   cadence.
4. **Backpressure.** Token-bucket recompute concurrency and reconcile scan rate
   so the engine can't DoS the source or trip admin (size/time/look-through)
   limits.
5. **Read from a replica** rather than the master.

**Mitigation's own risk:** piggyback introduces a second ("trust the image") code
path — keep it a strict optimization producing the same membership result, and
let reconcile be the periodic verifier. **Instrument** reads-per-change
amplification; if it drifts above ~1 at rest, coalescing/piggyback is leaking.

Bottom line: the read is the cost of convergence and is worth paying, but steady
state must not be O(entries) reads.

### (b) Identity-key choice & brownfield matching

**Why it calcifies.** The index is keyed by identity, and the key stamps the
target (via the anchor). Changing it later is a migration that re-derives every
index row and re-correlates every target entry, with ambiguity windows →
duplication or deletion. Settle it before data moves.

**A good key is:** stable across rename/move/reparent and mutable-attribute
edits (DN, `mail`, `uid`, `employeeNumber` all fail); present on every in-scope
entry; unique in scope; readable by the sync bind. Gold standard: a
server-assigned immutable opaque id — `entryUUID` (OpenLDAP/389/OUD/OpenDJ), AD
`objectGUID`, Entra `id`. (Avoid AD `objectSID` — changes on domain migration.)
A *business* key (employeeNumber/mail) is for explicit cross-system join only,
with documented churn cost.

**The mutable-key trap (concrete).** With key = `uid`, a rename
`uid=alice → uid=aadams` reads as old-identity-gone (→ DELETE target) +
new-identity-appeared (→ ADD target): an ordinary rename becomes a destructive
delete-and-recreate that loses target state and breaks references. With a stable
key it's a MODIFY/MODDN.

**Brownfield matching (the scariest failure mode).** First reconcile against a
populated target must decide per identity whether a corresponding target entry
exists — get it wrong and you duplicate, or overwrite/delete the wrong entry.
Strategies, increasing robustness:
1. **Deterministic placement** — check `placement(source)` exists. Brittle if
   the target was built differently.
2. **Business-key search** — by mail/employeeNumber. Ambiguity risk.
3. **Anchor attribute (recommended)** — write the source identity onto every
   target entry as a namespaced `sourceAnchor`; match by `sourceAnchor=K`. The
   MS MIM pattern. Exact, unambiguous, and decouples "which target entry is
   this" from "where we put it" (so target restructuring stays safe). Cost: one
   target attribute + schema space.

**Conservative-matching rule:** exact + unique only; multiple candidates, or one
target matching multiple identities → **quarantine for REVIEW, never
auto-merge/delete.** Over-eager matching is how a sync engine deletes the wrong
production entries.

**Recommended defaults:** identity = source's stable server id, normalized;
always write a namespaced `sourceAnchor` and correlate by anchor not DN
(fall back to deterministic-DN only when the target schema can't hold it);
validate present+unique at config time; surface the destructive-churn warning
loudly if an operator insists on a mutable key.

The two risks touch: keying/transforming on attributes a feed doesn't carry
forces a read (feeds Risk a); the anchor adds a little to every target write.

## Worked example

Two directories with *different* structure (so identity-vs-DN and reference
remapping matter).

- **Source** `dc=acme,dc=com`: people under `ou=people`, groups under
  `ou=groups`.
- **Target** `dc=corp,dc=example`: people under `ou=Users`, groups under
  `ou=Groups`.
- **SyncSet `people`:** scope `ou=people` sub;
  applicability `(&(objectClass=inetOrgPerson)(employeeType=staff))`;
  identityKey `entryUUID`; placement `cn=${uid},ou=Users,dc=corp,dc=example`;
  transform `uid→sAMAccountName`, copy `cn/sn/mail`, write `sourceAnchor`.
- **SyncSet `groups`:** scope `ou=groups`, `(objectClass=groupOfNames)`;
  identityKey `entryUUID`; placement `cn=${cn},ou=Groups,...`; transform remaps
  each `member` source-DN → target-DN via the people index.

Source start:

```ldif
uid=alice,ou=people  entryUUID:1111 employeeType:staff      uid:alice cn:"Alice Adams" mail:alice@acme.com
uid=bob,ou=people    entryUUID:2222 employeeType:contractor uid:bob   cn:"Bob Barr"
uid=carol,ou=people  entryUUID:3333 employeeType:staff      uid:carol cn:"Carol Cole"
cn=eng,ou=groups     entryUUID:9999 member:[alice, bob, carol]
```

**T0 — initial reconcile (seed).** alice→IN→ADD; bob→OUT (contractor); carol→
IN→ADD. Then eng→IN; remap members via people index: alice→`cn=alice,ou=Users`,
bob→not in index (dropped), carol→`cn=carol,ou=Users` → ADD eng with members
`[alice,carol]`.

```
membership index after T0:
 people 1111 uid=alice,ou=people…  cn=alice,ou=Users…  Ha1 APPLIED
 people 3333 uid=carol,ou=people…  cn=carol,ou=Users…  Hc1 APPLIED
 groups 9999 cn=eng,ou=groups…     cn=eng,ou=Groups…   He1 APPLIED
```

**T1 — Bob promoted to staff (OUT→IN via attribute change).**
`modify bob employeeType→staff` → recompute(bob). Read bob → staff → IN,
identity 2222, index miss → **ADD target `cn=bob,ou=Users`**. (An ADD on the
target driven by a MODIFY at the source — the case the weaker designs couldn't
do without special logic.) eng is now stale (source lists bob; target doesn't)
and didn't change → **closure trigger**: recompute(eng) → re-read → members
`[alice,bob,carol]` → bob resolves → hash `He1→He2` → **MODIFY eng** adds bob.
(Without closure, the next reconcile repairs eng.)

**T2 — Alice renamed (DN change, identity stable).**
`modrdn uid=alice→uid=aadams` → recompute. Read → entryUUID 1111 (unchanged) →
IN. Index lookup *by identity 1111* → found. placement `cn=aadams` ≠ stored
`cn=alice` → **MODDN target `cn=alice→cn=aadams`**; index `source_dn`/`target_dn`
updated. A rename, not a delete+recreate; references survive.

**T3 — mutable-key contrast.** Replay T2 with identityKey = `uid`:
recompute(uid=alice) → old DN gone → OUT → **DELETE `cn=alice`**;
recompute(uid=aadams) → new identity → **ADD `cn=aadams`**. Same rename now
deletes+recreates the target entry and breaks eng's reference. The argument for
a stable key, shown.

**T4 — Carol deleted.** Changelog DELETE → recompute(carol). Read → not found →
OUT. Index 3333 was IN → **DELETE target `cn=carol`** (target_dn from index — no
source attrs needed). Closure → recompute(eng) → carol unresolves → **MODIFY eng**
removes carol.

**T5 — reconcile catches out-of-band target drift.** Someone deletes `cn=bob`
directly on the target. Next reconcile (deep-verify): source bob still IN →
recompute → index APPLIED but target read shows missing → **re-ADD bob**. The
not-seen sweep likewise deletes orphans left by a missed event.

**T6 — brownfield adoption.** Target already had a hand-made `cn=alice,ou=Users`
(no anchor). First reconcile for alice: search `sourceAnchor=1111` → none;
deterministic `cn=alice` exists but unanchored → ambiguous → **quarantine for
REVIEW**. Operator confirms → write `sourceAnchor=1111` + MODIFY to converge;
index records target_dn.

| Step | Capability shown |
|---|---|
| T0 | Selection predicate + identity index + DN-reference remapping (contractor pruned) |
| T1 | Attribute-driven scope **enter** as a normal ADD; **closure** for cross-references |
| T2 | Stable identity ⇒ rename is a target **MODDN**, references survive |
| T3 | Mutable key ⇒ same rename is **destructive delete+recreate** |
| T4 | Exact **DELETE** from a bare delete record (no source attrs) |
| T5 | Reconcile = **anti-entropy** for target drift and missed events |
| T6 | **Brownfield adoption** via anchor, with conservative review on ambiguity |

## Mapping onto the existing code

**Reuse:** `DirectoryConnection`, `LdapConnectionFactory`
(`withConnectionUnreplicated` for source reads + target writes), the HA lease
pattern (`changelog_poll_claimed_at`), the scheduler/worker skeleton,
`AttributeMapper`, the changelog `Strategy` classes (now they only emit "dn
changed @ cursor"), the reconcile scheduler, `interpret()` result-code
normalization.

**Repurpose:** `replication_events` → the recompute queue (payload shrinks to
`{syncSetId, identity|dn, srcCursor}`); the per-link FIFO worker → per-identity
processing (row-locked, parallel across identities); `ReplicationLinkSnapshot` →
`SyncSetSnapshot`.

**Replace:** `ReplicationScopeFilter` (exclusion) → membership function;
`DnMapper`-as-scope + `excludeFilter` → SyncSet `applicability`/`placement`;
`ReplicationEnqueuer` (builds final ops) → emits recompute requests;
`ReplicationDelivery` (op-specific delivery) → the diff/apply engine.

**New:** `membership` table + repository; the recompute engine; the
identity/placement/transform config + DTO/UI.

## Phased plan

1. **Engine core** — `membership` table + `process()` diff/apply + app-intercept
   adapter + a reconcile that emits recomputes. identity = DN, placement =
   prefix-rewrite, transform = existing mapper. (Already beats today on
   deletes/exit/poison-head, no new feed work.)
2. **Rich selection + identity** — applicability expression, configurable
   identityKey, DN-template placement, brownfield join + anchor in reconcile.
3. **Changelog adapter rewrite** to "emit recompute" (drops the exactly-once /
   FIFO / LDIF-reconstruction machinery).
4. **DN-reference remapping + closure triggers.**
5. **Heterogeneous feeds** (DirSync / syncrepl / Entra-delta) as adapters.

## When not to build this

This is a synchronization engine: its complexity is the membership table +
identity/projection config + brownfield matching. If the product is forever
"copy this subtree to that subtree with identical DNs," it is over-built, and
the two-tier design (DN scope + reconcile-authoritative content filter) is the
right stopping point. The membership index earns its complexity the moment you
need exact attribute-exit, heterogeneous identity, DN restructuring, reference
remapping, group closure, new vendor feeds, poison-isolation, or a queryable
inventory — each of which is free or a small addition here, and a rewrite on any
other design. Greenfield is precisely when it is cheapest to adopt.

## Open decisions

- **Closure: timely vs eventual.** In-stream closure triggers (more machinery)
  vs reconcile-only repair of cross-references (simpler, eventually consistent).
- **Anchor vs deterministic-DN matching** as the default (schema availability on
  targets).
- **Identity key default** per directory type, and whether to allow business
  keys at all in v1.
- **Reconcile change-stamp source** per vendor (`entryCSN` / `modifyTimestamp` /
  `uSNChanged`) and the minimal-attr enumeration shape.
- **Read-amplification budget** and where backpressure limits live.
