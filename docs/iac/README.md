<!-- SPDX-License-Identifier: Apache-2.0 -->
# Managing LDAPPortal configuration as code (Ansible / REST)

**Status:** Shipped (Phase 1 — Ansible/REST surface, 2026-06-05).

This guide shows how to manage **LDAPPortal's own configuration** — directories,
admin accounts and their permissions, the ISVA vendor integration, and API
tokens — declaratively, from automation, against the REST API. It is the
operator-facing half of the IaC plan in
[`docs/plans/2026-06-05-iac-automation-design.md`](../plans/2026-06-05-iac-automation-design.md);
read that for the design rationale.

A runnable Ansible example lives in [`examples/`](examples/). The canonical,
always-current API reference is the OpenAPI document the server publishes at
`GET /api/v1/openapi` (Swagger UI at `/swagger-ui.html`, SUPERADMIN-only).

> This manages the **portal's configuration**, not the directories it manages.
> Provisioning LDAP users/groups is a separate, existing capability (bulk
> import, the provisioning-plan SPI).

---

## 1. The model in one paragraph

Every IaC-managed resource has a **stable, client-owned key** and an
**idempotent upsert** endpoint addressed by that key. You `PUT` the desired
state; the server creates the resource on first apply and converges it to match
on every subsequent apply, writing nothing when nothing changed. Concurrency is
guarded by a per-resource version surfaced as an HTTP `ETag`; you may send it
back in `If-Match` for a safe read-modify-write. Secrets are **write-only** —
you send them to set them and omit them to preserve them; they are never read
back.

| Resource | Stable key | Upsert endpoint (all `PUT`, all SUPERADMIN) |
|---|---|---|
| Directory connection | `slug` | `/api/v1/superadmin/directories/by-slug/{slug}` |
| Admin account (+ permissions) | `username` | `/api/v1/superadmin/admins/by-username/{username}` |
| ISVA vendor config | directory `slug` | `/api/v1/directories/by-slug/{slug}/isva-config` |
| API token | `name` | `/api/v1/superadmin/api-tokens/by-name/{name}` |

Plain id-addressed CRUD (`/directories/{id}`, `/admins/{id}`, …) still exists
for the UI; IaC should use the by-key upsert endpoints above so it never has to
track server-assigned UUIDs.

---

## 2. Authentication

All these endpoints require a **SUPERADMIN** principal. Two credential types
work:

- **API token** (for automation): send it as a bearer token. The token
  plaintext always starts with `ldap_pat_`:

  ```
  Authorization: Bearer ldap_pat_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
  ```

  The server distinguishes API tokens from JWTs by that prefix.

- **JWT session** (for interactive / bootstrap): `POST /api/v1/auth/login` with
  `{"username","password"}`. The JWT is returned **only as an httpOnly cookie**
  (`jwt-token`), not in the response body — capture the `Set-Cookie` and send it
  back as a `Cookie:` header on subsequent calls.

### Bootstrapping the first token

There's a deliberate restriction: **a request authenticated by an API token
cannot create, rotate, or revoke API tokens** (no token self-replication). So
the *first* automation token must be minted by an interactive SUPERADMIN:

1. A SUPERADMIN exists (the bootstrap superadmin from `BOOTSTRAP_SUPERADMIN_*`,
   or any other).
2. Log in (UI, or `POST /api/v1/auth/login`) to get a JWT session.
3. Create a token: `POST /api/v1/superadmin/api-tokens` (or the by-name upsert)
   — the plaintext is returned **exactly once**.
4. Store that plaintext in your secret manager (Ansible Vault, HashiCorp Vault,
   etc.). Automation then uses it as the bearer token for everything except
   token management itself.

In the example playbook this is the `tokens` play (superadmin login → manage
tokens); the directory/admin/ISVA plays use the API token.

---

## 3. The idempotency contract

- **Re-applying the same declaration converges to identical state and writes
  nothing** when nothing changed (no row churn, no audit-log spam, no LDAP
  connection-pool eviction).
- **Status codes** signal create vs. update:
  - Directories, admins, tokens: **201** on first apply (created), **200**
    thereafter (updated in place).
  - ISVA config: **200** on both create and update (it has no separate "created"
    signal — check the response body / probe if you need to know).
