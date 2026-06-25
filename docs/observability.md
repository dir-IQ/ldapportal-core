# Observability — Prometheus metrics

**Status:** In progress (Phases 0–1 shipped, 2026-06-25).

LDAPPortal exports operational-health metrics in Prometheus format from the
core backend. This is self-observability — the health of *the portal*, not of
the directories it manages. Phase 0 wires the registry, secures the scrape
endpoint, and ships the LDAP connection-pool meters; Phase 1 adds per-directory
LDAP operation latency and error counts; later phases add subsystem metrics
(see [Roadmap](#roadmap)).

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

### Free — Spring Boot / Micrometer defaults

The same registry also carries Boot's built-in binders, with no extra code:

- **JVM** — `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_live_threads`, …
- **HTTP server** — `http_server_requests_seconds{uri,method,status,outcome}` (latency + error rate per route)
- **HikariCP** (the app's own Postgres pool) — `hikaricp_connections_*`
- **System / process** — `process_cpu_usage`, `system_load_average_1m`, file descriptors, uptime
- **Logback** — `logback_events_total{level}` (e.g. error-log rate)

## Cardinality & privacy

Tags are restricted to **bounded, low-cardinality** dimensions: directory id /
display name / type, the operation verb, and a fixed set of result classes. User
identifiers, DNs, entry attributes, filters, and secrets are **never** used as
metric names, labels, or values — note in particular that the operation timer
tags a coarse `result` *class*, not the raw LDAP result code (which would be a
wider, less bounded dimension). New metrics must hold this line — an unbounded
label (a DN, a username) would blow up Prometheus' series count and can leak
directory contents.

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

## Roadmap

Phases 0 and 1 are shipped (documented above). Planned follow-ups, each its own
branch/PR:

- **P1 — LDAP operations.** ✅ Shipped. Per-directory operation latency and
  error counts, tagged by operation verb and result class.
- **P2 — Sync engine & scheduled jobs.** Changelog lag, recompute-queue depth,
  scheduled-report job success/failure and last-run age.
- **P3 — Auth, licensing, inventory.** Authentication-failure rate, entitlement
  / usage gauges, pending-approval counts, configured-directory inventory.
