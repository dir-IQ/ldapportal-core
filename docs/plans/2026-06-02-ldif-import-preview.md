# LDIF import preview — implementation plan

- **Date:** 2026-06-02
- **Status:** Shipped (v1 backend + frontend preview flow; deferred tiers in §7 remain, 2026-06-03).
- **Scope:** Replace the count-only "dry run" of LDIF import with a real
  **preview** — a per-entry view of what *would* change (add / modify /
  delete / skip), with conflict-against-directory detection, DN-syntax and
  in-scope warnings, and group **member deltas** — designed to stay usable
  at **2–5K entries × 15–30 attributes**. Superadmin-only; `core` only
  (vendor-agnostic, no addon).
- **Audience:** Written to hand to a fresh Claude Code session in
  `ldapportal-core`. Self-contained; paths relative to repo root.
- **Branch:** `feat/ldif-import-preview`, cut fresh from `origin/main`.

## 1. Goal

> "Before I apply a 4,000-entry LDIF, show me exactly what it will do —
> which entries are new, which collide with existing ones, which lines
> won't parse, which DNs fall outside this directory, and for group
> changes how many members get added or removed — without making me scroll
> 4,000 rows or wait on 4,000 round-trips."

Today the "Validate" button is a shallow dry-run that only reports
RFC-2849 **parse errors** and **counts every record as `skipped`** (§2). It
never says what each entry would do, never consults the live directory, and
never inspects group membership. This plan turns it into a true preview.

## 2. Current state (verified)

| Concern | Today | Gap |
|---|---|---|
| Endpoint | `POST /api/v1/superadmin/directories/{directoryId}/browse/import/ldif` with `conflictHandling` + `dryRun` params (`core/.../controller/superadmin/BrowseController.java:239-258`) | Single endpoint, dual semantics. No dedicated preview surface. |
| Authz | Class-level `@PreAuthorize("hasRole('SUPERADMIN')")` (`BrowseController.java:60`); service marked `@LdapWriteAuthorized` (`LdifService.java:41`) | OK as-is — preview keeps superadmin-only. |
| Parse / apply | UnboundID `LDIFReader` loop in `LdifService.importLdif` (`core/.../ldap/LdifService.java:61-141`); writes via `LdapConnectionFactory.withConnection` → `ReplicatingLdapInterface` | Loop conflates parse + classify + execute; not reusable for a read-only preview. |
| "Validate" (`dryRun=true`) | For both `Entry` and `LDIFChangeRecord`: `if (dryRun) { skipped++; continue; }` (`LdifService.java:~84,~96`) | **No classification, no directory lookup, no schema/scope/membership checks.** Only `LDIFException` parse errors are reported. |
| Result DTO | `LdifImportResult(added, updated, skipped, failed, List<LdifImportError(dn,message)>)` (`core/.../dto/ldap/LdifImportResult.java`) | Counts + flat error list only. No per-entry op, no diff, no per-row issues. |
| Conflict modes | `ConflictHandling` = `SKIP` / `OVERWRITE` / `PROMPT` | `PROMPT` currently behaves as `SKIP`. Preview makes "what would happen per mode" explicit. |
| Audit | `AuditService.record(..., AuditAction.LDIF_IMPORT, ...)` with added/updated/skipped/failed/dryRun (`BrowseController.java:250-256`) | Reuse `LDIF_IMPORT` for apply (per audit convention — no new action). Preview is read-only → not audited. |
| Frontend modal | `frontend/src/components/LdifImportModal.vue` — plain `<script setup>`, renders 4 counts + collapsible error list; button label flips `Validate`/`Import` | No per-entry view, no diff, no pagination. Must convert to `<script setup lang="ts">` on touch. |
| API client | `importLdif(dirId, file, conflictHandling, dryRun)` (`frontend/src/api/browse.js:36-43`); types in `frontend/src/api/openapi.d.ts` | Needs new preview/page/row/apply methods + regenerated types. |
| Mount point | `<LdifImportModal>` from `frontend/src/views/superadmin/DirectoryBrowserView.vue:247-251`, opened from an actions menu | Unchanged. |
| Tests | **None** for `importLdif` / the endpoint (backend or frontend) | Backfill regression tests for apply *and* add preview tests. |

### Reusable precedent & infrastructure (verified)

- **CSV bulk-import preview** is the model to mirror:
  `core/.../dto/csv/BulkImportPreviewResult.java`,
  `BulkImportPreviewRow(rowNumber, computedDn, attributes, missingRequired)`,
  test `core/.../service/BulkGroupServicePreviewTest.java`.
- `DnValidator` (`core/.../ldap/validation/DnValidator.java`) — `isValidDn`,
  `requireValidDn(dn, directoryType)` for DN-syntax + scope checks.
- `LdapBrowseService.entryExists(dc, dn)` — conflict detection.
- `LdapSchemaService.getAttributesForObjectClass(dc, oc)` — `MUST`/`MAY`
  (used by the schema tier; **deferred**, see §7).