- **Full-replace semantics** — the request is the complete desired state for the
  dimensions it owns:
  - Directory: `userBaseDns` / `groupBaseDns` are replaced to exactly the lists
    you send (omit ⇒ empty).
  - Admin: `profileRoles` and `featurePermissions` are replaced — any role or
    override **not** in the request is removed.
- **Validation failures** return **400** (`IllegalArgumentException` →
  RFC 7807 ProblemDetail); a duplicate key from a concurrent create returns
  **409**; a licensing/edition gate returns **402**.

> **Primitive-field gotcha:** the directory request body is a Java record with
> primitive `int`/`boolean` fields (`port`, `pagingSize`, `pool*`, `enabled`,
> …). If you omit them they deserialize to `0`/`false`, and e.g. `port: 0`
> fails the `@Min(1)` check with a 400. Always send the full set — the example
> vars do.

---

## 4. Optimistic concurrency (ETag / If-Match)

Every IaC resource carries a numeric `version` in its body and as a strong
`ETag` header on reads and writes:

```
ETag: "7"
```

`If-Match` is **optional**:

- Omit it ⇒ last-write-wins (still safe against a true lost update — a racing
  commit makes your write fail with **409**).
- Send the version you last saw ⇒ the write proceeds only if the resource is
  still at that version, otherwise **412 Precondition Failed**. Use this for a
  safe GET-modify-PUT, or to detect drift before overwriting.

```
If-Match: "7"
```

An update response returns the **post-increment** ETag, so you can chain writes
using the value you just received.

---

## 5. Secret handling (write-only)

Secrets are never returned. The pattern is the same everywhere:

- **Send to set, omit to preserve.** On a directory update, omit `bindPassword`
  / `entraClientSecret` to keep the stored value; on an admin update omit
  `password`.
- **Presence indicators** tell you whether a credential is already stored
  without revealing it: directory responses carry `bindPasswordSet` /
  `entraClientSecretSet`; admin responses carry `passwordSet`. An applier can
  use these to decide whether it must supply a secret on first create.
- **API-token plaintext is one-time.** It's returned only in the **201** body
  when the token is first created; the metadata-only update (**200**) returns no
  plaintext. Rotating a token's secret is a separate, explicit verb
  (`POST /api/v1/superadmin/api-tokens/{id}/rotate`) — a re-apply never rotates
  it, so automation can run repeatedly without invalidating the credential it's
  using.

Keep the secrets you send (bind passwords, the one-time token plaintext) in a
secret manager, not in plain inventory. The example uses an Ansible Vault file.

---

## 6. Resource reference

Field lists below are the load-bearing ones; see the OpenAPI spec for the full
schema and enums.

### Directory — `DirectoryConnectionRequest`
`displayName` (required), `slug` (optional in body; the path value wins and is
**immutable**), `directoryType` (`GENERIC` | `ACTIVE_DIRECTORY` | `OPENLDAP` |
`IBM_DIRECTORY_SERVER` | `ORACLE_UNIFIED_DIRECTORY` | `ENTRA_ID`), `host`,
`port` (1–65535),
`sslMode` (`NONE` | `LDAPS` | `STARTTLS`), `trustAllCerts`,
`trustedCertificatePem`, `bindDn`, `bindPassword` (**write-only**; required on
create, omit to preserve), `baseDn`, `pagingSize`, `poolMinSize`,
`poolMaxSize`, `poolConnectTimeoutSeconds`, `poolResponseTimeoutSeconds`,
`enabled`, `userBaseDns` / `groupBaseDns` (`[{ "dn", "displayOrder" }]`,
full-replace), `userObjectClasses` / `groupObjectClasses` (optional lists; omit
to use the vendor default for `directoryType`). Entra-only: `tenantId`,
`entraClientId`, `entraClientSecret` (**write-only**), `graphEndpoint`.

### Admin — `CreateAdminWithPermissionsRequest`
```jsonc
{
  "account": {
    "username": "...",        // MUST equal the {username} path segment
    "role": "ADMIN",          // by-username upsert manages ADMIN accounts only
    "authType": "LOCAL",      // LOCAL | LDAP | OIDC | WEBSEAL
    "password": "...",        // LOCAL only; write-only, omit to preserve
    "displayName": "...", "email": "...", "active": true
  },
  "profileRoles": [ { "profileId": "<uuid>", "baseRole": "ADMIN" } ],
  "featurePermissions": [ { "featureKey": "user.create", "enabled": true,
                            "profileId": "<uuid|null>" } ]
}
```
`profileRoles` and `featurePermissions` are **full-replace**. A per-profile
feature override requires a matching `profileRole` on the same profile.

