<!-- SPDX-License-Identifier: Apache-2.0 -->
# Configuration export for disaster recovery & IaC

**Status:** In progress (Phase 1 shipped 2026-07-29; Phase 2 — application settings shipped, 2026-07-30).

This is the **export** half of the IaC story. The companion design
[`2026-06-05-iac-automation-design.md`](2026-06-05-iac-automation-design.md)
made the API IaC-grade and added a startup **reconciler** that *applies* a
declarative `bootstrap-config.yml`. What was missing: a way to *produce* that
file from a live install — so an operator can snapshot a running portal for
disaster recovery, or seed an IaC repo from an existing deployment instead of
hand-authoring it. This design adds that exporter as the **inverse of the
reconciler**.

## 1. Goal & the one invariant

Dump the portal's own configuration to the exact YAML the
`BootstrapConfigReconciler` already consumes, so the round-trip holds:

> **export → (store in Git / vault) → `BOOTSTRAP_CONFIG_FILE` on a fresh
> install → reconcile → identical configuration.**

The load-bearing invariant is **export and reconcile stay in lockstep**: the
exporter only emits sections and fields the reconciler can apply. Emitting
something the reconciler can't consume (or a reference it can't resolve on a
fresh DB) produces a dump that fails to restore — worse than not exporting it.
Every section added to the exporter must be added to the reconciler first.