- Frontend `DataTable.vue` — slot-per-column, selectable, keyboard nav,
  `EmptyState`. **No virtualization lib** in `package.json`; pagination is
  the established pattern (`UserListView`, `GroupListView`, `AuditLogView`).

## 3. Scope decisions (locked)

- **Groups in v1 = member delta only.** For group `modify` change records,
  compute and show `+N / −N` member changes (`member` / `uniqueMember` /
  `memberUid`); for group adds, summarize member **count**. v1 does **not**
  verify that referenced members exist. Full membership resolution
  (members vs. live directory ∪ entries added in this same LDIF) is
  **deferred** (§7).
- **Replace, don't parallel.** The preview supersedes `dryRun`. Keep the
  existing apply endpoint working, but the modal flow becomes
  **preview → apply**.
- **Stateful preview** keyed by a short-lived `previewId` (§4) so a 2–5K
  import is parsed and existence-checked **once**, then paged/filtered
  cheaply — and apply runs the *exact* records you previewed.

## 4. Backend design

### 4.1 Extract the parse loop (de-risk)

Refactor `LdifService.importLdif` so the `LDIFReader` iteration is a private
helper that yields, per record, a `(rowNumber, LDIFRecord | parseError)`
tuple. Both **preview** (classify) and **apply** (execute) consume it — one
parser, no drift. No behaviour change to apply; guarded by new regression
tests (§6, Phase 0).

### 4.2 Stateful preview, paged reads

New `LdifPreviewService` (core):

1. **Compute (once):** parse the upload → classify every record → run
   **batched** existence checks → build the full row list + summary totals.
   Store under a UUID `previewId` in a **bounded, TTL'd cache** (see
   decision D1) scoped to the creating superadmin. Hold the parsed records
   too, so apply needs no re-upload.
2. **Page reads:** serve filtered/searched/paged slices of the cached rows.
3. **Row detail:** serve one row's full attributes (and member-delta detail)
   on demand.
4. **Apply:** execute the cached records via the existing apply path,
   evict the cache entry, audit as `LDIF_IMPORT`.

### 4.3 Classification rules

- Content `Entry`:
  - not in directory → **ADD**.
  - already exists + `OVERWRITE` → **MODIFY** (+ `CONFLICT_EXISTS` info).
  - already exists + `SKIP`/`PROMPT` → **SKIP** (+ `CONFLICT_EXISTS` info).
- `LDIFChangeRecord` → op from `changeType`: **ADD / MODIFY / DELETE / MODDN**.
- **Group member delta** (v1): when a `modify` touches `member` /
  `uniqueMember` / `memberUid`, fold the `ADD`/`DELETE` modifications into
  `{added:int, removed:int}`. When an `add` is a group
  (`groupOfNames` / `groupOfUniqueNames` / `posixGroup`), record
  `memberCount`.
- Per-row **issues** (`{severity, code, message}`): `PARSE_ERROR`,
  `INVALID_DN`, `OUT_OF_SCOPE` (DN not under `dc.baseDn`), `CONFLICT_EXISTS`.

### 4.4 Batched existence (the 2–5K round-trip problem)

Don't call `entryExists` per row. Collect distinct target DNs, **group by
parent DN**, and issue **one one-level search per parent** (attrs `1.1`,
DN-only) to build a set of existing child DNs; classify against the set.
Bulk LDIFs typically cluster under a few OUs, collapsing thousands of
lookups into a handful. Fall back to per-DN base-scope search for scattered
parents. Cap total work (decision D2).

### 4.5 DTOs (new, in `core/.../dto/ldap/`)

```text
LdifPreviewSummary { previewId, totalRows,
                     countsByOp{add,modify,delete,moddn,skip,error},
                     warningCount, errorCount, truncated:boolean, page0 }
LdifPreviewRow     { rowNumber, dn, op, objectClasses[], attrCount,
                     memberDelta?{added,removed}, memberCount?,
                     issues[]{severity,code,message} }     // no attr values
LdifPreviewRowDetail { rowNumber, dn, op,
                     attributes:Map<String,List<String>>,  // capped per D3
                     memberDelta?{added,removed}, issues[] }
LdifPreviewPage    { rows[], page, size, totalFiltered }
```

### 4.6 Endpoints (BrowseController, superadmin-only)

```text
POST  …/browse/import/ldif/preview            (multipart file, conflictHandling)         → LdifPreviewSummary
GET   …/browse/import/ldif/preview/{id}        ?op=&q=&page=&size=                         → LdifPreviewPage
GET   …/browse/import/ldif/preview/{id}/row/{rowNumber}                                    → LdifPreviewRowDetail
POST  …/browse/import/ldif/preview/{id}/apply  (conflictHandling)                          → LdifImportResult
```

Keep legacy `POST …/browse/import/ldif` (apply / dry-run) for compatibility;
mark `dryRun` deprecated in favour of the preview flow.

## 5. Frontend design