> **Limitation:** `profileRoles[].profileId` is a provisioning-profile **UUID**,
> not a stable slug. Until profiles get an IaC key of their own, resolve the
> UUIDs once (e.g. `GET` the profiles list) and pin them in your vars.

### ISVA config — `UpsertIsvaConfigRequest`
`enabled`, `topologyMode` (`INLINE` | `LINKED`), `secAuthority`,
`defaultValidUntilYears` (≥1), `deletePolicy` (`DISABLE` | `HARD_DELETE`),
`requireSecGroup`. Linked-mode
only: `managementDitBaseDn` (**required when `LINKED`**), `secuserRdnAttribute`,
`groupMemberTarget`, `onDemographicDelete`. The endpoint is gated by the
`VENDOR_INTEGRATIONS_ISVA` entitlement — a community build without the addon
returns **402/403**.

### API token — `UpsertApiTokenRequest`
`description`, `expiresAt` (ISO-8601 instant, must be in the future, ≤ 2 years).
The `name` comes from the path and is the key.

---

## 7. Running the example

See [`examples/`](examples/):

```bash
cd docs/iac/examples
# 1. Create the secrets file from the template, fill it in, and encrypt it:
cp vault.example.yml group_vars/all/vault.yml   # then edit real values
ansible-vault encrypt group_vars/all/vault.yml
# 2. Dry-run, then apply:
ansible-playbook -i inventory.ini ldapportal-iac.yml --ask-vault-pass --check
ansible-playbook -i inventory.ini ldapportal-iac.yml --ask-vault-pass
```

The playbook is organised as:

- **`tokens`** play — logs in as a SUPERADMIN (Vault creds) and upserts the
  automation token(s); prints the one-time plaintext of any newly created token.
- **`provisioning`** play — uses the API token to upsert directories, admin
  accounts, and ISVA config.

Run a single resource family with `--tags directories|admins|isva|tokens`.

---

## 8. Known limitations / sharp edges

- **Admin profiles are keyed by UUID** (see §6) — the one place IaC still needs
  a server-assigned id.
- **ISVA upsert returns 200 for both create and update** — no create/update
  signal in the status line.
- **Token management needs a JWT, not an API token** (§2) — keep the superadmin
  credential available to the `tokens` play only.
- **Precise `changed` reporting:** a bare `PUT` returns 200 on every update
  whether or not anything changed. The playbook reports `changed` on 201 and, on
  200, compares the response to the request for the fields it manages. For
  exact drift detection you can GET first and diff (and pass the ETag as
  `If-Match`); §4 covers the mechanics.

---

## 9. Declarative bootstrap file (startup reconcile)

For GitOps / air-gapped / config-baked-into-the-image installs where running
Ansible against the API is awkward, the server can reconcile a declarative
config file **at startup** — no external driver. It uses the same idempotent
upserts under the hood, so it composes with (and is safe to run alongside) the
Ansible path.

Point `BOOTSTRAP_CONFIG_FILE` at a readable YAML file:

```
BOOTSTRAP_CONFIG_FILE=/etc/ldapportal/bootstrap-config.yml
```

Unset (the default) disables it entirely. When set, on every boot the server:

1. reads the file and resolves `${ENV_VAR}` / `${VAR:default}` placeholders
   against the environment (so secrets stay out of the Git-tracked file);
2. parses and **validates the whole file up front** — a malformed config, an
   unresolved placeholder, or an unreadable file **aborts startup** (fail-fast,
   like a missing `ENCRYPTION_KEY`);
3. upserts the `directories` then `admins` sections through the same idempotent
   service paths as the REST API;
4. hands the parsed config to each addon contributor (the ISVA addon applies the
   `isva` section; on a community build with no addon, that section is ignored).

It is **create/update only** — it never deletes resources absent from the file;
manage deletions through the API / Ansible. Re-running converges and writes
nothing when nothing changed. See
[`examples/bootstrap-config.example.yml`](examples/bootstrap-config.example.yml)
for the file shape (it mirrors the REST request bodies in §6).

The file format and the Ansible vars in §7 are intentionally close, so the two
delivery mechanisms share the same mental model.
