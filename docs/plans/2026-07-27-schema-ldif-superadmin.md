# Schema updates via LDIF (superadmin) — implementation plan

- **Date:** 2026-07-27
- **Status:** Draft (design only; no code yet).
- **Scope:** Let a superadmin **apply directory-schema changes** (new
  `attributeTypes` / `objectClasses`, and additive modifications to existing
  ones) by uploading an **LDIF**, with a server-side **preview** before
  anything is written — mirroring the existing entry-level LDIF import
  preview. **v1 vendors: OpenLDAP and OpenDJ/OUD only.** `core` only, no
  addon. Superadmin-gated behind a new `MANAGE_SCHEMA` permission.
- **Audience:** Written to hand to a fresh Claude Code session in
  `ldapportal-core`. Self-contained; paths relative to repo root.
- **Branch:** `claude/ldif-schema-updates-superadmin-om195j`, cut from
  `origin/main`.

## 1. Goal

> "As a superadmin, let me paste or upload an LDIF that adds an
> `attributeType` and an `objectClass` to a directory's schema, show me
> exactly what it will do — which elements are new, which already exist,
> which OIDs collide, which reference an unknown `SUP` — and only then let
> me apply it. And do it correctly for both OpenLDAP and OpenDJ, whose
> schema-write mechanics differ."

Today the portal can **read** schema (`LdapSchemaService.getObjectClassNames`
/ `getAttributeTypeInfo`, surfaced at `.../browse/schema/object-classes`) and
can **import entry LDIF** with a preview
(`LdifPreviewService` → `.../browse/import/ldif/preview[/apply]`). It cannot
**write schema**. Operators drop to SSH + `ldapadd -Y EXTERNAL` /
`ldapmodify` (see the manual-fallback notes in
`testdata/isva-schema-openldap.ldif` and `testdata/isva-schema-opendj-inline.ldif`).
This plan closes that gap through the portal.

## 2. Current state (verified)

| Concern | Today | Relevance |
|---|---|---|
| Schema **read** | `core/.../ldap/LdapSchemaService.java` — `conn.getSchema()` (line 261), enumerates objectClasses/attributeTypes, resolves MUST/MAY + syntax; **rejects Entra** (line 40) | Reuse for the diff ("does this element already exist?", OID lookup). |
| Entry **LDIF import + preview** | `core/.../ldap/LdifService.java`, `LdifPreviewService.java`; endpoints on `BrowseController.java` (`/import/ldif/preview`, `/preview/{id}`, `/preview/{id}/apply`) | The **UX + statefulness pattern to mirror**: parse once, classify each record, cache under a TTL'd per-owner `previewId` (`LdifPreviewService.java:93,179`), `apply()` runs the previewed records then evicts (line 235). |
| Preview DTOs | `core/.../dto/ldap/` — `LdifPreviewSummary`, `LdifPreviewRow`, `LdifPreviewRowDetail`, `LdifPreviewIssue`, `LdifPreviewOp`, `ApplyLdifPreviewRequest`, `LdifImportResult` | Parallel a **schema-specific** DTO family (elements, not entries). |
| LDIF frontend | `frontend/src/components/LdifImportModal.vue`, `frontend/src/api/browse.js` | Adapt the preview-table modal for schema elements. |
| **Writes** | Funnel through `PlanExecutor` (`core/.../core/provisioning/PlanExecutor.java:137`) / `LdapOperationService`; raw `conn.modify(...)` used by `LdifService` change records | Schema writes are LDAP `modify`s too, but against the **schema subentry**, not the data DIT — see §3. |
| Superadmin authz | Coarse `hasRole('SUPERADMIN')` on `/api/v1/superadmin/**` (`SecurityConfig.java:107`); fine-grained `@RequiresSuperadminPermission` + `SuperadminPermissionAspect` | Add a new `MANAGE_SCHEMA` permission and gate the new controller with it. |
| Permission catalogue | `core/.../entity/enums/SuperadminPermission.java` (10 values); grants table `V13__superadmin_permissions.sql`; owner model = `MANAGE_SUPERADMINS` holds all | Add enum value + Flyway migration (latest is **V22**, so **V23**). |
| Connection model | `core/.../entity/DirectoryConnection.java` — host/port, **one** bindDn + encrypted password, sslMode, pool sizing | **Gap:** stores only the *data* bind. OpenLDAP schema needs the **`cn=config` bind** (§3). |
| Audit | `AuditAction` enum has `LDIF_IMPORT ("ldif.import")`, `INTEGRITY_CHECK` (`core/.../entity/enums/AuditAction.java:44-45`) | Add `SCHEMA_UPDATE ("schema.update")`; fire via the existing async audit path. |
| Fixtures | `testdata/isva-schema-openldap.ldif` (cn=config form), `testdata/isva-schema-opendj-inline.ldif` (subschema-entry form) | **Ready-made test inputs** demonstrating both dialects (§3). |

