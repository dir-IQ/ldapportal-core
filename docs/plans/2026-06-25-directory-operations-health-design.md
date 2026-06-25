# Directory Operations & Health — design

**Status:** Not started (design proposal, 2026-06-25).

## Summary

A unified, **read-only** *Directory Operations* surface in the superadmin UI that
gives the directory admin (as opposed to the user admin) operational visibility
into a directory connection. Three capabilities behind one **standalone superadmin
view** (a directory picker + cards):

1. **Server health monitor** — live `cn=monitor` stats (connections, work queue, backends, DB cache).
2. **Search-performance diagnostics** — unindexed-search counters + the backend index inventory, with an index-advisor hint.
3. **Schema comparison / drift** — diff one directory's schema against another (prod vs DR, primary vs alternate replica).

Primary target is **Oracle Unified Directory / OpenDJ**; the **schema comparison**
is vendor-neutral, while **server health** and **search diagnostics** degrade
gracefully to "not supported on this server" for vendors without a
`cn=monitor`/`cn=config` strategy. Everything here is additive to the existing
connect-time root-DSE capability probe (`LdapCapabilityProbeService` /
`DirectoryCapabilities`), the per-user password policy view
(`PasswordPolicyService`), and the operational-reports framework
(`OperationalReportType`).

### Goals

- One coherent, low-friction "is this directory healthy?" surface for the directory admin.
- Read-only and safe: no writes, no remediation, no proprietary SDKs.
- Power **dashboard awareness** (work-queue backlog, schema drift) without anyone opening the page.

### Non-goals

