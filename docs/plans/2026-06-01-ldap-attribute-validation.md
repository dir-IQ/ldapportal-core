# LDAP attribute validation — implementation plan

- **Date:** 2026-06-01
- **Status:** In progress (much of workstreams A/C landed since drafting —
  `DnValidator`, profile-rule enforcement on the admin **create and update**
  paths, `editableOnUpdate`/`hidden` server gating, field-level error UI, and
  now the **server-side syntax layer** (`LdapAttributeValidator`: DN-format on
  DN-valued attributes, email, boolean) wired into the user create/update,
  group create/update, and bulk-attribute-update paths; the remaining
  numeric / generalized-time syntax, object-class `MUST` coverage, and the
  client-side syntax mirror remain. The `@Dn`/`@ValidRdn` DTO constraints were
  **intentionally not added** — see §2a. See §2a for the verified
  shipped-vs-remaining split, 2026-06-10).
- **Scope:** Consistent, layered validation of LDAP attribute values —
  presence, length, regex, allowed-values, type/syntax (DN, email,
  numeric, boolean, generalized-time), object-class `MUST` coverage,
  and edit-gating — enforced authoritatively in the **API** and mirrored
  for UX in the **UI**, across both the admin direct-entry forms and the
  self-service / bulk paths.
- **Audience:** Written to hand to a fresh Claude Code session in
  `ldapportal-core`. Self-contained; paths relative to repo root.
- **Branch:** the implementation should be cut fresh from `origin/main`
  per repo workflow; this doc is the design hand-off.

## 1. Goal

> "When an admin or end-user enters or edits directory data, malformed
> values are rejected with a clear, field-level message — the same rules
> on the client (instant feedback) and the server (authoritative), with
> no rule defined in two places."

Today validation is **uneven** (see §2). This plan unifies it so every
write path runs the same attribute-validation engine, adds the missing
type/syntax checks (notably DN format), and surfaces field errors in the
admin forms — not just self-service.

## 2. Current state (verified)

| Concern | Today | Gap |
|---|---|---|
| Profile rules engine (`required` / `minLength` / `maxLength` / `validationRegex` + `validationMessage` / `allowedValues`) | Implemented in `ProvisioningProfileService.validateAttributes()` (`core/.../service/ProvisioningProfileService.java:604-678`), ReDoS-guarded (`MAX_REGEX_INPUT_LENGTH`), regex compiled at config-save (`:814`) | **Only called from `SelfServiceService.java:318`.** The admin create/update path does not run it. |
| Admin create/update | `LdapOperationService.createUser/updateUser/createGroup/...` only run `permissionService.requireDnWithinScope()` (authorization, not syntax) | No attribute-rule validation; relies on the LDAP SDK rejecting bad values at write. |
| DN format | Validated up front only in `LdapBrowseService.createContainer()` via `new DN(dn)` (`:185-199`); constructed+escaped via `new RDN(...)` in self-service/bulk; otherwise rejected by UnboundID `conn.add` as `INVALID_DN_SYNTAX` at write | No central DN validator; everyday user/group create has no pre-write DN check. |
| DTOs | `@NotBlank` / `@NotEmpty` only (`CreateEntryRequest.dn`, `MoveUserRequest.newParentDn`, `RenameEntryRequest.newRdn`, `BulkImportRequest.parentDn`, …) | No `@Dn` / `@Email` / `@Pattern` constraints. |
| Edit-gating (`editableOnCreate` / `editableOnUpdate` / `hidden`) | UI-only (`UserForm.vue`, `HIDDEN_EDIT_ATTRS`) | Not enforced server-side; an API caller can set a non-editable/hidden attr. |
| UI admin forms | `UserForm.vue`, `GroupListView.vue`, `CreateEntryForm.vue`, `EditEntryForm.vue` use HTML `required` (from `requiredOnCreate`) + LDAP schema `required`/`optional` | No length/regex/format checks; no inline error display. |
| UI self-service | `validateFields()` in `RegisterView.vue` + `SelfServiceProfileView.vue` (required/min/max/regex) | Logic duplicated in two components; no DN/type checks. |
| Object-class schema | `LdapSchemaService.getAttributesForObjectClass()` returns `MUST`/`MAY`; used by bulk import + `CreateEntryForm` | Not used to enforce `MUST` on the single-entry admin create path. |
| App-account passwords | `AccountPasswordPolicy` (≥12 chars, 3+ classes, blocklist) | Separate concern — out of scope (not LDAP user data). |