## 3. The central decision: schema writes are vendor-specific

Reading schema is uniform (`conn.getSchema()` works everywhere). **Writing**
it is not — the *target DN*, the *value attribute*, and the *bind identity*
differ per vendor. This is why schema-LDIF is a new capability, not a flag on
the entry importer.

### OpenLDAP (`cn=config` / OLC)

- Schema lives under **`cn={N}schema,cn=schema,cn=config`**, objectClass
  `olcSchemaConfig`, values in **`olcAttributeTypes`** / **`olcObjectClasses`**
  (see `testdata/isva-schema-openldap.ldif`).
- A runtime add is a `changetype: modify` that **adds an `olcAttributeTypes`
  value** to an existing schema entry (or an `add` of a new `olcSchemaConfig`
  entry).
- **Crucially, this requires the config-admin bind** (`cn=admin,cn=config`
  with `LDAP_CONFIG_PASSWORD`, `"config"` in the fixture —
  `compose.yaml:220`), **not** the data admin (`cn=admin,dc=openldap,…`,
  `"admin"`). The data bind that `DirectoryConnection` stores today **cannot
  write schema.** → schema/entity change required (§5.0).
- OpenLDAP schema is **additive-only at runtime**: you can add
  attributeTypes/objectClasses, but you cannot modify or delete an existing
  one online (that needs offline `slapd.d` editing). The preview must **reject
  delete/replace of existing elements** for OpenLDAP and say why.

### OpenDJ / OUD

- Schema lives in the subschema entry **`cn=schema`**, values in
  **`attributeTypes`** / **`objectClasses`** (see
  `testdata/isva-schema-opendj-inline.ldif`).
- A change is a `changetype: modify` adding those values, applied with the
  **ordinary directory-manager bind** — no separate config connection.
- OpenDJ tolerates a broader set of online schema changes than OpenLDAP.

### Entra ID

- No LDAP schema. **Reject**, exactly as `LdapSchemaService` already does
  (`LdapSchemaService.java:40`).

### Design consequence

A `SchemaWriteStrategy` interface, resolved from `DirectoryType`, encapsulates
the per-vendor differences. This mirrors the existing vendor-strategy pattern
in `core/.../ldap/changelog/` (`DseeChangelogStrategy`, `AccesslogStrategy`,
`DirSyncChangelogStrategy`).

```java
interface SchemaWriteStrategy {
    DirectoryType vendor();
    // Where schema elements are read/written (e.g. cn=schema,cn=config vs cn=schema)
    // Which connection/bind to use (data pool vs a cn=config bind)
    // Translate a parsed element into the vendor's LDIF modify (olcAttributeTypes vs attributeTypes)
    // Preflight: reject unsupported ops (e.g. OpenLDAP delete/replace of existing)
    List<Modification> toModifications(SchemaPreviewElement el);
    void preflight(DirectoryConnection dc, List<SchemaPreviewElement> els);
}
```

Implementations for v1: `OpenLdapSchemaWriteStrategy`,
`OpenDjSchemaWriteStrategy`. A registry/factory picks by
`dc.getDirectoryType()`; unsupported vendors (AD, ITDS, GENERIC, ENTRA) throw
a clear 422 "schema-via-LDIF not supported for <vendor>".

## 4. Scope decisions (locked)

- **Vendors: OpenLDAP + OpenDJ/OUD only.** Both are in `compose.yaml`
  fixtures, so both are testable end-to-end. Interface leaves room for
  AD/ITDS later.
- **Preview-then-apply is mandatory.** No direct-apply endpoint. Schema
  changes are high blast-radius and largely irreversible online.
- **Additive focus in v1.** Support *adding* attributeTypes/objectClasses and
  (where the vendor allows) additive `modify` of existing ones. **Deletes are
  out of scope** for v1 (§8) — the preview flags them as unsupported.
- **Snapshot before apply.** Provide a schema **export** endpoint so an
  operator can capture current schema first (parallels
  `.../browse/export/ldif`).
- **Stateful preview** keyed by short-lived per-owner `previewId`, reusing the
  `LdifPreviewService` caching shape (TTL, owner check, evict-on-apply).

