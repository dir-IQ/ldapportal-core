# IaC automation for LDAPPortal configuration — design plan

- **Date:** 2026-06-05
- **Status:** In progress (Phase 0 API hardening shipped; Phase 1 Ansible/REST
  docs shipped — see `docs/iac/`; Phase 2 bootstrap file not started, 2026-06-05).
- **Scope:** Let operators declare LDAPPortal's *own* configuration —
  directories + base DNs, admin accounts + permissions, vendor
  integrations (ISVA), and API tokens / licensing — as
  Infrastructure-as-Code, applied idempotently and non-interactively.
  This is about configuring **the portal**, not the directories it
  manages.
- **Audience:** Written to hand to a fresh Claude Code session in the
  `ldapportal-core` repo. Self-contained; assumes no prior conversation.
  Paths are relative to the repo root.
- **Branch:** `docs/iac-automation-design` (doc only; implementation
  branches cut later, per phase).

## 1. Goal

A customer wants to stand up and maintain LDAPPortal's configuration the
same way they manage the rest of their infrastructure: declaratively, in
version control, applied by automation (Ansible today, with Terraform
likely to follow), with no clicking through the SUPERADMIN UI.

The user-facing promise is:

> "I describe my directories, admins, permissions, integrations, and
> automation tokens in a file in Git. A pipeline applies it. Running it
> again changes nothing if nothing changed, and converges the portal to
> match the file if it drifted — without me logging into the UI."

### 1.1 Chosen direction (v1)

Per stakeholder decision (2026-06-05):

1. **Primary surface: a hardened, idempotent REST API** that Ansible (or
   any HTTP client) can drive. The work is mostly making existing
   endpoints **idempotent and import-friendly**, plus documenting the
   `ApiToken` auth flow.
2. **Companion: a declarative bootstrap file** (`YAML`/`JSON`) the
   container reconciles at startup, extending the existing
   `BootstrapService`. Covers GitOps / air-gapped / "config baked into
   the image" installs.
3. **Forward-compatible with Terraform.** No design choice here may
   make a future `terraform-provider-ldapportal` require rework. In
   practice that means: stable external identifiers, full-replace `PUT`
   semantics, write-only secret handling, and `If-Match`/version
   concurrency — all of which a Terraform provider needs anyway.

