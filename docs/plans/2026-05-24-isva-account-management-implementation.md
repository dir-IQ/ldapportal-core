# IVIA account management — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Product:** IBM Verify Identity Access (IVIA). **Internal codename:** `isva`
(stable — package, `vendor_integration_isva_config` table,
`VENDOR_INTEGRATIONS_ISVA` entitlement). UI strings use
`frontend/src/constants/productNames.ts`.

**Goal:** Give an IVIA-focused admin (1) a first-class, typed view of an
identity's IVIA account state and (2) account-scoped verbs on an *existing*
identity — grant, revoke, suspend/restore, renew, force-credential-reset —
decoupled from demographic create/delete. Every verb is a small plan run
through the existing `PlanExecutor`.

**Spec:**
[`../specs/2026-05-24-isva-account-management-design.md`](../specs/2026-05-24-isva-account-management-design.md)
*(if the spec lives elsewhere in your tree, fix the link before this plan
lands — the file isn't currently at the path above)*

**Mockups:** see [P3 → Mockups](#p3-mockups) for two standalone HTML files
checked in alongside this plan.

**Decisions locked in the spec (do not re-litigate):**

- **Directory-scoped admin authz** (not superadmin), `@RequiresFeature` +
  `requireDnWithinScope` + `@Entitled(VENDOR_INTEGRATIONS_ISVA)`. These are
  daily admin operations.
- **Grant refused** when the resolved profile is `FORCE_OFF` or the directory
  is disabled; **revoke/suspend/renew/reset always allowed** (narrowing-safe).
  The override stays the single source of truth; `SCOPE_DRIFT` stays meaningful.
- **Every verb re-verifies + idempotency policy is per-verb** (see P1 table).
- **No new tables (P1–P3), no new `AuditAction`** — state lives in LDAP (read
  via `IsvaUserReadEnricher`); audit reuses `USER_*` with an `ivia` detail
  discriminator. (P4 adds four nullable columns to the existing
  `vendor_integration_isva_config` table.)
- **Orchestration only** — reuse `IsvaSecUserPlans` + `PlanExecutor`; no new
  write machinery.

**Depends on / shares:** the `IsvaSecUserPlans` extraction (promoting
`grantSecUser` / `revokeSecUser` off `IsvaProvisioningInterceptor`) is **shared
with**
[`2026-05-24-isva-integrity-reconciliation.md`](2026-05-24-isva-integrity-reconciliation.md).
Whichever feature lands first does the base extraction (P0 there / P0 here);
the second builds on the already-extracted component. Also depends on the
merged IVIA full-mode integration (`IsvaUserReadEnricher`, `IsvaLinkedUserLookup`,
`VendorIntegrationIsvaConfig`, `PlanExecutor`) and the profile-scoped override
(`IsvaProfileOverrideService`).

**Sequencing:** P0 (done) → P1 → P2 → P3 → P4 → P5. P1 is the heart (the
service + guards); P2 wires REST; P3 is the UI; P4 adds the cert-mapping
attribute; P5 is docs.

**Tech stack:** Java 21, Spring Boot 3.5, UnboundID 7 (backend); Vue 3.5
`<script setup lang="ts">`, Tailwind 4, Pinia, Vitest (frontend); Playwright
(`@smoke`); Maven multi-module. CI uses runner `mvn` (build cache disabled).

**Commit cadence:** one PR per phase. P0 has already landed, so the remaining
phases are P1–P5 = **5 PRs**.

---

## Phase summary

| Phase | Topic | Files | Tests | Status |
|---|---|---|---|---|
| P0 | Extract + extend `IsvaSecUserPlans` | ~2 addon (interceptor refactor + new component) | unit + interceptor regression | **Done** |
| P1 | `IsvaAccountService` (verbs + status + guards + audit) | ~3 addon (service + topology probe + status DTO companion) | unit/integration | |
| P2 | REST controller + `IsvaAccountStatusDto` + authz | ~2 addon | MockMvc | |
| P3 | UI: account panel + list orphan chip | ~3 frontend + 1 Playwright `@smoke` | smoke + Vitest | |
| P4 | Cert-mapping identity attribute (attribute-only, §8 of the design) | ~2 addon + migration + ~2 frontend | config MockMvc + verb unit + Vitest | |
| P5 | Docs + boundary + roadmap | docs only | — | |

---

## P0 — Extract + extend `IsvaSecUserPlans` *(done — recap only)*

Captured here for context. Both items are complete; the interceptor's
27-test regression suite stayed green through the extraction.

- [x] **Already extracted** by the integrity feature: `IsvaSecUserPlans`
      is the injectable home for every `secUser` plan fragment. The
      interceptor depends on and delegates to it; `generalizedTime` is
      a public static there.
- [x] **Extended** with account verbs: `suspend` / `restore`
      (`secAcctValid` toggle), `renew` (`secValidUntil` extend),
      `forceCredentialReset` (`secPwdValid=FALSE`), and
      `grantInlineOnExisting` (MODIFY adding `objectClass: secUser` +
      `sec*` to an existing demographic entry — the one new fragment).
      `secDefaults()` is the single source of the shared `sec*` values.
      13 tests in `IsvaSecUserPlansTest`.

**Note on inline-mode `secUUID`:** `secDefaults()` does **not** include
`secUUID` — linked mode synthesizes it as the RDN value; inline mode
keeps the demographic RDN and skips it. Document this choice in the
spec; if downstream IVIA tooling needs `secUUID` on inline entries,
that's a follow-up that touches the create path too.

## P1 — `IsvaAccountService`

### Service surface

- [ ] New addon service with `getStatus(dir, dn)` + the verbs `grant`,
      `revoke(mode)`, `suspend`, `restore`, `renew(validUntil)`,
      `forceCredentialReset`. Each: load active
      `VendorIntegrationIsvaConfig` (4xx if IVIA not enabled),
      **re-verify** current state via the new topology-aware probe
      (see below), build the plan via `IsvaSecUserPlans`, run via
      `PlanExecutor`, audit.

### Topology-aware existence check *(was a gap in v1)*

`IsvaLinkedUserLookup.findSecUserDn` searches the management DIT and
only finds linked-mode pairs. In inline mode the `secUser` objectClass
lives on the demographic entry itself, so the lookup returns empty
even when the account is "linked." Without a topology-aware check, a
grant on an already-granted inline entry would pass the re-verify
guard, hit LDAP, and trip `ATTRIBUTE_OR_VALUE_EXISTS` on
`MODIFY ADD objectClass: secUser` — surfacing as a 500.

- [ ] New `IsvaAccountStatusProbe` (or a method on `IsvaSecUserPlans`)
      that returns a `Topology` + presence:
   - `mode == LINKED` → call `findSecUserDn`. Present iff the lookup
     returns a DN.
   - `mode == INLINE` → fetch the demographic entry's `objectClass`
     attribute. Present iff `secUser` is among the values.
- [ ] Every verb routes its existence check through the probe; the
      verb then builds the plan against the right target DN
      (linked → secUser DN, inline → demographic DN).

### Guards

- [ ] Grant when already present → refuse 409
      (`code: ivia_already_linked`).
- [ ] Revoke/suspend/renew/reset when orphaned (no IVIA account) →
      refuse 409 (`code: ivia_orphan`, message points at integrity
      reconcile).
- [ ] Grant when resolved profile is `FORCE_OFF` → refuse 409
      (`code: ivia_force_off`, message says flip the profile first).
- [ ] Grant when directory IVIA disabled → refuse 409
      (`code: ivia_directory_disabled`).

### Idempotency policy *(clarified vs v1)*

The wording "every verb is idempotent" was conflated with refusal.
Per-verb policy:

| Verb | Repeat behaviour |
|---|---|
| `grant` (already linked) | **Refuse 409** — refusing a redundant grant is a deliberate choice, not idempotency |
| `revoke` (already orphaned) | **Refuse 409** — points at integrity reconcile |
| `suspend` (already suspended) | **No-op 200** — idempotent |
| `restore` (already active) | **No-op 200** — idempotent |
| `renew` (new date earlier than current) | **Refuse 400** — server clamps to "must extend forward" |
| `renew` (new date == current) | **No-op 200** |
| `forceCredentialReset` (already invalid) | **No-op 200** — idempotent |

### TOCTOU under concurrent operators *(new)*

The verb does `probe → plan → executor`. Another admin doing the
opposite verb between probe and executor commit is possible. Map known
LDAP errors so users see 409 ("state changed; refresh") instead of a
500:

- `NO_SUCH_OBJECT` on revoke/suspend/etc → 409 + refresh hint
- `ATTRIBUTE_OR_VALUE_EXISTS` on grant → 409 + refresh hint
- Other `LDAPException` → bubble through GlobalExceptionHandler

### Audit *(table added vs v1)*

Reuse existing `AuditAction` values; discriminate in `detail.source =
"ivia"` (matches the existing per-source convention from the profile
work — e.g. `source: profile_create`). Include a per-verb `ivia_op`
key for filtering.

| Verb | AuditAction | `detail` keys |
|---|---|---|
| `grant` | `USER_UPDATE` | `source: ivia`, `ivia_op: grant`, `mode: inline\|linked`, `secUserDn: …` (linked) |
| `revoke(SOFT)` | `USER_DISABLE` | `source: ivia`, `ivia_op: revoke_soft` |
| `revoke(HARD)` | `USER_DELETE` *(see note)* | `source: ivia`, `ivia_op: revoke_hard`, `mode` |
| `suspend` | `USER_DISABLE` | `source: ivia`, `ivia_op: suspend` |
| `restore` | `USER_ENABLE` | `source: ivia`, `ivia_op: restore` |
| `renew` | `USER_UPDATE` | `source: ivia`, `ivia_op: renew`, `validUntil` |
| `forceCredentialReset` | `PASSWORD_RESET` | `source: ivia`, `ivia_op: force_reset` |
| `certMapping set/clear` (P4) | `USER_UPDATE` | `source: ivia`, `ivia_op: cert_mapping_set\|cert_mapping_clear` |

`USER_DELETE` doesn't currently exist; the integrity / lifecycle work
may add it. If it lands first, revoke-hard uses it; otherwise revoke-
hard rides on `USER_DISABLE` with `ivia_op: revoke_hard` and a note
in the spec.

### Tests

- [ ] Unit/integration: each verb happy-path on each topology.
- [ ] FORCE_OFF grant refusal + directory-disabled grant refusal.
- [ ] Orphan-guard refusal for each "always-allowed" verb.
- [ ] Idempotency per the table above (double-grant 409, suspend-
      when-suspended 200, etc.).
- [ ] TOCTOU translation: NO_SUCH_OBJECT → 409, ATTRIBUTE_OR_VALUE_EXISTS
      → 409.
- [ ] Inline vs linked topology probe.

**Exit:** every verb is a guarded, audited operation over the executor;
idempotency policy is explicit per verb; TOCTOU resolves to 409 not 500.

## P2 — REST + DTO

### URL shape *(changed vs v1)*

DNs contain `=`, `,`, `+` — all reserved in URL paths. Take the DN
as a query parameter, not a path segment, matching how other addon
endpoints already address LDAP entries:

```
GET    /api/v1/directories/{dirId}/isva-account?dn={dn}
POST   /api/v1/directories/{dirId}/isva-account/grant?dn={dn}
POST   /api/v1/directories/{dirId}/isva-account/revoke?dn={dn}
POST   /api/v1/directories/{dirId}/isva-account/suspend?dn={dn}
POST   /api/v1/directories/{dirId}/isva-account/restore?dn={dn}
POST   /api/v1/directories/{dirId}/isva-account/renew?dn={dn}
POST   /api/v1/directories/{dirId}/isva-account/force-credential-reset?dn={dn}
PUT    /api/v1/directories/{dirId}/isva-account/cert-mapping?dn={dn}        (P4)
DELETE /api/v1/directories/{dirId}/isva-account/cert-mapping?dn={dn}        (P4)
```

The frontend client `encodeURIComponent`s the DN; controller signs `dn`
as `@RequestParam String dn`.

### Authz — feature-key per verb *(was unpinned in v1)*

Class-level `@Entitled(VENDOR_INTEGRATIONS_ISVA)`. Per-method
`@RequiresFeature` + `requireDnWithinScope`:

| Verb | `FeatureKey` |
|---|---|
| `getStatus` | `USER_READ` |
| `grant`, `revoke`, `suspend`, `restore` | `USER_ENABLE_DISABLE` |
| `renew` | `USER_EDIT` |
| `forceCredentialReset` | `USER_RESET_PASSWORD` |
| `certMapping set/clear` (P4) | `USER_EDIT` |

Rationale: the verbs mirror demographic user lifecycle ops, so reuse
the corresponding feature keys. An IVIA-specific key
(`VENDOR_ACCOUNT_MANAGE`) would be cleaner but means a new
`FeatureKey` enum value + a permissions UI cell — out of scope for
this phase.

### DTOs *(constraints pinned vs v1)*

- [ ] `IsvaAccountStatusDto` — typed projection:
   - `linked: boolean`, `orphaned: boolean`, `topology: "INLINE" | "LINKED"`
   - `acctValid: boolean`, `validUntil: OffsetDateTime?`,
     `daysRemaining: int?`
   - `pwdValid: boolean`, `pwdLastChanged: OffsetDateTime?`
   - `authority: String?`, `secUserDn: String?` (linked only)
   - `certMappingPresent: boolean?`, `certMappingValue: String?` (P4)
- [ ] `RenewRequest { validUntil: OffsetDateTime }`:
   - `@NotNull validUntil`
   - server-side: must be `> now`; cap at `now + 10 years`. Refuse
     400 if the new date is earlier than the current `secValidUntil`
     (idempotency policy table).
- [ ] `RevokeRequest { mode: "SOFT" | "HARD" }`.

### ProblemDetail shape for refusals *(new)*

The frontend will dispatch on a `code` field to show contextual CTAs
(e.g., "Flip the profile override → and retry" for FORCE_OFF). Augment
the standard `ProblemDetail` body with a `code` property:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Grant refused: this directory's IVIA integration is...",
  "code": "ivia_directory_disabled"
}
```

Codes:

| `code` | When |
|---|---|
| `ivia_already_linked` | grant on already-present account |
| `ivia_orphan` | revoke/suspend/renew/reset with no IVIA account |
| `ivia_force_off` | grant refused — profile resolves to FORCE_OFF |
| `ivia_directory_disabled` | grant refused — directory IVIA disabled |
| `ivia_renew_not_forward` | renew with new date ≤ current `secValidUntil` |
| `ivia_state_changed` | TOCTOU — refresh and retry |

`code` is part of the public API surface; documented in P5.

### Tests

- [ ] MockMvc: authz (entitlement + directory scope + each FeatureKey
      gating).
- [ ] Status shape (linked + orphaned + inline + linked topology).
- [ ] Each verb happy-path; each `code` returned for the matching
      refusal path.
- [ ] Community / non-addon build returns 403 on every endpoint.

**Exit:** the verbs + status are callable; community/non-addon builds 403;
refusals carry machine-readable `code`.

## P3 — UI

<a id="p3-mockups"></a>

### Mockups

Two standalone HTML mockups live alongside this plan. They load
Tailwind via CDN — no build step. Open in any browser.

| File | What it shows |
|---|---|
| [`2026-05-24-isva-account-management-panel-mockup.html`](2026-05-24-isva-account-management-panel-mockup.html) | The IVIA Account panel in **nine states**, stacked top-to-bottom so they're easy to compare side-by-side: Linked+Active · Orphaned · Not provisioned · Grant refused (FORCE_OFF) · Linked+Suspended · Renew dialog · HARD-revoke confirm · Inline mode with cert mapping set · User-list orphan chip preview |
| [`2026-05-24-isva-account-management-user-detail-mockup.html`](2026-05-24-isva-account-management-user-detail-mockup.html) | Full **user-detail page** with the IVIA panel in context: sidebar + breadcrumb + page header (avatar, name, badges, primary actions), three stacked cards in the main column (IVIA Account · Demographic attributes split into Identity / Contact / Organization / Account & Lifecycle / Custom fieldsets · Group memberships table with Source badges and DIT column), and a right rail with At-a-glance / IVIA lifecycle timeline / Profile & scope / Recent activity |

**Fidelity caveats** (also noted in the mockups' footers):

- Styling approximates Tailwind 4 defaults via CDN. Production uses the
  project's `.btn-primary` / `.input` / fieldset utility classes (six
  state layers including Compact density) per
  `docs/frontend-conventions.md` — a new utility needs all six layers.
- Icons are inline Heroicons; the project may standardize on a
  different set.
- All identities, DNs, emails, dates are fictional.

**Use the mockups as the source of truth for layout decisions**, not
the prose below — when they disagree, the mockup wins. Things the
mockups already pin that the prose previously left vague:

- Tab order on the user-detail view: Overview · Attributes · Groups ·
  IVIA · Audit · Approvals.
- Right-rail card order and content shape.
- Group-membership table columns: checkbox · group + DN · Source ·
  DIT · Added · remove (the IVIA-side row is tinted).
- IVIA lifecycle timeline shape (granted → reset → renewed → will
  expire).

### Gating *(per-directory check pinned vs v1)*

The panel shows iff **both** apply:

1. `auth.isIsvaIntegrationEnabled` (addon is on the classpath).
2. The current directory has IVIA enabled. Reuse the
   `IsvaProfileOverrideControl.load()` pattern: call
   `getIsvaConfig(directoryId)`; treat 404 or `enabled: false` as
   "hide." Cache per directory for the lifetime of the user-detail
   view.

The panel itself self-gates — the user-detail view can mount it
unconditionally and it renders nothing when not applicable.

### Panel content

- [ ] "IVIA Account" panel on the user detail view: state badge
      (Linked / Orphaned / Not provisioned), Active / Suspended,
      `validUntil` + days-remaining, `pwdValid` + age, authority.
- [ ] Action buttons — disabled state mirrors the idempotency table:
   - **Grant** — visible only when not linked. Tooltip: refusal
     reasons (FORCE_OFF, directory disabled) when the disabled
     state derives from one of those `code`s.
   - **Revoke** — mode picker (SOFT / HARD). HARD requires a double-
     confirm dialog and the typed account name (existing
     `ConfirmDialog` pattern).
   - **Suspend / Restore** — mutually exclusive, shown by current
     state.
   - **Renew** — datepicker, defaulted to `now + cfg.defaultValidUntilYears`,
     capped at `now + 10y`.
   - **Force credential reset** — see UX note below.
- [ ] Copy uses `IVIA_NAME` / `IVIA_ABBR`.

### Force-credential-reset UX note *(new)*

The verb writes `secPwdValid=FALSE`. The visible effect on the end
user's next sign-in differs by topology:

- **Linked**: secUser entry's bind path is invalidated. The user
  can't authenticate via the IVIA bind until an operator (or self-
  service) re-sets the secUser password.
- **Inline**: same entry holds both demographic `userPassword` and
  `sec*`. The flag invalidates IVIA's authentication, not the plain
  LDAP bind. If your directory binds users via plain LDAP and only
  uses IVIA for authorization, the next sign-in still succeeds at
  the bind layer but IVIA may refuse.

Reflect both shapes in the button tooltip / confirm dialog copy so
the operator's expectations match the directory's mode.

### Error rendering

- [ ] Map ProblemDetail `code` to inline help in the panel:
   - `ivia_force_off` → "This profile is exempt from IVIA. Flip the
     override under Profiles → [profile name] → Policy → IVIA
     Integration." Link the profile.
   - `ivia_directory_disabled` → "IVIA is disabled on this directory.
     Enable it under Directory → IVIA Integration." Link the config
     page.
   - `ivia_state_changed` → "Another change landed while you were
     viewing this page; refresh and retry." Refresh action.
   - Others → toast via `useNotificationStore().error(detail)`.

### List integration

- [ ] User-list `isva.orphaned` warning chip (data already on the row
      via `IsvaUserReadEnricher`).

### API client + types

- [ ] API client methods; Pinia/store wiring as needed. Convert any
      touched plain `<script setup>` `.vue` to `lang="ts"` per
      CLAUDE.md.

### Tests

- [ ] Vitest where the panel has logic (mostly state-derived disabled
      buttons + the code-to-help mapping).
- [ ] Playwright `@smoke`:
   - **grant → status reflects linked**
   - **suspend → status reflects suspended; restore round-trips**
   - **grant on a FORCE_OFF profile shows the inline "flip the
     override" help with the link** — this is the security-relevant
     case from the spec and warrants smoke coverage.

**Exit:** an admin can see and manage an identity's IVIA account from
the user detail; hidden on community / non-addon builds; refusal codes
render as actionable help.

## P4 — Cert-mapping identity attribute (attribute-only)

Scope is the **attribute-based** cert-mapping model only (design §8).
We write a configurable per-entry attribute; the WebSEAL cert→user
mapping itself is not ours to own.

### What's deferred *(pinned vs v1)*

Fingerprint formats (`SHA1_FINGERPRINT`, `SHA256_FINGERPRINT`) are
common in WebSEAL configurations. They're out of scope for this
iteration — adding them is a config-page + verb change once we have
evidence of a customer that needs them. The `cert_value_format` enum
is forward-compat: adding a new value later is a non-breaking
migration.

### Schema migration

- [ ] **New addon Flyway migration `V502__isva_cert_mapping.sql`**:
      add `cert_mapping_enabled` (bool, default false),
      `cert_mapping_model` (enum, default/pinned `ATTRIBUTE` —
      forward-compat seam, no UI), `cert_identity_attribute`
      (varchar 128), `cert_value_format` (enum
      `BINARY_DER`/`SUBJECT_DN`) to `vendor_integration_isva_config`.
      All four nullable/defaulted; existing rows unaffected.

### DTO + validation

- [ ] Mirror on `IsvaConfigDto` / `UpsertIsvaConfigRequest`;
      controller requires `cert_identity_attribute` +
      `cert_value_format` non-null when `cert_mapping_enabled`.
- [ ] Validate `cert_identity_attribute` is an LDAP attribute name
      shape (`[A-Za-z][A-Za-z0-9-]*(;[A-Za-z][A-Za-z0-9-]*)?`) to
      accept descriptors like `userCertificate;binary`.

### Config page

- [ ] New "Certificate mapping" `<section>` on `IsvaConfigView`
      (rendered when `enabled`, after "Common settings", not in the
      linked-only block): enable checkbox, attribute name
      (`FormField`, default `userCertificate;binary`), value-format
      radio (Binary DER / Subject DN), boundary note that WebSEAL
      owns the actual cert→user mapping.

### Schema probe *(softened vs v1)*

- [ ] **Best-effort** schema probe — attempt to look up the
      configured attribute in the `secUser` schema. If the bind
      lacks subschema read access (common with locked-down IVIA
      service accounts), surface a non-blocking warning
      ("couldn't verify the attribute exists in the schema; the
      directory bind may not have subschema read access") and let
      the operator save anyway. Hard-failing here would lock out
      legitimate deployments.

### Verb

- [ ] Extend `IsvaSecUserPlans` with `setCertIdentity` (MODIFY
      REPLACE; binary→DER bytes, subject-DN→string) and
      `clearCertIdentity` (MODIFY DELETE).
- [ ] `IsvaAccountService.setCertMapping` / `clearCertMapping`
      (requires `cert_mapping_enabled`; orphan guard; audit
      `USER_UPDATE` with `{source: ivia, ivia_op:
      cert_mapping_set|cert_mapping_clear}`). Target entry follows
      `sec*` (secUser in linked, demographic in inline).

### REST

- [ ] `PUT` / `DELETE …/isva-account/cert-mapping?dn={dn}`; add
      `certMappingPresent` / `certMappingValue` to
      `IsvaAccountStatusDto`.
- [ ] ProblemDetail codes: `ivia_cert_mapping_disabled` when
      `cert_mapping_enabled=false`.

### UI

- [ ] In the IVIA Account panel, show mapping identity present /
      not-set with Set (upload for binary / paste for subject-DN per
      format) + Clear, gated on enablement + state.

### Tests

- [ ] Config controller / MockMvc (validation, probe pass + soft-fail
      paths).
- [ ] Verb unit tests (set / clear, orphan refusal, both formats).
- [ ] Vitest for the panel + config section.

**Exit:** an operator can configure the cert-mapping attribute per
directory, and an admin can set / clear it on an IVIA account.

## P5 — Docs + boundary + roadmap

- [ ] `docs/isva-integration.md`: document the account panel + verbs +
      the FORCE_OFF-grant-refusal rule + the cert-mapping section
      (attribute-only, WebSEAL owns the mapping) + the ProblemDetail
      `code` vocabulary (it's part of the public API surface).
- [ ] `docs/edition-boundary.md`: note the account ops live in the
      addon (core stays IVIA-agnostic), directory-scoped admin authz.
- [ ] `docs/enterprise-roadmap.md`: status row + section per the
      CLAUDE.md status-tracking convention.

**Exit:** docs reflect the feature; boundary + roadmap current.

---

## Cross-phase conventions *(consolidated)*

- **Audit `detail` discriminator naming.** Snake_case for keys and
  values, matching the existing convention in the profile-fix work
  (`source: profile_create`, `op: remove_profile_role`). So:
  `source: ivia`, `ivia_op: grant`. Not camelCase
  (`certMappingSet`).
- **Detail-map keys to always include where applicable:**
  `accountId`, `userDn`, `source`, `ivia_op`, `mode`,
  `secUserDn` (linked).
- **ProblemDetail `code` vocabulary.** All `ivia_*` codes are part
  of the public API; document them in `docs/isva-integration.md`
  (P5). Adding a new code is non-breaking; renaming an existing one
  is breaking.
- **DN-bearing endpoints take `dn` as a query parameter.** Never as
  a path segment.

---

## Risks / watch-items

- **Shared `IsvaSecUserPlans` extraction** — already done (P0). If
  the integrity feature lands a follow-up extraction that touches
  the same surface, coordinate to avoid drift.
- **Topology-aware existence check.** The two failure shapes that
  motivated the P1 probe (linked-mode lookup vs inline-mode
  objectClass introspection) are different LDAP operations. The
  probe is one extra LDAP roundtrip per verb; acceptable for
  single-identity operations, not for bulk.
- **TOCTOU under concurrent operators.** Handled by mapping
  known LDAP error codes to 409 + refresh hint; if a directory
  exposes other vendor-specific failure modes, extend the map.
- **Re-verify cost.** Each verb does one probe + one plan. Acceptable
  for single-identity operations; **don't reuse this path for bulk**
  — that's a deferred phase with a batched lookup.
- **Inline-grant-on-existing** is the only new write shape — make
  sure it's idempotent at the service level (refused by the probe
  when secUser objectClass is already present) and mirrors the
  create-time `sec*` defaults.
- **`secUUID` is intentionally omitted from inline grants.** The
  demographic RDN carries identity; secUUID isn't required by the
  inline overlay. If downstream IVIA tooling assumes it, that's a
  follow-up.
- **FORCE_OFF invariant** — keep grant-refusal consistent with the
  interceptor and the integrity `SCOPE_DRIFT` definition; a test
  should pin that a manual grant can't create the drift the scan
  would later flag.
- **Cert-mapping schema probe** is best-effort. A locked-down IVIA
  service account that can't read the subschema must not block
  configuration.
- **Mockup fidelity caveat.** The two HTML mockups in P3 use Tailwind
  via CDN and approximate the project's design system; production
  must use the project's utility classes per
  `docs/frontend-conventions.md`. When the implementation diverges
  from the mockup (e.g., for compact-density support), the
  conventions doc wins.