Non-goals: this exports **portal configuration**, not directory contents (LDAP
users/groups — that's bulk import / the provisioning SPI), not transactional
data (audit log, approvals, sync shadow), and not env/file config that already
lives in your infra IaC (`ENCRYPTION_KEY`, `JWT_SECRET`, the license file,
`BOOTSTRAP_SUPERADMIN_*`, scheduler crons).

## 2. Secrets: placeholders, not values

Secrets are AES-256-GCM encrypted at rest (keyed by `ENCRYPTION_KEY`) and
**write-only** across the API — they're never read back. So the exporter
**never emits a secret value**. For each stored credential it emits a
`${ENV_VAR}` placeholder that the reconciler resolves from the environment at
restore time (an unresolved placeholder aborts startup — fail-fast, the same
contract as a missing `ENCRYPTION_KEY`). The dump is therefore **safe to commit
to Git**; the operator supplies the actual secrets separately (secret manager /
encrypted vars file). A generated header enumerates every required variable.

Placeholder names are deterministic: `LDAPPORTAL_DIR_<SLUG>_BIND_PASSWORD`,
`LDAPPORTAL_DIR_<SLUG>_ENTRA_CLIENT_SECRET`, `LDAPPORTAL_ADMIN_<USERNAME>_PASSWORD`
(key upper-snake-cased, non-alphanumerics collapsed to `_`), and — for the
settings singleton — the fixed `LDAPPORTAL_SETTINGS_<FIELD>` set
(`…_SMTP_PASSWORD`, `…_S3_SECRET_KEY`, `…_LDAP_AUTH_BIND_PASSWORD`,
`…_OIDC_CLIENT_SECRET`, `…_SIEM_AUTH_TOKEN`, `…_WEBHOOK_AUTH_HEADER`).

> **The dump alone is not a complete DR bundle.** A full recovery also needs the
> `ENCRYPTION_KEY`, `JWT_SECRET`, the license file, and (re-minted) API-token
> plaintext — see §6. Pair the config dump with those, held in your secret
> store. The alternative "sealed" mode (decrypt-and-re-encrypt secrets into an
> `age`/`sops` sidecar) was considered and deferred (§5).

## 3. Delivery mechanisms (mirror the two import paths)

- **REST:** `GET /api/v1/superadmin/config/export` (SUPERADMIN) returns
  `application/yaml` — the inverse of the by-key upserts. `scripts/export-config.sh`
  wraps it (API-token or superadmin-login auth) and writes a timestamped file;
  `make export-config` is the convenience target.
- **(Future) offline CLI:** an `ApplicationRunner` guarded by `--export-config`
  for air-gapped export with no HTTP, symmetric with the reconciler being an
  `ApplicationRunner`. Deferred — the REST path covers the common case.

Output is sorted (directories by slug, admins by username, ISVA by slug) for
stable, diff-friendly dumps that behave well under version control.

## 4. Architecture

Symmetric with the reconciler, and respecting the edition boundary:

- `ConfigExportService` (core) walks each config family and serializes it,
  **reusing the same request records the reconciler validates**
  (`DirectoryConnectionRequest`, `AdminAccountRequest`,
  `FeaturePermissionRequest`) run through the same `ObjectMapper`. That's what
  guarantees enum/field serialization matches what the reconciler will parse
  back — the round-trip is correct by construction, not by hand-matching tokens.
- `ConfigExportContributor` (core SPI) is the inverse of
  `BootstrapConfigContributor`: an addon appends its own top-level section to
  the export document. `IsvaConfigExportContributor` (in `addons/isva`) emits
  the `isva` section. Community builds have no contributor and simply omit it.
- `ConfigExportController` exposes the SUPERADMIN endpoint.

Directory-specific export logic lives in `DirectoryConnectionService.exportAll()`
(it owns the entity + base-DN repos), returning the full restorable request
(including the trusted-cert PEM, which the read response DTO omits) with secrets
nulled and presence flags for the placeholder decision.

## 5. Coverage & roadmap

Each family becomes exportable **only once the reconciler can apply it**. Two
sharp edges gate the bigger families:

- **Provisioning-profile identity.** Admin `profileRoles` and per-profile
  feature overrides reference a provisioning-profile **UUID**. A fresh DB
  regenerates UUIDs, so these references can't round-trip until profiles get a
  stable IaC **slug** (a Flyway migration + by-slug upsert). This is the same
  open question flagged in the 2026-06-05 plan; the exporter makes it acute.
- **`auditDataSourceId`** on a directory references an audit source not yet in
  the reconciler; the exporter clears it (a dangling id would fail restore)
  until audit sources are added to both sides.

| Phase | Families | Status |
|---|---|---|
| **1** | `directories` (+ base DNs, object classes, cert PEM), `admins` (account + admin-wide feature permissions), `isva` | **Shipped 2026-07-29** |
| **2a** | `settings` singleton — branding, session, SMTP/S3, OIDC + LDAP admin-auth, SIEM/webhook, WebSEAL; six write-only secrets as placeholders | **Shipped 2026-07-30** |
| **2b** | Provisioning profiles (**+ new profile slug**), then admin `profileRoles` / per-profile overrides; audit data sources; superadmins | Planned |
| **3** | Sync links/sets, event subscriptions, CSV mapping templates, lifecycle playbooks, ISVA profile overrides; API-token **metadata** (plaintext non-recoverable — see §6) | Planned |
| **4** | Optional "sealed secrets" export mode (`--with-secrets` → encrypted `age`/`sops` sidecar) for unattended DR; feed `terraform import` from the dump | Optional |

**Phase 1 admin limitation (by design):** admins export with `profileRoles: []`
and admin-wide feature overrides only; profile-scoped bits arrive in Phase 2
with the profile slug. Superadmins are excluded (the reconciler's by-username
upsert manages ADMIN accounts; the bootstrap superadmin comes from env).

## 6. Full DR bundle (operator checklist)

The config dump is one artifact. A recoverable install also needs, from your
secret store / infra IaC:

1. **`ENCRYPTION_KEY`** — without the original key, no exported placeholder’s
   secret (nor any `pg_dump` ciphertext) can be decrypted on the target.
2. **`JWT_SECRET`**, cookie/auth settings.
3. **License file** (`ldapportal.license.path`) — entitlements are file/edition
   driven, not in the DB.
4. **Secret values** for every `${ENV_VAR}` in the dump header.
5. **API tokens** — stored as one-way hashes; plaintext can't be exported.
   Re-mint and re-distribute (Phase 3 exports their metadata to tell you which).

As a complementary raw backstop, `scripts/db-pull-from-fly.sh`'s `pg_dump`
approach captures *everything* including ciphertext and transactional data — use
it for forensics/rollback, and the YAML dump for reviewable, portable IaC.

## 7. Testing

- **Round-trip unit tests** (`ConfigExportServiceTest`,
  `IsvaConfigExportContributorTest`): every emitted section deserializes back
  into its request record and bean-validates with zero violations — proving the
  dump is reconciler-consumable without a DB.
- **Controller test** (`ConfigExportControllerTest`): SUPERADMIN-gated (403 for
  ADMIN, 401 anonymous), `application/yaml`, attachment disposition.
- Secrets never appear as values (asserted); `auditDataSourceId` and
  profile-scoped overrides are dropped (asserted).
