<!-- SPDX-License-Identifier: Apache-2.0 -->
# Directory replication (directory sync)

Directory replication asynchronously mirrors **app-initiated** writes from
one directory (the *source*) to one or more others (the *targets*). A change
an admin makes through LDAP Portal against the source — add, modify, delete,
or rename an entry — is captured after it commits and replayed against each
configured target, with optional DN and attribute mapping.

> **Entitlement.** Replication requires the `DIRECTORY_SYNC` entitlement.
> Without it the feature is hidden and capture is inert. It can be granted by
> a signed license **or** a single config property — see
> [Granting the `DIRECTORY_SYNC` entitlement](#granting-the-directory_sync-entitlement).

> **Scope.** Replication captures writes made *through LDAP Portal*. Changes
> made directly against the source directory by other clients are **not**
> seen by the app-intercept path — use the LDAP changelog audit source for
> visibility into out-of-band changes.

---

## Concepts

| Term | Meaning |
|---|---|
| **Replication link** | A source→target pair plus its mapping rules. Unidirectional. |
| **Per-directory toggle** (`replication_enabled`) | Master capture switch on the source directory. **Off → nothing is captured** (no event rows accumulate at all). |
| **Per-link `enabled`** | Pauses *dispatch* for one link. Capture still happens; that link's events queue until re-enabled. |
| **Replication event** | One queued write targeting one link, with a lifecycle status. |

The two switches are deliberately different:

- **Directory off** — capture is skipped entirely; no rows are written. Use to stop all replication from a source.
- **Link off** — capture continues and events accumulate as `PENDING`; only that link's delivery pauses. Use to temporarily hold one target (e.g. during target maintenance) without losing changes.

---

## Prerequisites

Before a link will deliver:

1. **`DIRECTORY_SYNC` entitled** (see [Granting the `DIRECTORY_SYNC` entitlement](#granting-the-directory_sync-entitlement)) and the **source directory's `Replication enabled`** toggle on (Directories → edit → *Replication enabled*).
2. **Target reachable** with credentials that can write the target subtree.
3. **Schema compatibility** — attributes/object classes replayed against the target must be accepted by its schema. Mismatches surface as `FAILED`/`DEAD_LETTERED` events with the target's error in *Last error*.
4. **Mapping rules** defined where source and target differ in DN layout or attribute names (below).

---

## Granting the `DIRECTORY_SYNC` entitlement

Replication is gated by the `DIRECTORY_SYNC` entitlement. The implementing
code is Apache-2.0 and ships in **every** build, but the feature stays off
until the entitlement is present — `directorySyncEnabled` is `false` in
`/me`, the **Directory Sync** nav and the per-directory **Replication
enabled** toggle are hidden, and the capture path is a no-op. There are two
ways to grant it.

### Option A — a signed license (production)

`DIRECTORY_SYNC` comes from a signed license JWT:

- The **BUSINESS** and **ENTERPRISE** edition baselines both include
  `DIRECTORY_SYNC`; or issue any edition with `--addons DIRECTORY_SYNC`
  (see `LicenseIssuer`).
- Point the app at the JWT with `ldapportal.license.path`
  (env `LDAPPORTAL_LICENSE_PATH`).
- The JWT must verify against the build's trust anchor,
  `core/src/main/resources/license/license-public-key.pem`. A license signed
  by a key the build doesn't trust **fails verification and the app refuses
  to start** (`License JWT failed verification: JWT signature does not
  match…`). To self-sign, replace that public-key resource with your own and
  rebuild, then sign with the matching private key.

> **Gotcha.** If `ldapportal.license.path` is set, the file must exist and
> verify, or startup fails loud. In Docker, a bind mount of a *missing* path
> makes the engine create a **directory** there — which then fails to read as
> a file. Don't set a license path unless you have a valid license file.

### Option B — config self-host override (no license)

For self-hosters who build from source and just want to switch the feature on
without standing up a license-signing toolchain:

```properties
# env: LDAPPORTAL_ENTITLEMENTS_GRANT  (CSV)
ldapportal.entitlements.grant=DIRECTORY_SYNC
```

Handled by `ConfiguredEntitlementsProbe`, which grants the listed entitlements
through the same `AddonProbe` SPI a classpath addon uses — so it flows through
the single entitlement chokepoint (`EntitlementService.has(...)`), with no
feature-site changes. Properties of this path:

- **Inert unless set** — no property, no effect.
- **Allow-listed to open-source entitlements** — only `DIRECTORY_SYNC` may be
  granted this way. Commercial/EE entitlements (`GOVERNANCE`, `HR_SYNC`, …)
  are refused and logged, so it cannot unlock the paid edition bundles.
- **Not a signed license** — it logs a loud warning at startup and, unlike a
  license file, does not have to verify against any key.
- **Independent of a license file** — the probe adds its entitlement *on top
  of* whatever base provider is active. So if you also set a (broken) license
  path, the base provider still fails first. Use one mechanism or the other,
  not both.

The local dev stack (`compose.yaml`) defaults
`LDAPPORTAL_ENTITLEMENTS_GRANT=DIRECTORY_SYNC`, so `docker compose up` enables
replication out of the box; set it to empty in your `.env` for the pure
community baseline.

### Verifying it took

```bash
# Option B prints this at startup:
docker compose logs app | grep -i "Granting entitlement"
#   → Granting entitlement(s) [DIRECTORY_SYNC] via ldapportal.entitlements.grant …

# /me reports the flag the UI gates on:
curl -s localhost:9090/api/v1/me | grep -o '"directorySyncEnabled":[a-z]*'
#   → "directorySyncEnabled":true
```

Once granted, the **Directory Sync** menu and the per-directory **Replication
enabled** checkbox appear.

> **Rebuild, don't just restart.** The `app` image bakes a **host-built**
> JAR, so a `docker compose restart` (or `up` without `--build`) reuses the
> old JAR and ignores backend changes. After changing entitlement config or
> any backend code:
> `./mvnw clean package -DskipTests && docker compose up -d --build app`.

---

## Configuring a link

In **Directory Sync** (superadmin) → *New Replication Link*:

- **Source / Target directory** — the two endpoints. A link is one-way; see [Unidirectional only](#unidirectional-only).
- **Source / Target base DN** (optional) — DN substitution (below). Leave blank for identity mapping.
- **Enabled** — dispatch on/off for this link.
- **Auto-create on missing** — see [Auto-create](#auto-create-on-missing).
- **Attribute mappings** — rename and/or value-template per attribute.

### DN base substitution

If source and target hold the entry under different subtrees, set both base DNs.
The source base is stripped and the target base appended:

```
Source base DN:  ou=people,dc=corp,dc=com
Target base DN:  ou=staff,dc=partner,dc=net

Source write:    uid=alice,ou=people,dc=corp,dc=com
Replicated as:   uid=alice,ou=staff,dc=partner,dc=net
```

A write whose DN is **outside** the source base is out of scope for that link and is silently skipped (not an error).

### Attribute mappings

Each rule has a **source attribute**, **target attribute**, and optional **value template**:

- *Rename*: `mail → email` copies values unchanged under the new name.
- *Value template*: `${value}` substitutes the source value, e.g. template `${value}@partner.net` rewrites each value.

Attributes without a rule are replicated unchanged (identity mapping).

---

## Event lifecycle

```
PENDING ──claim──> IN_FLIGHT ──ok──> DELIVERED
   ▲                   │
   │                   └─fail─> FAILED ──(retry budget exhausted)──> DEAD_LETTERED
   └──────────────── retry / backoff ───────────────┘
```

| Status | Meaning |
|---|---|
| `PENDING` | Queued, waiting for the worker. |
| `IN_FLIGHT` | Claimed by the worker; delivery in progress. |
| `DELIVERED` | Applied to the target successfully. |
| `FAILED` | A delivery attempt failed; will retry per the backoff ladder. |
| `DEAD_LETTERED` | Retry budget exhausted; needs operator attention. |
| `SKIPPED` | Operator chose not to apply. |
| `ACKNOWLEDGED` | Operator reviewed a dead letter and dismissed it. |

### Retry / backoff

Failed events retry on a fixed ladder — **30s → 2m → 10m → 1h → 6h** — then
move to `DEAD_LETTERED`. Backoff is per event; the schedule is *when* the
worker will next attempt, not a guarantee of immediate retry.

### Per-link FIFO and head-of-line blocking

Events deliver **in order per link**: the worker always takes the earliest
claimable event for each link. A stuck event (retrying or dead-lettered)
therefore **blocks later events for the same link** until it is resolved
(delivered, skipped, or acknowledged). This preserves causal ordering
(you can't apply a modify before the add that created the entry) at the cost
of head-of-line blocking — so triage dead letters promptly, or `SKIP` them
to unblock the link.

### Crash recovery (stale reset)

If the worker process dies mid-delivery, the event is left `IN_FLIGHT`. A
sweep resets events whose claim is older than the stale threshold back to
`PENDING` so they are retried. (The reset is keyed on *claim* time, not
enqueue time, so a freshly claimed backlog item is never falsely reset.)

---

## Operator actions

On each event (Directory Sync → *View events*):

| Action | Effect | When to use |
|---|---|---|
| **Retry** | Back to `PENDING`, retry budget reset | You fixed the underlying cause (target reachable, schema, credentials) and want it to try again. |
| **Skip** | → `SKIPPED`, never applied | The change isn't appropriate for the target (e.g. an attribute the target rejects) and you want to unblock the link. |
| **Acknowledge** | → `ACKNOWLEDGED` | You've reviewed a dead letter and are dismissing it without retrying. |

Every operator action is itself audit-recorded (`REPLICATION_EVENT_RETRIED_BY_OPERATOR`, `…_SKIPPED_BY_OPERATOR`, `…_ACKNOWLEDGED`).

### Auto-create on missing

When a MODIFY targets an entry that doesn't exist on the target yet (e.g. the
target is behind), enabling **Auto-create on missing** has the worker create
the entry from the source first, then apply the modify. With it off, the
modify fails and retries (giving a lagging replica time to catch up).

---

## Tracing a change end-to-end (`correlation_id`)

Every audit row emitted while handling one top-level operation shares a
**correlation id**. For a replicated write that means the source-side action
(e.g. `USER_UPDATE`) and any dispatch-side rows (e.g.
`REPLICATION_EVENT_DEAD_LETTERED`) carry the same id, so you can pivot from a
failed replication to the change that caused it.

- From a replication event (Directory Sync → *View events*), the **trace**
  link opens the audit log filtered to that event's originating
  correlation id.
- For traceable bulk operations, set the `X-Correlation-Id` request header;
  all resulting audit rows inherit it.

The dead-letter audit row also records `sourceCorrelationId` in its detail —
the originating operation's id — which is what the trace link filters on.

---

## Retention

A nightly sweep (`ReplicationEventRetentionScheduler`) keeps the
`replication_events` table bounded:

| Knob | Env var | Default | Effect |
|---|---|---|---|
| `ldapportal.replication.retention.floor-days` | `REPLICATION_RETENTION_FLOOR_DAYS` | `30` | Delete `DELIVERED` events older than this on **enabled** links. |
| `ldapportal.replication.retention.cap-days` | `REPLICATION_RETENTION_CAP_DAYS` | `90` | Hard-delete **any** event enqueued before this, regardless of status. |
| `ldapportal.replication.retention.cron` | `REPLICATION_RETENTION_CRON` | `0 30 2 * * *` | When the sweep runs. |

The cap deliberately reaps un-triaged `DEAD_LETTERED` / `SKIPPED` /
`ACKNOWLEDGED` rows — the dead-lettering itself is already in the audit log,
so the event row is safe to drop after the cap window. Retention is **not**
entitlement-gated, so it still drains leftover rows after a downgrade.

---

## Limitations

### Unidirectional only

A link is one-way. Bidirectional configurations (an A→B link *and* a B→A
link) are **rejected at creation** — they require origin-stamped events to
avoid infinite replication loops, which is not supported. Disable or delete
the reverse link first.

### Capture is post-commit, fire-and-forget

The source write commits to the directory **first**; capture happens after
and never blocks or rolls back the source change. The trade-off: if enqueue
fails (e.g. the queue DB is briefly unavailable), the source change is
durable but **that change is not replicated**. Such gaps are logged at
`ERROR` in the application log — monitor for "Failed to enqueue replication
event". There is no automatic backfill in v1.

### Out-of-band changes aren't captured

Only writes through LDAP Portal are intercepted. Changes made directly
against the source by other tools bypass replication.
