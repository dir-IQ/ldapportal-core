# Directory Operations & Health — design

**Status:** Not started (design proposal, 2026-06-25).

## Summary

A unified, **read-only** *Directory Operations* surface in the superadmin UI that
gives the directory admin (as opposed to the user admin) operational visibility
into a directory connection. Four capabilities behind one tab:

1. **Server health monitor** — live `cn=monitor` stats (connections, work queue, backends, DB cache).
2. **Search-performance diagnostics** — unindexed-search counters + the backend index inventory, with an index-advisor hint.
3. **Certificate inspection** — the directory's TLS server certificate, with expiry warning.
4. **Schema comparison / drift** — diff one directory's schema against another (prod vs DR, primary vs alternate replica).

Primary target is **Oracle Unified Directory / OpenDJ**; capabilities 3 and 4 are
vendor-neutral, capabilities 1 and 2 degrade gracefully to "not supported on this
server" for vendors without a `cn=monitor`/`cn=config` strategy. Everything here
is additive to the existing connect-time root-DSE capability probe
(`LdapCapabilityProbeService` / `DirectoryCapabilities`), the per-user password
policy view (`PasswordPolicyService`), and the operational-reports framework
(`OperationalReportType`).

### Goals

- One coherent, low-friction "is this directory healthy?" surface for the directory admin.
- Read-only and safe: no writes, no remediation, no proprietary SDKs.
- Power **dashboard awareness** (cert expiring, work-queue backlog, schema drift) without anyone opening the page.

### Non-goals