We explicitly **do not** build a bespoke `POST /config/apply`
"reconcile the whole manifest server-side" endpoint as the primary
mechanism. Reconciliation, diffing, and partial-failure handling are
exactly what Ansible/Terraform already do; duplicating that server-side
is a maintenance sink. The bootstrap file (#2) is the one server-side
reconciler we keep, and only because startup has no external driver.

## 2. Design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Primary driver | **External (Ansible/Terraform) over REST**, not a server-side apply engine | Desired-state logic lives in the tool that already does it well. Server stays a set of idempotent resources. |
| Identity for automation | **`ApiToken`** (`Authorization: ApiToken <plaintext>`) | Already built: hashed-at-rest, rotatable, revocable, optimistic-locked. No login/cookie dance. |
| Idempotency model | **Stable client-facing key per resource** + upsert `PUT` | The blocker today is server-generated UUID keys. Every IaC resource needs a name the client owns across runs. |
| Mutation semantics | **Full-replace `PUT`** (absent field → default), not merge-`PATCH` | Drift correction requires that removing a field from code reverts it server-side. |
| Secrets | **Write-only attributes**; never read back; change signalled by input change (+ optional `*_version` bump) | Bind passwords / Entra secrets / token plaintext are encrypted and one-way. A reconciler must not try to diff them. |
| Concurrency | **`ETag` + `If-Match`** on mutating endpoints, backed by JPA `@Version` | Safe concurrent applies; already have `@Version` on `ApiToken` to generalize. |
| Startup reconciler | **Extend `BootstrapService`** to optionally read a config file and converge | Reuses an existing idempotent `ApplicationRunner`; no new lifecycle machinery. |
| Edition boundary | **Core ships core resources; each addon ships its own** | ISVA config IaC lives in `addons/isva`, reaching core only via SPIs. Community build (no addon) must still expose a coherent surface; addon resources 402/403 cleanly. |
| Contract source of truth | **Existing OpenAPI spec** (`GET /api/v1/openapi`) | Already published + used by frontend codegen; becomes the basis for Ansible module docs and a future generated TF provider. |

## 3. Current state (what exists today)

Grounding for the gaps in §4. File references are current as of this
writing.

**Machine auth — already present.**
- `core/.../entity/ApiToken.java` — long-lived tokens; `tokenHash`
  (SHA-256), `tokenPrefix`, `expiresAt`, `revokedAt`, optimistic
  `version`; plaintext returned **once** at creation.
- `core/.../auth/ApiTokenAuthenticationFilter.java` — extracts
  `Authorization: ApiToken <plaintext>`, validates hash, builds
  `AuthPrincipal`; sits ahead of the JWT filter.
- `core/.../controller/superadmin/ApiTokenController.java` —
  `POST/GET/DELETE /api/v1/superadmin/api-tokens`, `.../{id}/rotate`.
  All `@PreAuthorize("hasRole('SUPERADMIN')")`. Tokens cannot mint /
  rotate / revoke other tokens.

**Contract — already present.**
- `GET /api/v1/openapi` (springdoc); `OpenApiConfig` registers
  `cookieAuth` + `bearerAuth` schemes. `OpenApiSpecEndToEndTest`
  asserts every `@RestController` is covered.

**Resources to expose.**
- Directories: `DirectoryConnection` + `DirectoryUserBaseDn` /
  `DirectoryGroupBaseDn`; `DirectoryConnectionController`
  (`/api/v1/superadmin/directories`, SUPERADMIN). Bind passwords +
  Entra secrets AES-256 encrypted via `ENCRYPTION_KEY`.
- Admins + permissions: `AdminManagementController`
  (`/api/v1/superadmin/admins`, incl. `with-permissions`,
  `permissions/profile-roles`, `permissions/features`). Backed by
  `AdminProfileRole`, `AdminFeaturePermission`, `FeatureKey` (29 keys).
- ISVA: `VendorIntegrationIsvaConfig` (PK = `directoryConnectionId`,
  1:1); `IsvaConfigController` —
  `PUT /api/v1/directories/{directoryId}/isva-config` is **already a
  true upsert** and is the template for §4.1. `@Entitled(VENDOR_INTEGRATIONS_ISVA)`.
- Licensing: `License` (record); `LicenseAutoConfiguration` (file-based
  signed-JWT provider via `ldapportal.license.path`, community
  fallback); `LicenseStatusController` `GET /api/v1/license/status`
  (read-only today).

**Startup config — already present.**
- `BootstrapService` (`ApplicationRunner`) creates the initial LOCAL
  SUPERADMIN once, from `BOOTSTRAP_SUPERADMIN_USERNAME` /
  `BOOTSTRAP_SUPERADMIN_PASSWORD`; idempotent (skips if an active LOCAL
  superadmin exists). This is the hook §6 extends.

## 4. Gaps to close (the actual work)

### 4.1 Idempotency / import

The central blocker: resources are addressed by server-generated UUIDs,
and `POST` mints a new row every apply.

**Approach:** give every IaC-managed resource a **stable, client-owned
key** and an upsert endpoint keyed by it, mirroring the ISVA pattern.

- Directories already have `displayName` (`@NotBlank`). Introduce/enforce
  a unique, immutable **`slug`/`name`** (URL-safe) as the external key.
  Add `PUT /api/v1/superadmin/directories/by-name/{name}` (or accept the
  key in-body on a `PUT` collection upsert) that creates-or-updates.
  Keep the UUID as the internal id and in responses so a Terraform
  provider can also implement plain `import` by id later.
- Admins: `username` is the natural external key. Generalize
  `with-permissions` into an upsert that sets account + profile-roles +
  feature-permissions to exactly the declared set (full-replace).
- ISVA: already keyed by directory + upsert — no change beyond keying
  the directory by name.
- API tokens: **not** upsertable by value (plaintext is one-time). Key
  by `name`; upsert manages *existence + metadata + expiry*; the secret
  is delivered once and thereafter referenced by the operator's own
  secret store. Rotation stays an explicit verb.

**Acceptance:** running the same declaration twice yields identical
server state and (for non-secret resources) a no-op second apply.

### 4.2 Full-replace semantics

For `PUT` upserts, an omitted optional field must revert to its default,
not be ignored. Today several update paths merge. Audit each exposed
endpoint; document per-field "replace vs preserve" in the OpenAPI
schema. Base-DN lists (`userBaseDns`/`groupBaseDns`) become declarative
set-replace, not append.

### 4.3 Write-only secrets

`bindPasswordEncrypted`, `entraClientSecretEncrypted`, token plaintext
are never readable. In the API + provider schema they are **write-only**:
- Reads return a presence indicator / last-rotated timestamp, never the
  value.
- A reconciler must not infer drift from them. Provide an optional
  `*_version` integer the operator bumps to force a re-write, so secret
  rotation is intentional and diffable without exposing the secret.

### 4.4 Concurrency

Add `ETag` to resource reads and require `If-Match` on mutating calls,
backed by JPA `@Version`. Generalize the `ApiToken.version` habit to
`DirectoryConnection`, admin entities, and ISVA config. Prevents two
concurrent applies (or an apply racing a UI edit) from silently
clobbering.

### 4.5 Scoped automation identities (deferred but designed-for)

`ApiToken.scopes` exists but is null in v1 (all tokens are full
SUPERADMIN). v1 ships unscoped; the **schema and filter must keep the
field** so Phase 3 can grant least-privilege automation tokens (e.g. a
token that may manage directories but not mint other tokens) without a
migration redesign. Document this as known-unscoped in v1.

## 5. REST / Ansible surface (v1)

Ansible drives the hardened endpoints. We ship **documentation + example
playbooks**, not a compiled binary. Deliverables:

- A `docs/iac/` guide: auth (create a bootstrap token via UI/bootstrap
  file → store in Vault → `Authorization: ApiToken`), the resource
  model, idempotency contract, secret handling, and the full-replace
  caveat.
- Example playbook using `ansible.builtin.uri` against the upsert
  endpoints for each resource family, with `check_mode` support where
  feasible (GET-then-diff).
- The OpenAPI spec as the canonical reference; ensure every IaC endpoint
  is covered by `OpenApiSpecEndToEndTest`.

A thin **Ansible module/collection** wrapping `uri` (better idempotency
reporting, `changed` accuracy) is optional polish, not required for v1.

## 6. Declarative bootstrap file (companion)

Extend `BootstrapService` to optionally load a config document and
converge to it at startup.

- **Source:** path from a new property, e.g.
  `app.bootstrap.config-file` / env `BOOTSTRAP_CONFIG_FILE`
  (default unset → today's behavior, superadmin-only). YAML preferred
  (operators read it); JSON also accepted.
- **Idempotent:** reconcile each declared resource through the *same*
  service-layer upsert paths used by the REST endpoints — not a second
  code path. The file is just another caller of the desired-state
  services.
- **Secret references:** never inline secrets in the file in plaintext;
  support `${ENV_VAR}` interpolation so the file stays Git-safe and
  secrets come from the environment / mounted secret.
- **Scope:** directories + base DNs, admins + profile-roles + feature
  permissions, ISVA config (when the addon is on the classpath; ignored
  with a logged notice otherwise — never a hard failure on community),
  and license file placement. API token *creation* via bootstrap is
  allowed but the plaintext can only be surfaced via logs/once-file —
  document the operational handling.
- **Failure mode:** fail fast and refuse to start on an invalid/partial
  config (consistent with how missing `ENCRYPTION_KEY`/`JWT_SECRET`
  already abort startup), with a clear ProblemDetail-style log.

This reuses the existing idempotency of `BootstrapService` and adds no
new lifecycle surface.

## 7. Edition boundary

Per the core/addon rule:

- Core ships IaC for **directories, admins/permissions, API tokens,
  license**. Community builds (no addon on classpath) expose this fully.
- **ISVA IaC ships in `addons/isva`** — its own controller/upsert
  (already there) and its own bootstrap-file contribution via a core
  SPI hook, never core importing addon code. With no addon present, the
  ISVA resource 402s (`@Entitled`) over REST and is skipped-with-notice
  in the bootstrap file. A future Terraform provider models ISVA as a
  separate resource type that degrades gracefully when the entitlement
  is absent.

## 8. Forward-compatibility with Terraform

Nothing in v1 may force provider rework later. The guarantees that buy
this — all also required by v1 Ansible — are: stable external keys
(§4.1), full-replace `PUT` (§4.2), write-only secrets with explicit
rotation signal (§4.3), `If-Match`/version concurrency (§4.4), and the
OpenAPI contract as the generation source. A provider can then map one
Terraform resource per family, use the upsert endpoints for
create/update, GET for read/drift, DELETE for destroy, and `import` by
UUID or name. No server change should be needed beyond what v1 delivers.

## 9. Phasing

1. **Phase 0 — make the API IaC-grade.** Stable external keys + upsert
   endpoints (§4.1), full-replace semantics (§4.2), write-only secrets
   (§4.3), `If-Match`/`@Version` (§4.4). Extend
   `OpenApiSpecEndToEndTest` coverage. *Schema changes = Flyway
   migrations in `db/migration/core` (and `addons/isva` for ISVA keys).*
2. **Phase 1 — Ansible surface.** `docs/iac/` guide + example playbooks
   + auth flow docs. Optional thin Ansible module.
3. **Phase 2 — declarative bootstrap file.** Extend `BootstrapService`
   (§6), env-interpolated secrets, edition-aware resource set.
4. **Phase 3 — scoped automation tokens.** Wire up `ApiToken.scopes`
   for least-privilege IaC identities (§4.5).
5. **Phase 4 (later, optional) — `terraform-provider-ldapportal`** over
   the now-stable contract (§8). Separate repo; no core change expected.

## 10. Open questions

- **External key for directories:** add a dedicated immutable `slug`, or
  promote `displayName` to a unique key? (Leaning: dedicated `slug` —
  display names change; keys must not.)
- **Bootstrap reconcile = create-only or full converge?** i.e. does the
  startup file also *delete* resources absent from it, or only
  create/update? Deleting is more "declarative" but riskier at boot.
  (Leaning: create/update + update-in-place in v1; no destructive
  delete from the bootstrap path. External Ansible/TF own deletes.)
- **License application over API:** today licensing is file + restart.
  Do we want `PUT /api/v1/license` to hot-apply a signed JWT, or keep it
  file-only and just expose status? (Leaning: file-only for v1;
  revisit.)
- **API token plaintext in automation:** confirm the operational
  contract for surfacing once-only secrets created via bootstrap
  (logs vs. write-once file vs. require UI/REST creation).

## 11. Acceptance criteria (v1 = Phases 0–2)

- Re-applying an unchanged declaration via Ansible is a clean no-op for
  all non-secret resources.
- A changed field in code converges the portal on next apply; a removed
  optional field reverts to default.
- Secrets are never read back; rotation is explicit and diff-visible
  without exposure.
- A community build (no ISVA addon) applies the core resource set with
  no errors; ISVA declarations are cleanly rejected/skipped, not 500s.
- The bootstrap file brings a fresh container to the declared state at
  startup, idempotently, with secrets sourced from the environment.
- Every IaC endpoint appears in the OpenAPI spec and is covered by
  `OpenApiSpecEndToEndTest`.
