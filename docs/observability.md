# Observability — Prometheus metrics

**Status:** In progress (Phases 0–2 shipped, 2026-06-25).

LDAPPortal exports operational-health metrics in Prometheus format from the
core backend. This is self-observability — the health of *the portal*, not of
the directories it manages. Phase 0 wires the registry, secures the scrape
endpoint, and ships the LDAP connection-pool meters; Phase 1 adds per-directory
LDAP operation latency and error counts; Phase 2 adds sync-engine and
background-job health (queue depth, changelog lag, outbox backlog, report-job
status); later phases add more subsystem metrics (see [Roadmap](#roadmap)).

## Endpoint

```
GET /actuator/prometheus
```

Served in Prometheus text exposition format by the Micrometer Prometheus
registry (`micrometer-registry-prometheus`). The endpoint is exposed via
`management.endpoints.web.exposure.include: health,info,prometheus` in
`application.yml`.

> The scrape path is `/actuator/prometheus`, **not** a root `/metrics`. Keeping
> it under the actuator base path leaves the existing public `health`/`info`
> probes untouched (moving them would break container probes and e2e checks).

## Security

The scrape endpoint exposes operational internals (pool depth, JVM internals,
per-URI request timings), so it is **superadmin-only** — never public like
`health`/`info`:

```java
// SecurityConfig
.requestMatchers("/actuator/health", "/actuator/info").permitAll()
.requestMatchers("/actuator/**").hasRole("SUPERADMIN")
```

`ActuatorMetricsSecurityTest` locks this in: anonymous → 401, non-superadmin →
403, superadmin → 200 exposition text, and `health` stays public.

### Scraping in production

Pick one of:

1. **Superadmin API token (recommended for shared Prometheus).** Mint a
   superadmin-owned API token (`ldap_pat_*`) and have Prometheus send it as a
   bearer credential:

   ```yaml
   # prometheus.yml
   scrape_configs:
     - job_name: ldap-portal
       metrics_path: /actuator/prometheus
       authorization:
         type: Bearer
         credentials: ldap_pat_xxxxxxxxxxxxxxxxxxxx   # superadmin token
       static_configs:
         - targets: ["ldap-portal:8080"]
   ```

2. **Network-isolated management port / sidecar.** Terminate scraping inside the
   trusted network (e.g. a mesh sidecar or a firewalled management interface) so
   the endpoint is never reachable from outside the cluster. Still keep the
   superadmin role requirement as defence in depth.

Do not widen the `permitAll` set to include `prometheus`. Metric labels and
values carry no PII or secrets (see [Cardinality & privacy](#cardinality--privacy)),
but the surface still reveals deployment internals useful to an attacker.

## What you get

### Custom — LDAP connection pool (`LdapPoolMetrics`)

One series set per configured directory, registered when the directory's
connection pool is first created and removed when the pool is evicted. Values
are read live from the UnboundID `LDAPConnectionPoolStatistics` at scrape time.

| Metric (Prometheus name) | Type | Meaning |
| --- | --- | --- |
| `ldapportal_ldap_pool_connections_available` | gauge | Idle connections currently available for checkout |
| `ldapportal_ldap_pool_connections_max` | gauge | Configured maximum pool size (capacity); pair with `_available` to derive utilization |
| `ldapportal_ldap_pool_checkouts_total{result="success"}` | counter | Successful borrow operations |
| `ldapportal_ldap_pool_checkouts_total{result="failed"}` | counter | **Failed checkouts — pool exhaustion** (primary alert signal) |
| `ldapportal_ldap_pool_connections_closed_defunct_total` | counter | Connections discarded as defunct (broken sockets) |
| `ldapportal_ldap_pool_connection_attempts_total{result="success"}` | counter | Successful new-connection attempts |
| `ldapportal_ldap_pool_connection_attempts_total{result="failed"}` | counter | Failed new-connection attempts (server unreachable / auth) |

Every series is tagged `directory_id` (UUID), `directory` (display name),
`type` (e.g. `ORACLE_UNIFIED_DIRECTORY`), plus the common
`application="ldap-portal"` tag.

A good first alert: `rate(ldapportal_ldap_pool_checkouts_total{result="failed"}[5m]) > 0`
(a directory's pool is exhausted — requests are starving).

### Custom — LDAP operations (`LdapOperationMetrics`)

Every call through the pooled connection surface is timed by
`MeteredLdapInterface` — a `FullLDAPInterface` decorator wrapped *innermost*
(closest to the wire), so the recorded latency is the raw server round-trip even
when the sync-capture wrapper is layered on top. A single `Timer` carries both
the latency distribution and the error breakdown:

| Metric (Prometheus name) | Type | Meaning |
| --- | --- | --- |
| `ldapportal_ldap_operations_seconds_count` | timer | Operation count (per operation + result) |
| `ldapportal_ldap_operations_seconds_sum` | timer | Cumulative latency |
| `ldapportal_ldap_operations_seconds_bucket` | timer | Latency histogram — bounded SLO buckets, 5 ms–10 s |

On top of the directory dimensions (`directory_id` / `directory` / `type`):

- `operation` — `search` / `add` / `modify` / `modify_dn` / `delete` /
  `compare` / `bind` / `extended` (a small fixed set; `getEntry` and
  `searchForEntry` fold into `search`).
- `result` — `success`, or a coarse failure **class**: `not_found` /
  `timeout` / `unavailable` / `limit_exceeded` / `auth` / `invalid` / `other`.
  These are classes, not raw result codes, to keep cardinality bounded. The
  `unavailable` class mirrors `LdapConnectionFactory`'s connection-broken (502)
  path (`ResultCode.isConnectionUsable() == false`), so the metric lines up with
  the user-facing failure.

Useful queries:

- p95 search latency per directory —
  `histogram_quantile(0.95, sum by (le, directory) (rate(ldapportal_ldap_operations_seconds_bucket{operation="search"}[5m])))`
- error rate per directory —
  `sum by (directory) (rate(ldapportal_ldap_operations_seconds_count{result!="success"}[5m]))`

A failed op's latency (e.g. a 30 s `timeout`) is tagged with its own `result`
class, so the `result="success"` latency stays clean for SLOs.

### Custom — sync engine & background jobs (`SyncEngineMetrics`, `JobHealthMetrics`)

Subsystem-health gauges, **global** (not per-directory). These are DB-backed:
rather than query on every scrape, a scheduled `refresh()` tick snapshots the
repository aggregates into in-memory holders and the gauges read those — so
scrape rate is decoupled from DB load, and a DB hiccup degrades to a stale
snapshot, not a failed scrape. The two "age" gauges store the oldest timestamp
and compute age **live**, so lag keeps climbing between refreshes.

| Metric (Prometheus name) | Type | Meaning |
| --- | --- | --- |
| `ldapportal_sync_recompute_pending_requests` | gauge | Unclaimed recompute requests (queue depth) |
| `ldapportal_sync_recompute_inflight_requests` | gauge | Recompute requests currently claimed by a worker |
| `ldapportal_sync_recompute_oldest_age_seconds` | gauge | Age of the oldest unclaimed request (queue lag); 0 when empty |
| `ldapportal_sync_changelog_links{health=...}` | gauge | Enabled changelog-capture links per poll health (`HEALTHY`/`LAGGING`/`STALLED`/`GAP_DETECTED`/`CURSOR_RESET`/`DISABLED_CONFIG_ERROR`) |
| `ldapportal_sync_changelog_lag_max_changes` | gauge | Largest changelog lag (source head − cursor) across links; clamped ≥ 0 |
| `ldapportal_events_outbox_entries{status=pending\|delivering\|dead_lettered}` | gauge | Event-outbox entries by delivery status |
| `ldapportal_events_outbox_oldest_pending_age_seconds` | gauge | Age of the oldest `PENDING` outbox entry (delivery backlog); 0 when none |
| `ldapportal_report_jobs_enabled_jobs` | gauge | Enabled scheduled report jobs |
| `ldapportal_report_jobs_failed_jobs` | gauge | Enabled jobs whose last run failed |

The sync engine is entitlement-gated (`DIRECTORY_SYNC`); where it's inactive the
tables are empty and every sync gauge reports 0 — an accurate "no backlog"
rather than a missing series. The outbox dispatcher and report scheduler run
unconditionally in core, so those gauges are always meaningful.

Good first alerts: `ldapportal_sync_changelog_links{health="STALLED"} > 0`
(a poller is stuck), `ldapportal_events_outbox_entries{status="dead_lettered"} > 0`
(deliveries are being abandoned), `ldapportal_report_jobs_failed_jobs > 0`.

Refresh cadence is `ldapportal.metrics.refresh-ms` (default 15000).

### Custom — deployment inventory (`InventoryMetrics`)

Always-on, **edition-agnostic** gauges describing how big the install is. This is
operational inventory, *not* licensing — it ships in every edition including
community (an operator counting their own resources). Same DB-backed snapshot +
eager-prime pattern as the Phase 2 metrics. (The license/quota overlay that
pairs `usage_limit{resource}` with these counts is Phase 3b; until a license sets
caps, only the counts exist.)

| Metric (Prometheus name) | Type | Meaning |
| --- | --- | --- |
| `ldapportal_inventory_directories` | gauge | Configured directory connections |
| `ldapportal_inventory_admin_accounts{role="admin"\|"superadmin"}` | gauge | Active operator accounts by role |
| `ldapportal_inventory_event_subscribers` | gauge | Enabled event subscriptions (0 where the events module is inactive) |
| `ldapportal_inventory_pending_approvals` | gauge | Approval requests awaiting action |
| `ldapportal_inventory_pending_approval_oldest_age_seconds` | gauge | Age of the oldest pending approval (backlog); 0 when none |

### Free — Spring Boot / Micrometer defaults

The same registry also carries Boot's built-in binders, with no extra code:

- **JVM** — `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_live_threads`, …
- **HTTP server** — `http_server_requests_seconds{uri,method,status,outcome}` (latency + error rate per route)
- **HikariCP** (the app's own Postgres pool) — `hikaricp_connections_*`
- **System / process** — `process_cpu_usage`, `system_load_average_1m`, file descriptors, uptime
- **Logback** — `logback_events_total{level}` (e.g. error-log rate)

### Custom — license & entitlements (`LicenseMetrics`)

The **license overlay** that complements the inventory counts. It stays dormant
in community via the model's own sentinels — *no edition branching*: an unlimited
limit (`Long.MAX_VALUE`) or a never-expiry (`Instant.MAX`) means the corresponding
series is simply not registered.

| Metric (Prometheus name) | Type | Meaning |
| --- | --- | --- |
| `ldapportal_license_entitlement{entitlement="..."}` | gauge 0/1 | Each entitlement granted (1) / withheld (0). In community-plus-isva, `VENDOR_INTEGRATIONS_ISVA=1` confirms the addon is active. |
| `ldapportal_license_info{edition,signed}` | gauge =1 | Install descriptor — edition + whether a signed license is present. |
| `ldapportal_license_expired` | gauge 0/1 | 1 once past the expiry instant; always 0 in community (never expires). |
| `ldapportal_license_expiry_timestamp_seconds` | gauge | Expiry as a Unix timestamp — **only when a real expiry exists**. |
| `ldapportal_usage_limit{resource="..."}` | gauge | Licensed quota per resource — **only finite limits**; pairs with `ldapportal_inventory_*`. |

Alerts: renewal `(ldapportal_license_expiry_timestamp_seconds - time())/86400 < 30`;
lapsed `ldapportal_license_expired == 1`; approaching a quota
`ldapportal_inventory_directories / on() ldapportal_usage_limit{resource="directories"} > 0.9`.

The conditional series (`expiry`, `usage_limit`) register from the license seen at
startup, so a license installed at runtime surfaces them after a restart.
`grace_state` (within-grace vs past-grace) is out of scope — the expiry timestamp
and `expired` flag cover the essential alerts.

### Custom — authentication failures (`AuthMetrics`)

A counter for rejected authentication attempts — the brute-force /
credential-attack signal. Incremented at the auth-rejection sites; the audit log
keeps the per-account detail, while the metric stays bounded (no usernames, IPs,
or tokens as labels).

| Metric (Prometheus name) | Type | Meaning |
| --- | --- | --- |
| `ldapportal_auth_failures_total{reason="...",principal="..."}` | counter | Rejected authentication attempts |

- `reason` — `bad_credentials` (failed admin login) / `invalid_token` (rejected API token).
- `principal` — `admin` / `api_token`.

Alert on a spike: `sum by (principal) (rate(ldapportal_auth_failures_total[5m]))`.
Self-service / OIDC / WebSEAL login and JWT-session rejections aren't instrumented
yet — the same `AuthMetrics.recordFailure(...)` extends to them.

## Cardinality & privacy

Tags are restricted to **bounded, low-cardinality** dimensions: directory id /
display name / type, the operation verb, and a fixed set of result classes. User
identifiers, DNs, entry attributes, filters, and secrets are **never** used as
metric names, labels, or values — note in particular that the operation timer
tags a coarse `result` *class*, not the raw LDAP result code (which would be a
wider, less bounded dimension). The Phase 2 subsystem gauges are **global** (no
per-entity tags) and carry only bounded enum tags (`health`, `status`). New
metrics must hold this line — an unbounded label (a DN, a username) would blow up
Prometheus' series count and can leak directory contents.

## Implementation notes

- `MetricsConfig` adds the common `application=ldap-portal` tag so a shared
  registry can tell this service apart.
- `LdapPoolMetrics.register(...)` is idempotent per directory and swallows its
  own failures — metrics must never break pool creation (the critical path).
- Metrics export is **disabled by default inside `@SpringBootTest`** (Spring
  Boot keeps only the in-memory `simple` registry so tests never push to a real
  backend). Tests that assert on the Prometheus endpoint re-enable it with
  `@AutoConfigureObservability`.
- `MeteredLdapInterface` is an `@LdapWriteAuthorized` chokepoint: it forwards
  every operation to the wrapped interface (issuing no writes of its own) and is
  enumerated in `docs/architecture/ldap-write-surface.md`. The operation timer's
  histogram buckets are set by a `MeterFilter` in `MetricsConfig`.
- The Phase 2 subsystem gauges (`SyncEngineMetrics`, `JobHealthMetrics`) are
  DB-backed: a scheduled `refresh()` (`ldapportal.metrics.refresh-ms`, default
  15 s) snapshots repository aggregates into in-memory holders the gauges read,
  decoupling scrape rate from DB load; the "age" gauges store the oldest
  timestamp and compute age live. Refresh failures are swallowed (the last
  snapshot is kept), never disrupting the app.

## Roadmap

Phases 0–2 are shipped (documented above). Planned follow-ups, each its own
branch/PR:

- **P1 — LDAP operations.** ✅ Shipped. Per-directory operation latency and
  error counts, tagged by operation verb and result class.
- **P2 — Sync engine & background jobs.** ✅ Shipped. Recompute-queue depth/lag,
  changelog lag/health, event-outbox backlog, scheduled-report job status.
- **P3 — Auth, licensing & inventory.** Three families, split so the
  operationally-useful half ships everywhere: **inventory** (directory / admin /
  subscriber / pending-approval counts) is edition-agnostic and always emitted; a
  **license overlay** (entitlement state, expiry, quotas) stays dormant in
  community via the `unlimited`/`never-expires` sentinels; plus an
  **auth-failure** counter. Full design:
  `docs/plans/2026-06-25-observability-phase3-plan.md`.