- **Certificate / TLS expiry monitoring**, **native replication health + conflict detection**, and **account-state remediation (bulk unlock/expiry)** are valuable but out of scope here — separate designs.
- No write/remediation actions in this surface (it observes; it doesn't fix).
- Not a replacement for the vendor's own admin console — a focused operational read.

## Where it lives — edition & vendor boundary

- **Core, vendor-agnostic.** All reads are plain UnboundID LDAP (`cn=monitor`,
  `cn=config`, the schema subentry) — no proprietary vendor SDK — so this belongs
  in core, not an addon. The addon boundary is for licensed vendor *integrations*
  (e.g. ISVA `secUser` provisioning), not read strategies; the existing changelog
  readers (`ChangelogStrategy` → `DseeChangelogStrategy` / `AccesslogStrategy` /
  `DirSyncChangelogStrategy`) already live in core as per-vendor strategies, and
  this follows that precedent.
- **Per-vendor reads behind a strategy SPI.** Capabilities 1–2 dispatch on
  `DirectoryConnection.getDirectoryType()` exactly like `LdapGroupService.getNestedMembers`
  does today: an OUD/OpenDJ implementation ships now; AD/IBM/Entra return an
  "unsupported" envelope until they get their own. Capability 3 needs no strategy
  (the LDAP schema is standard).
- **Authz: superadmin only.** Per the project's authz tiers, directory
  *infrastructure* health is genuinely system-wide config, so the controller is
  `@PreAuthorize("hasRole('SUPERADMIN')")` — not a daily-admin verb. This is a
  deliberate divergence from the Schema Browser, which is feature-keyed
  (`@RequiresFeature(FeatureKey.SCHEMA_READ)` + directory access) because it's a
  directory-scoped admin tool; reading `cn=monitor`/`cn=config` is server
  infrastructure, so superadmin is the right tier. It is **not** gated behind a
  commercial entitlement — these are operational, not compliance, features (the
  same rationale that keeps `OperationalReportType` in community).

## Architecture

```
DirectoryOperationsController  (superadmin, /api/v1/superadmin/directories/{id}/operations)
        │
        ▼
DirectoryOperationsService     (orchestrator: authz, caching, envelope, degradation)
        ├── ServerHealthProbe        (strategy by DirectoryType; cn=monitor)
        ├── SearchDiagnosticsProbe    (strategy by DirectoryType; cn=monitor + cn=config)
        └── SchemaComparator          (vendor-neutral; builds on LdapSchemaService)
```

- **LDAP access** reuses `LdapConnectionFactory.withConnection(...)`.
- **Caching.** Each probe result is cached in-memory per `(directoryId, capability)`
  with a short TTL, reusing the hand-rolled TTL + bounded-daemon-executor pattern
  from `ScopeCountService` (these are remote, latency-bound reads — same problem the
  dashboard counts had). A manual refresh busts the entry.
- **Progressive load.** Each card is fetched independently from the frontend (the
  dashboard's two-phase lesson), so one slow or erroring probe never blocks the others.
- **Time parsing.** Reuse `PasswordPolicyService.parseGeneralizedTime` for the
  `cn=monitor` generalized-time fields (e.g. start time → `OffsetDateTime`); lift it
  to a shared util.
- **OpenAPI.** New endpoints/DTOs flow to the frontend through `npm run gen:api`
  (`openapi.d.ts`), like every other typed API.

### The probe-result envelope

Every probe returns the same envelope so the UI renders all cards uniformly and
degrades predictably:

```java
public record ProbeResult<T>(
    boolean supported,     // this vendor exposes this capability at all
    boolean authorized,    // the configured bind has rights to read it
    T data,                // null unless supported && authorized && no error
    String error,          // short operator-facing reason, else null
    OffsetDateTime probedAt
) {}
```

`supported=false` → "Not available on <vendor>"; `authorized=false` → "The
service account can't read this (needs <cn=monitor|cn=config> access)"; `error`
→ a one-line reason. This mirrors the may-be-null philosophy `DirectoryCapabilities`
already uses, so a limited bind account never errors the whole page.

**Envelope vs ProblemDetail.** Probe-level *outcomes* — unsupported, unauthorized,
timeout/error — return **200 with the envelope** (so a degraded card renders
inline). Only request-level failures surface as ProblemDetail through
`GlobalExceptionHandler`: unknown directory id → 404, non-superadmin → 403,
invalid `against` id on the schema diff → 404/400.

## Capability 1 — Server health (`cn=monitor`)

OUD/OpenDJ expose a rich `cn=monitor` subtree. Read a curated subset:

| Signal | Source (OUD/OpenDJ) |
| --- | --- |
| Version, start time, uptime | `cn=monitor` root / `cn=Version,cn=monitor` |
| Current / peak / total connections | `cn=monitor` (`currentConnections`, `maxConnections`, `totalConnections`) |
| Work-queue backlog & rejected | `cn=Work Queue,cn=monitor` (current/average/max backlog, `requestsRejected`) |
| Per-backend entry counts | `cn=<backend> Backend,cn=monitor` (base-DN entry counts) |
| JE DB cache hit ratio / on-disk size | `cn=...JE Database,cn=monitor` |

> The exact attribute names above are illustrative and **must be validated against
> a real OUD 12c / OpenDJ instance** — they vary by version (see Open questions).

```java
record ServerHealthDto(
    String version, Duration uptime,
    Connections connections,            // current, peak, total
    WorkQueue workQueue,                // backlog, maxBacklog, rejected
    List<BackendStat> backends,         // baseDn, entryCount
    Double dbCacheHitRatio, Long dbOnDiskBytes) {}
```

- **Degradation:** the strategy reads a superset and maps what's present (missing →
  null). No read access to `cn=monitor` → `authorized=false`.
- **Awareness:** sustained work-queue backlog or `requestsRejected > 0`, or DB cache
  hit ratio below threshold, contribute a dashboard awareness item (see
  *Dashboard awareness*).

## Capability 2 — Search diagnostics

- **Unindexed searches** are the #1 OUD performance pitfall. Surface the
  unindexed-search count. **Source is an open question:** confirm whether OUD/OpenDJ
  exposes a cumulative counter under `cn=monitor`; if not, this becomes
  access-log–derived and is a later increment. Don't ship a number we can't source
  reliably.
- **Index inventory** from `cn=config`: the index entries under
  `cn=Index,cn=<backend>,cn=Backends,cn=config` — `ds-cfg-attribute` +
  `ds-cfg-index-type` (equality, presence, substring, ordering, approximate). Flag
  attributes the portal commonly filters on (`uid`, `mail`, `cn`, `member`,
  `objectClass`) that lack an **equality** index → an index-advisor hint.

```java
record SearchDiagnosticsDto(
    Long unindexedSearchCount,          // null when not sourceable on this server
    List<IndexInfo> indexes,            // attribute, types[]
    List<Advisory> advisories) {}       // attribute, suggestion
```

- **Degradation:** needs `cn=config` / `cn=monitor` read; else `authorized=false`.
- **Security:** the `cn=config` read is **scoped to the `cn=Index,…,cn=Backends,cn=config`
  index subtree** — never the full config tree.

## Capability 3 — Schema comparison / drift (vendor-neutral)

Builds directly on `LdapSchemaService`, which already fetches the full UnboundID
`Schema`. `SchemaComparator.compare(dirA, dirB)` diffs:

- **Object classes** — added / removed / changed (MUST, MAY, superior differences), keyed by name + OID.
- **Attribute types** — added / removed / changed (syntax, SUP, single-valued, matching rules).

```java
record SchemaDiffDto(
    Ref a, Ref b,                       // {id, name}
    Delta<ObjectClassChange> objectClasses,   // added[], removed[], changed[]
    Delta<AttributeChange> attributeTypes,
    boolean identical) {}
```

- **False-positive control.** Normalize names/case/ordering. A **custom-only**
  fingerprint (subtracting the RFC baseline via `Schema.getDefaultStandardSchema()`
  — the same standard-schema source `LdapSchemaServiceDetailTest` uses) keeps the
  *fingerprint* stable across vendor-patch noise and tames *cross-vendor* compares.
  Note: a **same-vendor** replica/DR diff already cancels identical vendor-default
  schema, so the baseline subtraction matters most for the fingerprint and for
  comparing across different vendors.
- **Performance.** This is the **heaviest** probe — it fetches *two* full schemas
  per call (`LdapSchemaService` deliberately doesn't cache; caching is left to this
  layer), so it leans on the ops cache and the connection's response timeout. AD
  schemas in particular are large.
- **Use cases:** prod vs DR, primary vs alternate replica, post-change verification.
- **Single-directory fingerprint.** A stable hash of the normalized custom schema is
  persisted per directory so a scheduled job can flag "schema changed since last
  snapshot" and paired directories can be flagged "diverged" on the dashboard.
- **Reuse:** the diff view deep-links into the **schema browser** (the shipped
  object-class/attribute detail) for any changed element.

## API surface

All under `/api/v1/superadmin/directories/{id}/operations`, superadmin-gated,
returning the `ProbeResult<...>` envelope (see *Envelope vs ProblemDetail*).

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/health` | `ProbeResult<ServerHealthDto>` |
| GET | `/search-diagnostics` | `ProbeResult<SearchDiagnosticsDto>` |
| GET | `/schema-diff?against={otherId}` | `ProbeResult<SchemaDiffDto>` |
| POST | `/refresh` | busts the cache for this directory |

The schema-diff endpoint resolves and authorizes **both** directories (superadmin
sees all; the `against` id is validated → 404 if unknown).

## UI

- A new **standalone superadmin view** `DirectoryOperationsView.vue` with an
  **inline directory picker** at the top — the established pattern for
  directory-scoped superadmin tools (Schema Browser `superadmin/directory-schema`,
  Directory Browser, Directory Search, Directory Sync, Integrity Check). A new
  left-nav entry, e.g. `superadmin/directory-operations`; no change to the existing
  directory-management (`DirectoriesManageView`) or edit pages. (The app has no
  tabbed directory-detail page — consolidating the growing set of per-directory
  tools into one is a separate, larger IA effort, explicitly out of scope here.)
  Handles the empty state (no directories configured → picker disabled with a hint).
- One **card per capability**, each loading independently with a skeleton, a
  "last probed <time>" line, and a refresh control. Cards render their envelope
  state: data / *not supported on this server* / *service account lacks access* / error.
- **Schema** card: a fingerprint + **"Compare with [directory ▾]"** → a diff panel
  (added/removed/changed groups); changed elements are chips that deep-link to the
  schema browser.
- **Accessibility:** the health/drift indicators and the supported/authorized/error
  states carry **text equivalents, not colour/icon alone**, with `sr-only` labels —
  consistent with the a11y lint gates and the directory status-dot work.

## Dashboard awareness

The signals worth surfacing without an open page (work-queue backlog/rejected,
schema drift) are contributed through the **awareness path that feeds
`ActivityDashboardService.build(...)`** — the same source as the existing
`REPLICATION_LAG_HIGH` / `RECONCILIATION_DRIFT_OPEN` items. The awareness
contributor reads the **persisted snapshot** (below), not a live probe, so the
dashboard stays fast; `UnifiedDashboardService` only filters the items by
role/entitlement, as it does today.

## Caching, refresh & scheduled probes

- In-memory short-TTL cache per `(directory, capability)` (the `ScopeCountService`
  pattern); manual refresh busts it; live cards always probe on demand within TTL.
- A **dedicated `@Scheduled(fixedDelayString=...)` sweeper** (`DirectoryOperationsProbeJob`)
  — the same fixed-delay worker pattern the codebase already uses
  (`ReplicationWorker`, `ReconciliationScheduler`, `EntraSyncScheduler`), **not** the
  report scheduler — periodically refreshes the **awareness-worthy** signals (schema
  fingerprint, key health counters) and persists a small snapshot so dashboard
  awareness works without an open page.

## Persistence

Minimal. A new `directory_operations_snapshot` JSONB (Flyway core migration,
`db/migration/core`) — or an extension of the existing `capabilities` column —
holding `{ schemaFingerprint, keyHealthCounters, lastProbedAt }`. Live
health/diagnostics are **not** persisted (ephemeral, cached only); only the signals
that feed scheduled awareness are stored.

## Security & degradation

- Superadmin only; reads use the configured service bind. `cn=monitor` / `cn=config`
  need privileged read, so a limited bind yields an `authorized=false` card rather
  than an error — the design assumes this is common and degrades per-card.
- The `cn=config` read is scoped to the index subtree only (see Capability 2) — no
  arbitrary config is exposed.

## Audit

Read-only — no audit on view. The scheduled probe and a manual refresh may emit a
light `AuditService` detail. Awareness **state transitions** (schema converged →
diverged) are good candidates for an audit trail — flagged as a TBD, reusing
existing `AuditAction` values with a `detail` discriminator.

## Testing

- **Unit.** `ServerHealthProbe` / `SearchDiagnosticsProbe` against an UnboundID
  `InMemoryDirectoryServer` seeded with representative `cn=monitor` / `cn=config`
  entries (or a mocked `FullLDAPInterface` returning fixture `SearchResultEntry`s).
  `SchemaComparator` against `Schema.getDefaultStandardSchema()` ± synthetic custom
  definitions — the same approach `LdapSchemaServiceDetailTest` already uses.
- **Controller (MockMvc):** superadmin authz (403 for non-superadmin), envelope shape,
  supported/authorized degradation mapping, and request-level → ProblemDetail mapping.
- Community build passes with no addon on the classpath.

## Vendor support matrix

| | Health (`cn=monitor`) | Search diagnostics | Schema diff |
| --- | --- | --- | --- |
| **OUD / OpenDJ** | ✅ | ✅ | ✅ |
| Active Directory | ⛔ (future) | ⛔ (future) | ✅ |
| IBM ITDS | ⛔ (cn=monitor variant, future) | ⛔ | ✅ |
| Entra ID | n/a (Graph) | n/a | n/a (not LDAP schema) |

## Phasing

Each phase is independently shippable behind the same Operations view + envelope.
(Phase order is independent of the capability numbering above.)

1. **Schema comparison** — vendor-neutral, builds straight on the schema browser; ships alongside the standalone Operations view shell (picker + cards) and the `ProbeResult` envelope.
2. **Server health** (`cn=monitor`, OUD strategy) + the persisted snapshot + dashboard awareness.
3. **Search diagnostics** + index advisor.

## Open questions / risks

- **`cn=monitor` attribute-name variance** across OUD 12c / OpenDJ / DSEE versions — the table above is illustrative; needs validation + a small mapping table, targeting OUD 12c / OpenDJ first.
- **Unindexed-search count source** — confirm a reliable `cn=monitor` counter exists; if it's access-log–only, defer the count rather than guess.
- **Privileged-bind availability** — many deployments bind with a limited app account; the per-card `authorized=false` degradation is load-bearing, not an edge case.
- **Schema-diff scale** — two full schema fetches per call; rely on the ops cache + response timeout, and confirm acceptable on large (AD) schemas.
- **Entitlement** — recommend shipping in community/core behind superadmin; revisit only if a commercial "advanced directory operations" bundle is desired.