Convert `LdifImportModal.vue` to **`<script setup lang="ts">`** (on-touch
rule), typed against the regenerated OpenAPI types. Flow:

1. **Pick file + conflict mode** (existing controls, `.input` / `.btn-*`).
2. **Preview** → calls `preview`, renders:
   - **Summary header** — reuse the dashboard **stat-chip** language:
     `Add 1,820 · Modify 240 · Delete 12 · Skip 90 · ⚠ 47 · ✕ 8`.
   - **Segmented filter** — `All / Adds / Modifies / Deletes / Conflicts /
     Errors`; default to problems-first (errors + conflicts) when any exist.
   - **DN search** (debounced, server-side via the page endpoint).
   - **Paged `DataTable`** (size 50–100): `Op badge · DN (mono, truncate) ·
     objectClass · attrs / member Δ · issue icon`. Lightweight rows only.
   - **Row detail drawer** — selecting a row lazy-loads
     `…/row/{n}` and shows the full attributes (and, later, the diff).
     Use a side/below drawer rather than inline expansion so `DataTable`
     stays unmodified; extract `LdifPreviewRow.vue` only if it earns it.
   - **Group member delta** — render `member +12 / −3`; large adds collapse
     to `1,240 members` with expand.
3. **Import** → calls `…/{id}/apply`; results + notification via the Pinia
   notification store as today; refresh the browser tree.

New `frontend/src/api/browse.js` methods: `previewLdif`, `getLdifPreviewPage`,
`getLdifPreviewRow`, `applyLdifPreview`. Run `npm run gen:api` after the
backend DTOs land.

## 6. Tasks

### Phase 0 — De-risk (parser + regression net)
- [ ] Add `LdifServiceTest` covering existing apply: add, conflict `SKIP`
      vs `OVERWRITE`, change records (add/modify/delete), parse-error
      counting. (Currently zero tests.)
- [ ] Extract the `LDIFReader` loop into a reusable `(rowNumber, record |
      parseError)` iterator; apply path unchanged; tests stay green.

### Phase 1 — Backend preview (v1)
- [ ] DTOs (§4.5).
- [ ] `LdifPreviewService`: classify (§4.3), batched existence (§4.4),
      DN-syntax + in-scope issues via `DnValidator`, group member-delta.
- [ ] Bounded TTL cache for `previewId` (decision D1); evict on apply.
- [ ] `BrowseController` endpoints (§4.6); apply-from-preview reuses the
      apply path + audits `LDIF_IMPORT`.
- [ ] Tests: `LdifPreviewServiceTest` (classification incl. member delta,
      batched-existence grouping, parse/DN/scope issues) + MockMvc
      (superadmin-only 403, multipart, paging/filter/search, row detail,
      apply-from-preview).

### Phase 2 — Frontend preview (v1)
- [ ] `npm run gen:api`; add `browse.js` methods.
- [ ] Convert `LdifImportModal.vue` → `<script setup lang="ts">`; preview
      flow, summary chips, segmented filter, search, paged `DataTable`,
      row-detail drawer, member-delta rendering, apply.
- [ ] `LdifImportModal.spec.ts` (currently none): preview render, filter /
      search / pagination, lazy row detail, member-delta display, apply.
      `setActivePinia` for the notification store; `vi.mock` the api module.

### Phase 3 — Docs
- [ ] Operator note in `docs/*.md` (LDIF import → preview); update this
      doc's **Status** to `Shipped`.

## 7. Out of scope for v1 (deferred tiers)

Documented so they're not silently dropped:

- **Schema validation** — `MUST`-attribute coverage per `objectClass` via
  `LdapSchemaService`; surfaces as a `SCHEMA_MISSING_MUST` warning.
- **Attribute-level diff** for `OVERWRITE` / `modify` — old→new per attribute
  in the row-detail drawer.
- **Full group membership resolution** — verify each `member` /
  `uniqueMember` / `memberUid` resolves against (live directory ∪ entries
  added in this LDIF); flag "N members won't resolve". (v1 ships delta only.)
- **Referential / ordering checks** — forward references (group before its
  members), `moddn` renames breaking existing group membership.
- **Async job + progress** for very large imports (50K+). v1 is synchronous.

## 8. Decisions / open questions

- **D1 — preview cache backing.** Recommended v1: **in-memory**, bounded +
  TTL (~30 min), per-superadmin. Caveat: in a multi-instance deployment
  without sticky sessions, page/row/apply could hit an instance lacking the
  entry (→ "preview expired, re-run"). Acceptable for a low-frequency
  superadmin tool; revisit with a DB/Redis backing if horizontal scale
  needs it. **Confirm single-instance assumption holds for now.**
- **D2 — max preview size.** Cap parsed records (config, default e.g.
  50,000); over-cap → clear 400 with guidance. Comfortably above the stated
  2–5K target.
- **D3 — member/value display cap.** In row detail, cap rendered values per
  multi-valued attribute (e.g. first 200 + "… N more") so a group with
  thousands of members can't blow up the payload or the DOM.