## 5. Backend changes

### 5.0 Connection model: the `cn=config` bind (OpenLDAP only)

`DirectoryConnection` stores a single bind. OpenLDAP schema writes need a
config-admin bind. Options, cheapest first:

- **(A) Per-request config credentials.** The apply request carries an
  optional `configBindDn` + `configPassword` used only for OpenLDAP schema
  writes; never persisted. Simplest; no migration; matches "superadmin does a
  deliberate, occasional operation." **Recommended for v1.**
- **(B) Persist an optional config bind** on `DirectoryConnection`
  (`config_bind_dn`, `config_bind_password_enc`), encrypted via
  `EncryptionService` like the primary password. More convenient, more schema
  + UI surface. Defer to v2.

Pick (A) for v1; note (B) as the follow-up. OpenDJ needs neither (uses the
existing pooled bind).

### 5.1 Permission + migration

- Add `MANAGE_SCHEMA ("superadmin.manage_schema")` to
  `SuperadminPermission.java`.
- `V23__superadmin_manage_schema.sql`: backfill owners
  (`INSERT … SELECT` where a superadmin already holds
  `superadmin.manage_superadmins`) so the upgrade is zero-behaviour-change,
  matching the V13 backfill idiom.
- Add label in `frontend/src/constants/superadminPermissions.ts`.

### 5.2 DTOs (`core/.../dto/schema/`)

- `SchemaPreviewElement(kind, name, oid, action, rawDefinition, issues)` where
  `kind ∈ {ATTRIBUTE_TYPE, OBJECT_CLASS}`, `action ∈ {ADD_NEW,
  MODIFY_EXISTING, UNSUPPORTED}`.
- `SchemaPreviewSummary(previewId, directoryId, vendor, counts, elements,
  blocking)` — `blocking=true` when any element is `UNSUPPORTED`/error.
- `SchemaPreviewIssue(severity, code, message)` — codes: `OID_COLLISION`,
  `NAME_COLLISION`, `UNKNOWN_SUP`, `DELETE_UNSUPPORTED`,
  `MODIFY_UNSUPPORTED_OPENLDAP`, `PARSE_ERROR`.
- `ApplySchemaPreviewRequest(previewId, optional configBindDn/configPassword)`.
- `SchemaUpdateResult(applied, skipped, failed, errors)`.

### 5.3 Service: `SchemaLdifService`

- `createPreview(dc, ldifBytes, ownerId)`:
  1. Reject Entra + unsupported vendors up front.
  2. Parse the LDIF (reuse the UnboundID `LDIFReader` machinery already used
     by `LdifPreviewService`; schema LDIF elements are attribute values, so
     also parse the embedded `attributeTypes`/`objectClasses` /
     `olcAttributeTypes`/`olcObjectClasses` values with
     `com.unboundid.ldap.sdk.schema.{AttributeTypeDefinition,ObjectClassDefinition}`).
  3. Load live schema via `LdapSchemaService` / `conn.getSchema()`; for each
     element classify ADD_NEW vs MODIFY_EXISTING and detect OID/name
     collisions and unknown `SUP`.
  4. Delegate vendor-specific `preflight` to the strategy (e.g. OpenLDAP
     rejects deletes/replaces).
  5. Cache under a per-owner TTL'd `previewId` (copy `LdifPreviewService`'s
     `CachedPreview` + `require(previewId, ownerId)` pattern).
- `apply(previewId, ownerId, dc, configCreds)`: re-load the cached preview,
  refuse if `blocking`, translate elements to `Modification`s via the
  strategy, and write:
  - **OpenDJ:** `connectionFactory.withConnection(dc, conn -> conn.modify(cn=schema, mods))`.
  - **OpenLDAP:** open a **separate connection bound as the config admin**
    (new `LdapConnectionFactory.openConfigConnection(dc, configCreds)` or a
    one-shot `openUnboundConnection`-style helper) and
    `conn.modify(cn={N}schema,cn=schema,cn=config, mods)`.
  Then evict the preview and fire the audit event. Mark the class
  `@LdapWriteAuthorized`.

### 5.4 Controller: `SchemaManagementController`

`@RequestMapping("/api/v1/superadmin/directories/{directoryId}/schema")`,
class-level `@PreAuthorize("hasRole('SUPERADMIN')")` **and**
`@RequiresSuperadminPermission(SuperadminPermission.MANAGE_SCHEMA)`.

