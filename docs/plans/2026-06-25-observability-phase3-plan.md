# Observability Phase 3 — auth, licensing & inventory

**Status:** Not started (planned, 2026-06-25).

Phase 3 completes the self-observability roadmap (P0 connection pool, P1 LDAP
operations, P2 sync engine & background jobs — see `docs/observability.md`). It
adds three metric families: **inventory**, a **license/entitlement overlay**, and
**authentication failures**.

## Organizing principle: inventory vs. license overlay

The "usage" metrics split into two concerns that behave differently by edition,
so they are modelled separately:

- **Inventory** — *"how big is this deployment"*: directory count, admin
  accounts, event subscribers, pending approvals. This is operational data,
  **edition-agnostic and always emitted**, including in the community build. It
  is not licensing — it's the operator counting their own resources, scraped
  locally (no phone-home). Named `ldapportal_inventory_*`.

- **License overlay** — entitlement state, expiry, grace, and per-resource
  **quotas**. Reflects a signed license. Named `ldapportal_license_*` /
  `ldapportal_usage_limit`.

**No edition branching is needed.** The overlay goes dormant in community on its
own, via the sentinels already in the model:

- `License.limitFor(t)` returns `Long.MAX_VALUE` when a limit is absent →
  **skip emitting** that `usage_limit` series (community is uncapped, so no
  quota series and no `9.2e18` denominator to break ratio alerts).
- `expiresAt == Instant.MAX` (community "never expires") / grace state
  `NO_EXPIRY` → **skip** the expiry-timestamp series.

So community gets the genuinely useful half (inventory) while the licensing half
simply doesn't materialise until a real license is present.

Backing model (all in `com.ldapportal.core.entitlement`): `EntitlementService.current()`
→ `License` (`edition`, `has(Entitlement)`, `limitFor(LimitType)`, `expiresAt`,
`signature`); `Entitlement` (12 bounded values); `LimitType`
(`DIRECTORIES`, `ADMIN_ACCOUNTS`, `PROFILES_PER_DIRECTORY`, `TERRAFORM_*`,
`EVENT_SUBSCRIBERS`); the `LicenseStatus` controller already computes
`daysRemaining` + `graceState` (`NO_EXPIRY`/`VALID`/`APPROACHING_EXPIRY`/
`EXPIRED_WITHIN_GRACE`/`PAST_GRACE`) — reuse it.

## 1. Inventory (always-on, every edition)

| Prometheus metric | Type | Source |
| --- | --- | --- |
| `ldapportal_inventory_directories` | gauge | `DirectoryConnectionRepository.count()` |
| `ldapportal_inventory_admin_accounts` | gauge | `accountRepo.countByRoleAndActiveTrue(ADMIN)` (the count the `ADMIN_ACCOUNTS` cap already uses) |
| `ldapportal_inventory_event_subscribers` | gauge | active event subscriptions (new `count` query; 0 where the events module is inactive) |
| `ldapportal_inventory_pending_approvals` | gauge | `PendingApprovalRepository.countByStatus(PENDING)` |
| `ldapportal_inventory_pending_approval_oldest_age_seconds` | gauge | oldest `PENDING` approval (approval backlog); live-age, 0 when none |

Snapshot/refresh pattern (see Design notes). Community value: these are real
deployment-size signals regardless of license.

## 2. License overlay (dormant in community)

| Prometheus metric | Type | Source / behaviour |
| --- | --- | --- |
| `ldapportal_license_entitlement{entitlement="..."}` | gauge 0/1 | `has(e)` per `Entitlement` (12 series). **Community value = addon activation** — `VENDOR_INTEGRATIONS_ISVA=1` in community-plus-isva (granted by classpath probe, not a license); ee bits are constant 0. |
| `ldapportal_license_info{edition,signed}` | gauge =1 | install descriptor (Prometheus "info" pattern); useful for fleet inventory (which installs are community vs licensed). |
| `ldapportal_license_expiry_timestamp_seconds` | gauge | `expiresAt` epoch — **emitted only for real expiries** (skip `Instant.MAX`). |
| `ldapportal_license_grace_state{state="..."}` | gauge 0/1 | reuse `LicenseStatus` graceState; in community this is constant `NO_EXPIRY` → **drop in community** (or keep solely for schema uniformity). |
| `ldapportal_usage_limit{resource="..."}` | gauge | `license.limitFor(type)` — **emitted only when finite**. Pairs with the `inventory_*` count for quota alerts. |