## 2a. Status update — shipped vs remaining (verified 2026-06-10)

Several workstream items landed after this plan was drafted (the §2 table
above reflects the **2026-06-01 baseline**, not today). Verified by reading
the current code on `main`:

### Shipped ✅

| Item | Where | Notes |
|---|---|---|
| `DnValidator` utility (A1) | `core/.../ldap/validation/DnValidator.java` (`parse` / `requireValidDn` / `requireValidRdn`) | Wraps UnboundID `new DN`/`new RDN`; rejects empty / root-DSE; `ENTRA_ID`-aware. Called on the user **create** path (`LdapOperationService.createUser`). |
| Profile rules on admin **create** (A4) | `LdapOperationService.createUser` → `ProvisioningProfileService.validateAttributes()` | required / min / max / regex (ReDoS-guarded) / allowed-values. |
| Profile rules on admin **update** (A4) | `LdapOperationService.updateUser` → `ProvisioningProfileService.validateModification()` | length / regex / allowed-values on the **modified** attributes; `requiredOnCreate` intentionally **not** enforced on update. |
| `editableOnUpdate` / `hidden` server enforcement (A3) | `validateModification` → `assertEditable` | Rejects modifying a non-editable/hidden attribute; computed attrs exempt; **unconfigured attrs pass through**. |
| Error mapping (A6) | `GlobalExceptionHandler` | `IllegalArgumentException` → 400; `MethodArgumentNotValidException` → field-keyed 400 `ProblemDetail`. |
| Frontend field validation + inline errors (C1 partial, C3 for `UserForm`, C4) | `frontend/src/utils/attributeValidation.ts` `validateAttributeValue()`; `UserForm.vue` `validate()` with `FormField :error`; `UserListView.save()` gates on it | required (create-only) / min / max / regex + custom message. Edit mode skips the immutable RDN. |
| **Server-side syntax layer (A2.2, A4)** | `core/.../ldap/validation/{AttributeSyntax,WellKnownAttributes,LdapAttributeValidator}.java`; wired in `LdapOperationService` createUser / updateUser / createGroup / updateGroup / bulkUpdateAttributes | DN-format on DN-valued attrs (`manager`/`secretary`/`owner`/`seeAlso`/`roleOccupant`/`member`/`uniqueMember`, plus any profile `DN_LOOKUP` field), email on `mail`, boolean on `BOOLEAN` fields. Directory-type aware (Entra-exempt for DN); validates only values being written, so existing data is untouched. Field-keyed 400 via `IllegalArgumentException`. |

### Remaining ⏳ (the gaps this plan still tracks)