- **Native replication health + conflict detection** and **account-state remediation (bulk unlock/expiry)** are valuable but out of scope here — separate designs.
- No write/remediation actions in this surface (it observes; it doesn't fix).
- Not a replacement for the vendor's own admin console — a focused operational read.

## Where it lives — edition & vendor boundary

- **Core, vendor-agnostic.** All reads are plain UnboundID LDAP (`cn=monitor`,
  `cn=config`, the TLS handshake, the schema subentry) — no proprietary vendor
  SDK — so this belongs in core, not an addon. The addon boundary is for licensed
  vendor *integrations* (e.g. ISVA `secUser` provisioning), not read strategies;
  the existing changelog readers (`DseeChangelogStrategy`, the OpenLDAP/AD variants)
  already live in core as per-vendor strategies, and this follows that precedent.
- **Per-vendor reads behind a strategy SPI.** Capabilities 1–2 dispatch on
  `DirectoryConnection.getDirectoryType()` exactly like `LdapGroupService.getNestedMembers`
  does today: an OUD/OpenDJ implementation ships now; AD/IBM/Entra return an
  "unsupported" envelope until they get their own. Capabilities 3–4 need no
  strategy (TLS and schema are standard).
- **Authz: superadmin only.** Per the project's authz tiers, directory
  *infrastructure* health is genuinely system-wide config, so the controller is
  `@PreAuthorize("hasRole('SUPERADMIN')")` — not a daily-admin verb. It is **not**
  gated behind a commercial entitlement: these are operational, not compliance,
  features (same rationale that keeps `OperationalReportType` in community).

## Architecture

```
DirectoryOperationsController  (superadmin, /api/v1/superadmin/directories/{id}/operations)
        │
        ▼
DirectoryOperationsService     (orchestrator: authz, caching, envelope, degradation)
        ├── ServerHealthProbe        (strategy by DirectoryType; cn=monitor)
        ├── SearchDiagnosticsProbe    (strategy by DirectoryType; cn=monitor + cn=config)
        ├── CertificateInspector      (vendor-neutral; TLS handshake)
        └── SchemaComparator          (vendor-neutral; builds on LdapSchemaService)
```

- **LDAP access** reuses `LdapConnectionFactory.withConnection(...)`; the
  certificate read uses a **fresh, non-pooled** TLS connect (mirroring
  `openUnboundConnection`) so it can read the negotiated peer chain deterministically.
- **Caching.** Each probe result is cached in-memory per `(directoryId, capability)`
  with a short TTL, reusing the hand-rolled TTL + bounded-daemon-executor pattern
  from `ScopeCountService` (these are remote, latency-bound reads — same problem the
  dashboard counts had). A manual refresh busts the entry.
- **Progressive load.** Each card is fetched independently from the frontend (the
  dashboard's two-phase lesson), so one slow or erroring probe never blocks the others.
- **Time parsing.** Reuse `PasswordPolicyService.parseGeneralizedTime` for
  `cn=monitor` / cert timestamps (generalized-time → `OffsetDateTime`); lift it to a
  shared util.

### The probe-result envelope

Every probe returns the same envelope so the UI renders all four cards uniformly
and degrades predictably:

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

## Capability 1 — Server health (`cn=monitor`)

OUD/OpenDJ expose a rich `cn=monitor` subtree. Read a curated subset:

| Signal | Source (OUD/OpenDJ) |
| --- | --- |
| Version, start time, uptime | `cn=monitor` root / `cn=Version,cn=monitor` |
| Current / peak / total connections | `cn=monitor` (`currentConnections`, `maxConnections`, `totalConnections`) |
| Work-queue backlog & rejected | `cn=Work Queue,cn=monitor` (current/average/max backlog, `requestsRejected`) |
| Per-backend entry counts | `cn=<backend> Backend,cn=monitor` (base-DN entry counts) |
| JE DB cache hit ratio / on-disk size | `cn=...JE Database,cn=monitor` |

```java
record ServerHealthDto(
    String version, Duration uptime,
    Connections connections,            // current, peak, total
    WorkQueue workQueue,                // backlog, maxBacklog, rejected
    List<BackendStat> backends,         // baseDn, entryCount
    Double dbCacheHitRatio, Long dbOnDiskBytes) {}
```

- **Degradation:** attribute names vary across OUD 12c / OpenDJ / legacy DSEE; the
  strategy reads a superset and maps what's present (missing → null). No read access
  to `cn=monitor` → `authorized=false`.
- **Awareness:** sustained work-queue backlog or `requestsRejected > 0`, or DB cache
  hit ratio below threshold, emit a dashboard **action/awareness** item (the surface
  the app already uses for `REPLICATION_LAG_HIGH` etc.).

## Capability 2 — Search diagnostics

- **Unindexed searches** are the #1 OUD performance pitfall. Surface the
  unindexed-search counter from `cn=monitor` (count since startup); listing the
  offending filters from the access log is a later increment (count first).
- **Index inventory** from `cn=config`: the `ds-cfg-...` index entries under
  `cn=Index,cn=<backend>,cn=Backends,cn=config` — attribute + index types (equality,
  presence, substring, ordering, approximate). Flag attributes the portal commonly
  filters on (`uid`, `mail`, `cn`, `member`, `objectClass`) that lack an **equality**
  index → an index-advisor hint.

```java
record SearchDiagnosticsDto(
    long unindexedSearchCount,
    List<IndexInfo> indexes,            // attribute, types[]
    List<Advisory> advisories) {}       // attribute, suggestion
```

- **Degradation:** needs `cn=config` / `cn=monitor` read; else `authorized=false`.

## Capability 3 — Certificate inspection (vendor-neutral)

The app already negotiates TLS via `SslHelper`/`SSLUtil`. `CertificateInspector`
opens a **dedicated** TLS connection to the directory and reads the negotiated
peer chain — subject, issuer, SANs, `notBefore`/`notAfter`, serial, signature
algorithm, self-signed flag — and computes `daysToExpiry`.

```java
record CertificateDto(
    SslMode tlsMode,
    CertInfo leaf,                      // subject, issuer, sans[], notBefore, notAfter, daysToExpiry, serial, sigAlg, selfSigned
    List<CertInfo> chain,
    boolean trustedByConfiguredAnchor) {}  // vs the connection's trustedCertificatePem / trustAllCerts
```

- **Vendor-neutral**; `SslMode.NONE` directories → `supported=false` ("no TLS configured").
- **Persisted for awareness.** The probe writes `certExpiresAt` into the directory's
  snapshot (see Persistence) so a scheduled check + dashboard awareness can flag
  "cert expires in 12 days" without an open page — a classic "directory went dark at
  2am" save. Only public certificate data is read; no private material is touched.

## Capability 4 — Schema comparison / drift (vendor-neutral)

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

- **False-positive control.** Normalize names/case/ordering and default to a
  **custom-only** diff — subtract the RFC baseline via `Schema.getDefaultStandardSchema()`
  (the same standard-schema source the schema-browser test already uses) so the
  diff highlights *custom* drift, not vendor-supplied standard definitions.
- **Use cases:** prod vs DR, primary vs alternate replica, post-change verification.
- **Single-directory fingerprint.** A stable hash of the normalized custom schema is
  persisted per directory so a scheduled job can flag "schema changed since last
  snapshot" and paired directories can be flagged "diverged" on the dashboard.
- **Reuse:** the diff view deep-links into the **schema browser** (the just-shipped
  object-class/attribute detail) for any changed element.

## API surface

All under `/api/v1/superadmin/directories/{id}/operations`, superadmin-gated,
returning the `ProbeResult<...>` envelope; hard failures map through
`GlobalExceptionHandler` to ProblemDetail.

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/health` | `ProbeResult<ServerHealthDto>` |
| GET | `/search-diagnostics` | `ProbeResult<SearchDiagnosticsDto>` |
| GET | `/certificate` | `ProbeResult<CertificateDto>` |
| GET | `/schema-diff?against={otherId}` | `ProbeResult<SchemaDiffDto>` |
| POST | `/refresh` | busts the cache for this directory |

## UI

- A new **standalone superadmin view** `DirectoryOperationsView.vue` with an
  **inline directory picker** at the top — the established pattern for
  directory-scoped superadmin tools (Schema Browser `superadmin/directory-schema`,
  Directory Browser, Directory Search, Directory Sync, Integrity Check). A new
  left-nav entry, e.g. `superadmin/directory-operations`; no change to the
  existing directory-management (`DirectoriesManageView`) or edit pages. (The app
  has no tabbed directory-detail page — consolidating the growing set of
  per-directory tools into one is a separate, larger IA effort, explicitly out of
  scope here.)
- One **card per capability**, each loading independently with a skeleton, a
  "last probed <time>" line, and a refresh control. Cards render their envelope
  state: data / *not supported on this server* / *service account lacks access* / error.
- **Certificate** card: subject/issuer/SAN, a prominent days-to-expiry badge
  (green/amber/red), chain expander.
- **Schema** card: a fingerprint + **"Compare with [directory ▾]"** → a diff panel
  (added/removed/changed groups); changed elements are chips that deep-link to the
  schema browser.
- **Dashboard awareness/action items** for cert expiring/expired, work-queue backlog
  high, and schema drift detected — mirroring the existing
  `REPLICATION_LAG_HIGH`/`RECONCILIATION_DRIFT_OPEN` awareness types in
  `UnifiedDashboardService`.

## Caching, refresh & scheduled probes

- In-memory short-TTL cache per `(directory, capability)` (the `ScopeCountService`
  pattern); manual refresh busts it; live cards always probe on demand within TTL.
- A scheduled `DirectoryOperationsProbeJob` (reusing the scheduled-report scheduler
  infra, `ScheduledReportJobScheduler`/`...Service`) periodically refreshes the
  **awareness-worthy** signals — cert expiry, schema fingerprint, key health
  counters — and persists a small snapshot so dashboard awareness works without an
  open page.

## Persistence

Minimal. A new `directory_operations_snapshot` JSONB (Flyway core migration,
`db/migration/core`) — or an extension of the existing `capabilities` column —
holding `{ certExpiresAt, schemaFingerprint, keyHealthCounters, lastProbedAt }`.
Live health/diagnostics are **not** persisted (ephemeral, cached only); only the
signals that feed scheduled awareness are stored.

## Security & degradation

- Superadmin only; reads use the configured service bind. `cn=monitor` / `cn=config`
  need privileged read, so a limited bind yields an `authorized=false` card rather
  than an error — the design assumes this is common and degrades per-card.
- No secrets exposed; certificate inspection reads only public certificate fields.

## Audit

Read-only — no audit on view. The scheduled probe and a manual refresh may emit a
light `AuditService` detail. Awareness **state transitions** (cert healthy →
expiring, schema converged → diverged) are good candidates for an audit trail —
flagged as a TBD, reusing existing `AuditAction` values with a `detail` discriminator.

## Testing

- **Unit.** `ServerHealthProbe` / `SearchDiagnosticsProbe` against an UnboundID
  `InMemoryDirectoryServer` seeded with representative `cn=monitor` / `cn=config`
  entries (or a mocked `FullLDAPInterface` returning fixture `SearchResultEntry`s).
  `CertificateInspector` against a generated self-signed cert. `SchemaComparator`
  against `Schema.getDefaultStandardSchema()` ± synthetic custom definitions — the
  same approach `LdapSchemaServiceDetailTest` already uses.
- **Controller (MockMvc):** superadmin authz, envelope shape, supported/authorized
  degradation mapping.
- Community build passes with no addon on the classpath.

## Vendor support matrix

| | Health (`cn=monitor`) | Search diagnostics | Certificate | Schema diff |
| --- | --- | --- | --- | --- |
| **OUD / OpenDJ** | ✅ | ✅ | ✅ | ✅ |
| Active Directory | ⛔ (future) | ⛔ (future) | ✅ | ✅ |
| IBM ITDS | ⛔ (cn=monitor variant, future) | ⛔ | ✅ | ✅ |
| Entra ID | n/a (Graph) | n/a | n/a | n/a |

## Phasing

Each phase is independently shippable behind the same Operations view + envelope.

1. **Certificate** — vendor-neutral, cheapest, highest "save" value; plus the Operations view shell (standalone + directory picker) and `ProbeResult` envelope.
2. **Schema comparison** — builds straight on the schema browser.
3. **Server health** (`cn=monitor`, OUD strategy).
4. **Search diagnostics** + index advisor.

## Open questions / risks

- **`cn=monitor` attribute-name variance** across OUD 12c / OpenDJ / DSEE versions — needs a small mapping table; target OUD 12c / OpenDJ first.
- **Privileged-bind availability** — many deployments bind with a limited app account; the per-card `authorized=false` degradation is load-bearing, not an edge case.
- **Schema-diff false positives** from server-normalized standard schema — default to the custom-only diff.
- **Certificate read** — use a fresh, non-pooled TLS connect (not a pooled connection) to read the peer chain deterministically, including for `STARTTLS`.
- **Entitlement** — recommend shipping in community/core behind superadmin; revisit only if a commercial "advanced directory operations" bundle is desired.