Quota alerts join the overlay limit to the inventory count, e.g.
`ldapportal_inventory_directories / on() ldapportal_usage_limit{resource="directories"} > 0.9`.

`customer_id` label on `license_info`: **off by default** (marginal value,
slight exposure — it's the install's own id).

## 3. Authentication failures

| Prometheus metric | Type | Source |
| --- | --- | --- |
| `ldapportal_auth_failures_total{reason="...",principal="..."}` | counter | incremented at the auth-rejection sites |

- `reason` (bounded): `bad_credentials` / `account_locked` / `token_expired` /
  `token_invalid` / `disabled`.
- `principal` (bounded): `admin` / `self_service` / `api_token`.
- Injection points to confirm at implementation: the login path
  (`AuthService`) and the token filters (`JwtAuthenticationFilter`,
  `ApiTokenAuthenticationFilter`). The app uses custom JWT/API-token auth, not
  Spring Security form login, so the standard `AuthenticationFailure*` events may
  not fire — a counter incremented inline at each rejection is the reliable path.
- **No usernames, IPs, or DNs as tags** (cardinality + PII). Per-account
  failure detail stays in the audit log, not in metrics.

This is a counter (incremented at the event), not a snapshot gauge.

## Design notes

- **Pattern reuse.** The inventory + license-overlay gauges are DB/license-backed
  reads → reuse the P2 snapshot/refresh component shape (`SyncEngineMetrics` /
  `JobHealthMetrics`): a `@Scheduled refresh()` on `ldapportal.metrics.refresh-ms`
  feeding `AtomicLong` holders, plus the eager `@PostConstruct` prime so the first
  post-restart scrape is truthful. The license read is cheap but is cached in the
  same snapshot for consistency. Auth failures are an inline counter, no snapshot.
- **Edition boundary.** Everything lives in core; the `MAX_VALUE`/`Instant.MAX`
  sentinels make the overlay dormant in community with no `if (edition …)`.
- **Cardinality.** All bounded: entitlements 12, grace states 5, resources few,
  auth reasons/principals few. No per-entity tags by default.
- **No PII.** No usernames, DNs, or IPs anywhere.
- **Reuse existing computations.** `graceState`/`daysRemaining` from
  `LicenseStatus`; admin/profile counts from the existing license-enforcement
  call-site queries.

## Deferrals / open questions

- **Per-directory profiles usage** (`PROFILES_PER_DIRECTORY`) — the only
  *per-directory* cap (`profileRepo.countByDirectoryId`), and profiles is
  potentially the largest table. Defer, or gate behind a property, and emit
  per-directory (`directory_id` tag, bounded by directory count) only if enabled.
- **Terraform caps** (`TERRAFORM_DIRECTORIES`/`TERRAFORM_PROFILES`) — niche;
  defer.
- **`license_grace_state` in community** — constant `NO_EXPIRY`; decide whether
  schema uniformity is worth the constant series (lean: drop in community).
- **Auth-failure injection points** — confirm the exact rejection sites and the
  bounded `reason` set during implementation.

## Suggested PR slicing

Each its own branch/PR, matching the per-phase convention:

- **P3a — inventory** (always-on): directories, admin accounts, event
  subscribers, pending approvals (+ oldest-pending age).
- **P3b — license overlay**: entitlement / info / expiry / grace / `usage_limit`.
- **P3c — auth failures**: the rejection-site counter.

On delivery, update the `docs/observability.md` roadmap (P3 → Shipped) and add a
"License & inventory" / "Auth" subsection there, mirroring the P1/P2 write-ups.