| Gap | Impact | Workstream |
|---|---|---|
| **DN-format on DN-valued attributes — _client mirror_** | Server side **shipped** (see §2a Shipped). The client still only ensures *a* value via `DnPicker`; a typed/pasted bad DN is caught server-side now but with no instant field feedback. | C2 |
| `@Dn` / `@ValidRdn` DTO constraints | **Intentionally not added.** A bean-validation constraint runs at the controller boundary and cannot see the directory type, so it cannot honour the Entra-ID DN exemption (principle #5). The directory-type-aware `DnValidator` in the service layer (already wired on create/move/rename/container) is the authoritative check; the new `AttributeSyntax` extends it to DN-valued *attribute values*. | A5 (closed by redesign) |
| Numeric / generalized-time syntax | `AttributeSyntax` covers DN / email / boolean; numeric and `DATE`/`DATETIME` generalized-time are deferred — the wire format the UI sends for date fields needs pinning down first to avoid false rejects. | A2.2 / C1 (type-aware) |
| Object-class `MUST` coverage on create | Missing-required-by-schema not caught pre-write (the directory server still rejects it at write, just with a rawer message). Deferred — backward-compat risk; plan §6 suggests gating behind a per-profile flag. | A2.3 |
| `editableOnCreate` server enforcement (create) | Deferred. Unlike the update path, by the time `createUser` runs the caller has already merged defaults / computed / `HIDDEN_FIXED` values into the attribute map, so server-injected and user-supplied values are indistinguishable here; correct enforcement belongs at the controller on the *raw* request. | A3 (create) |
| **Unprofiled OUs / unconfigured ("Other") attributes** | Server now applies **well-known** syntax (DN-valued attrs, `mail`) even when unprofiled; truly free-form attributes still pass through (by design). The **client** is still unrestricted for these. | A2 / C1 |
| Wiring beyond user create/update | group create/update + `bulkUpdateAttributes` **now wired** for syntax; move/rename carry only a DN/RDN (no attribute values) and are already `DnValidator`-checked. | A4 (done) |
| Client `validateDn` (C2); self-service dedupe onto the shared util (C5); wiring `GroupListView` / `CreateEntryForm` / `EditEntryForm` (C3) | **Unverified**; treat as remaining | C2 / C3 / C5 |
| `createContainer` refactor onto `DnValidator`; `editableOnCreate` server enforcement (A3, create) | **Unverified** | A1 / A3 |

**Net for the admin user-edit form today:** a *profiled* directory gets solid
length / regex / allowed-value / edit-gating enforcement (server-authoritative,
mirrored in the UI). **DN format on DN-valued fields, email/type syntax, and
`MUST` coverage are not checked**, and *unprofiled / "Other"* attributes are not
validated at all — malformed values slip through or are rejected by the
directory at write with a raw LDAP error rather than a clean field-level
message. Closing the **DN-on-DN-valued-attributes** gap (A2.2 + the `@Dn` DTO
constraints) is the highest-value remaining step.

## 3. Design principles

1. **Server is authoritative; UI mirrors.** The client validates for
   instant feedback only; every rule is re-checked server-side.
2. **Single source of rule definitions.** Configurable rules live on
   `ProfileAttributeConfig` (DB) and are already returned in
   `ProfileResponse` (`validationRegex`, `validationMessage`, `minLength`,
   `maxLength`, `allowedValues`, `inputType`). The UI consumes those
   fields rather than re-declaring rules. Schema `MUST`/`MAY` comes from
   the directory. Built-in **syntax** validators (DN, email, numeric,
   boolean, generalized-time) are derived from `inputType` + a small
   well-known-attribute map — not duplicated per profile.
3. **Reuse, don't rebuild.** Widen the reach of the existing
   `validateAttributes()` engine; add a thin syntax layer beside it.
   Build DN writes through the provisioning-plan SPI as today.
4. **Core stays vendor-agnostic.** All work lands in `core/`. Addons add
   extra rules through the existing `ProvisioningInterceptor` SPI if ever
   needed — no new core→addon coupling.
5. **Directory-type aware.** DN-syntax checks apply to OpenLDAP/AD/IBM
   DIT directories; Entra ID (no DN container model) is exempted, matching
   `createContainer`'s existing `ENTRA_ID` guard.
6. **Errors are 400, not 500.** Validation failures throw
   `IllegalArgumentException` (mapped by core `GlobalExceptionHandler` to a
   400 `ProblemDetail`); bean-validation failures map via
   `MethodArgumentNotValidException`. Avoid `ResponseStatusException`.

## 4. Workstreams

### A. Backend — centralize + wire the engine

- **A1. `DnValidator` utility** (`core/.../ldap/validation/DnValidator.java`).
  Wrap UnboundID `new DN(dn)` / `new RDN(attr, value)`; throw
  `IllegalArgumentException("Invalid DN: …")` on `LDAPException`; reject
  null-DN / zero-RDN. Directory-type aware (skip for `ENTRA_ID`). One home
  for the logic currently inline in `createContainer`; refactor
  `createContainer` to call it.
- **A2. `LdapAttributeValidator`** (new component, or a widened facade over
  `ProvisioningProfileService`). Validates a
  `Map<String,List<String>>` against, in order:
  1. profile configs — reuse `validateAttributes(profileId, attrs)`
     (required/min/max/regex/allowed);
  2. **syntax by `inputType` / well-known attribute** — `DN_LOOKUP`/known
     DN-valued attrs → `DnValidator`; `mail`/email type → RFC-ish email;
     numeric/`DATE`/`DATETIME` → generalized-time / integer; `BOOLEAN` →
     `TRUE`/`FALSE`;
  3. **schema `MUST`** (create only) — via
     `LdapSchemaService.getAttributesForObjectClass()` across the entry's
     `objectClass` set.
  Keep the existing `MAX_REGEX_INPUT_LENGTH` ReDoS guard.
- **A3. `editable` / `hidden` enforcement.** On update, reject modifying
  an attribute whose config is `editableOnUpdate=false` or `hidden=true`
  (unless it has a `computedExpression`); on create honor
  `editableOnCreate`. Mirrors the UI `HIDDEN_EDIT_ATTRS` list.
- **A4. Wire into `LdapOperationService`** create/update/group/move/rename/
  bulk paths (`createUser` `:220`, `updateUser` `:234`, `createGroup`
  `:435`, `addGroupMember` `:455`, move/rename, `bulkUpdateAttributes`
  `:262`). Resolve `profileId` (the controller already resolves it for
  `createUser`) and call `LdapAttributeValidator` **before** building the
  plan. On update, validate only the modified attributes; pass through
  attributes with no profile config but still apply syntax checks (DN,
  email) where the attribute is well-known.
- **A5. Bean-validation constraints on DTOs.** Add `@Dn` (custom
  `ConstraintValidator` over `DnValidator`) on `CreateEntryRequest.dn`,
  `MoveUserRequest.newParentDn`, `BaseDnRequest.dn`,
  `BulkImportRequest.parentDn`; add `@ValidRdn` on
  `RenameEntryRequest.newRdn`. Keep `@NotBlank`. These give a fast 400 at
  the controller boundary before service logic.
- **A6. Error mapping.** Confirm `GlobalExceptionHandler` maps
  `MethodArgumentNotValidException` to a field-keyed 400 `ProblemDetail`;
  add a handler if missing so the UI can render per-field messages.

### B. Backend — expose rules to the admin UI

- **B1.** Confirm the admin user/group template endpoint returns the full
  attribute config (it already powers `UserForm.vue`'s
  `userTemplateConfig.attributeConfigs`, and `ProfileResponse` carries
  `validationRegex`/`validationMessage`/`minLength`/`maxLength`/
  `allowedValues`/`inputType`). No new fields expected; add any missing to
  the admin DTO mapping.
- **B2.** Reuse the existing object-class schema endpoint
  (`browseObjectClasses` / `browseObjectClassesBulk`, already consumed by
  `CreateEntryForm`) so the UI knows `MUST`/`MAY` and (optionally) which
  attributes are DN-syntax for client-side format hints.

### C. Frontend — shared validation

- **C1.** Extract a reusable composable/util in `frontend/src/utils/` (TS):
  `validateAttributeValue(config, value)` and
  `useAttributeValidation(configs)` returning a per-field error map —
  generalizing the existing self-service `validateFields()`
  (required/min/max/regex) and adding type-aware checks (DN, email,
  numeric, boolean) that match the server.
- **C2.** `validateDn(value)` lightweight RFC-4514-ish client check (for
  instant feedback only; server is authoritative).
- **C3.** Wire into the admin forms — `UserForm.vue`, `GroupListView.vue`,
  `CreateEntryForm.vue`, `EditEntryForm.vue`: block submit, show inline
  errors. Per CLAUDE.md, convert any touched `<script setup>` to
  `lang="ts"` (typed refs, form interface, error-narrowing in `catch`),
  and rename touched `*.spec.js` → `*.spec.ts`.
- **C4.** `FormField.vue`: add an `:error` prop + message/red-border state
  using project utility classes (no hand-rolled Tailwind controls);
  surface field errors consistently.
- **C5.** Replace the duplicated `validateFields()` in `RegisterView.vue`
  and `SelfServiceProfileView.vue` with the shared util (dedupe).
- **C6.** Server-side rejections continue to publish through the Pinia
  notification store; field-keyed 400s map back onto the corresponding
  `FormField` errors.

### D. Tests

- **Backend:** unit tests for `DnValidator` and `LdapAttributeValidator`
  (required, too-short/long, regex pass/fail + custom message, allowed
  values, malformed DN, bad email, missing `MUST`, non-editable/hidden on
  update). MockMvc on `UserController`/`GroupController` asserting 400
  `ProblemDetail` shape + field key for each case, and that valid requests
  still succeed. Regression: self-service path behavior unchanged.
- **Frontend:** Vitest for `validateAttributeValue` + composable; specs for
  `UserForm`/`GroupListView`/`CreateEntryForm` asserting inline errors and
  blocked submit; ensure Pinia is active where notifications are used.

### E. Schema / migration

- **None expected.** `ProfileAttributeConfig` already has the rule columns.
  A new migration is only needed if we add an explicit per-attribute
  `syntax` enum (`db/migration/core`); the plan instead derives syntax from
  `inputType` + a well-known-attribute map, avoiding a migration.

## 5. Sequencing (small, shippable steps)

Markers reflect the 2026-06-10 status (see §2a): ✅ done · ◑ partial · ⏳ remaining.

1. ✅ `DnValidator` + `createContainer`/`renameEntry` already on it. The
   `@Dn`/`@ValidRdn` DTO-constraint idea is **closed by redesign**: a boundary
   bean-constraint can't honour the Entra exemption, so the directory-type-aware
   `DnValidator` (service layer) + the new `AttributeSyntax` (DN-valued attribute
   values) are the authoritative DN checks.
2. ◑ Profile-rule reach into admin create/update ✅; the **syntax** layer —
   DN-valued attrs / email / boolean ✅ (`LdapAttributeValidator`, wired into
   user create/update + group create/update + bulk); numeric / generalized-time
   + schema `MUST` ⏳.
3. ◑ `editable`/`hidden` server enforcement — update ✅; create
   (`editableOnCreate`) ⏳ (deferred — needs controller-level raw-request gating;
   see §2a Remaining).
4. ◑ Frontend shared util ✅ + `FormField` error state ✅ + `UserForm` wired ✅;
   DN/type client checks + `GroupListView`/`CreateEntryForm`/`EditEntryForm` ⏳.
5. ⏳ Dedupe self-service onto the shared util (unverified).
6. ⏳ Operator doc note + flip this **Status** to *Shipped* once the syntax
   layer + `@Dn` DTO constraints land.

## 6. Risks & decisions

- **Backward compatibility.** Tightening validation can reject requests
  that previously succeeded (e.g. existing looser data on update). Mitigate
  by validating only *modified* attributes on update and only *configured*
  rules + syntax; consider gating strict object-class `MUST` enforcement on
  create behind a per-profile/setting flag if real directories carry
  exceptions. **Decision needed at implementation time.**
- **Vendor differences.** AD/Entra DN and attribute semantics differ
  (`sAMAccountName`, object IDs, no DN container model for Entra). Keep
  DN-syntax checks directory-type-aware (reuse the `ENTRA_ID` guard).
- **ReDoS.** Preserve the existing input-length cap and
  `PatternSyntaxException` handling when moving regex into the shared
  validator.
- **Rule duplication.** Do not hard-code attribute rules in the UI — drive
  the admin forms from the same profile-config payload the server validates
  against, so the two layers cannot drift.