- `POST /import/preview` (multipart LDIF) → `SchemaPreviewSummary`
- `GET  /import/preview/{previewId}` → cached summary
- `POST /import/preview/{previewId}/apply` → `SchemaUpdateResult`
- `GET  /export` → current schema as LDIF (attachment; snapshot-before-change)

Fire `AuditAction.SCHEMA_UPDATE` on apply with element counts + directory id.
Keep the new controller **out of `BrowseController`** — different permission,
different write surface, different vendor mechanics.

### 5.5 `LdapConnectionFactory`

Add a helper to obtain a connection bound with **caller-supplied config
credentials** for OpenLDAP `cn=config` writes (do not pool; close after use),
reusing the existing TLS/`ServerSet` construction.

## 6. Frontend changes

- `frontend/src/api/schema.ts` — `previewSchemaLdif`, `getSchemaPreview`,
  `applySchemaPreview`, `exportSchema` (wrap `apiPost`/`apiGet`; hand-type DTOs
  until OpenAPI is regenerated, as done for the superadmin permissions client).
- `frontend/src/views/superadmin/SchemaManageView.vue` — adapt
  `LdifImportModal.vue`'s preview table: columns kind / name / OID / action /
  issues; a **blocking banner** when any element is unsupported; an
  OpenLDAP-only config-credentials field on apply; an "Export current schema"
  button.
- Route: lazy component under `superadmin/*` with
  `meta: { requiresSuperadmin: true }` in `frontend/src/router/index.js`.
- Nav: link in `AppLayout.vue` gated by
  `auth.hasSuperadminPermission('superadmin.manage_schema')`.
- Label: `frontend/src/constants/superadminPermissions.ts`.

## 7. Testing

- **Backend unit:** `SchemaLdifServiceTest` — parse + classify against a
  stub/live schema; OID/name collision detection; unknown-`SUP`; OpenLDAP
  delete/modify rejection. Strategy tests:
  `OpenLdapSchemaWriteStrategyTest`, `OpenDjSchemaWriteStrategyTest`
  (assert correct target DN + value attribute + generated `Modification`s).
- **Controller:** `SchemaManagementControllerTest` — authz (403 without
  `MANAGE_SCHEMA`), preview→apply happy path, blocking-preview refusal,
  Entra/unsupported-vendor 422. Mirror
  `BrowseControllerLdifPreviewTest`.
- **Fixtures as inputs:** feed `testdata/isva-schema-openldap.ldif` and
  `testdata/isva-schema-opendj-inline.ldif` through preview to prove both
  dialects classify correctly.
- **Frontend:** Vitest for `schema.ts` + a `SchemaManageView` render/preview
  test, following existing `LdifImportModal` test conventions.
- Integration against the live `openldap-primary` / `oud1` compose fixtures is
  the acceptance gate (Testcontainers/Docker-gated, like
  `EventBackboneEndToEndTest`).

## 8. Deferred / out of scope (v1)

- Schema **deletes / destructive modifies** (online-unsafe on OpenLDAP;
  needs a separate, carefully-gated design).
- **AD** (Schema NC + Schema-Admins + Schema-Master FSMO; irreversible
  deactivation-only) and **ITDS**.
- **Persisted** `cn=config` bind on `DirectoryConnection` (option (B), §5.0) —
  v1 takes config creds per-request.
- Cross-directory schema **diff / propagation** and schema **templates**.
- OID-registry / private-arc management.

## 9. Risks & guardrails

- **Irreversibility.** Online schema adds are largely one-way. Mitigations:
  mandatory preview, `blocking` refusal, export-before-apply, `SCHEMA_UPDATE`
  audit.
- **Wrong bind / silent no-op.** OpenLDAP writes with the data bind fail or
  mislead. Mitigation: explicit config-connection path + a clear error when
  config creds are missing for OpenLDAP.
- **Dependency ordering.** An objectClass may reference an attributeType
  defined later in the same LDIF. Apply attributeTypes before objectClasses;
  surface `UNKNOWN_SUP`/missing-MUST/MAY in preview.
- **Partial application.** LDAP is non-transactional. Apply
  element-by-element, report per-element results in `SchemaUpdateResult`, and
  make the operation **idempotent** (skip elements already present) so a
  re-run after partial failure is safe.

## 10. Open questions

1. **Config-bind model** — confirm per-request creds (A) for v1 vs. persisting
   an encrypted config bind (B).
2. **Additive `modify` of existing elements** — include in v1 for OpenDJ, or
   restrict v1 to ADD_NEW only and defer all modifies?
3. **Export scope** — full subschema dump, or only the custom (non-core)
   elements?
