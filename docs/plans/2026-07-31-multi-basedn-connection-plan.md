# Multi-base-DN directory connection — one connection traversing several naming contexts

**Status:** Not started (scoped 2026-07-31; revised to adopt a flat base-DN list).

A directory connection stores `baseDn` as a single string, and the general
search path falls back to it (`LdapUserService.searchUsers:91-96`). A server
with more than one top-level naming context (e.g. an OUD exposing both
`dc=corp,dc=example` and `o=partners`) therefore needs one connection per
context. This plan scopes letting a **single connection** cover all of a
server's contexts.

## What "traverse both DNs" has to mean

Two top-level DNs have **no common parent** except the null / Root-DSE base, so
every read surface must iterate a *list* of bases and merge, not search one
subtree.

| Surface | Code today | Fans out already? |
|---|---|---|
| User search | `LdapOperationService.searchUsers` (`:196`) | Yes — already fans out + dedups (`:213-225`) |
| Group search | `LdapOperationService.searchGroups` (`:591`) | Yes — already fans out + dedups (`:607-625`), same pattern as user search |
| Bulk CSV export (users/groups) | `LdapOperationService.bulkExportUsers` (`:1070`), `bulkExportGroups` (`:1419`) | Yes — already resolves a `bases` list via the resolver |
| User + group count | `ScopeCountService` (`:104`, `:166-179`) → `LdapUserService.countUsers` (`:165`), `LdapGroupService.countGroups` (`:143`) | No — single per-base count, cached/keyed per `baseDn` |
| Reverse membership (`isMemberOf` / AD matching rule) | `LdapGroupService.pagedDnSearch` (`:358`, reached from `:348`) | No — hardcodes `dc.getBaseDn()` |
| Tree browser | `LdapBrowseService.browse` (`:44`) | No — structurally can't subtree (see item 6) |
| Self-service group lookup | `SelfServiceService` (`:233`) | No — single, calls `LdapGroupService` directly (bypasses the resolver) |
| Auth / login probe | `AuthController` (`:494`) | No — single |
| Operational + scheduled reports | `OperationalReportService` (`runLdapReport:445`, `:181`, `:211/221`) | No — searches by `scopeBaseDn` directly (null ⇒ single `dc.getBaseDn()`), **bypasses the resolver**. A superadmin's whole-directory report only sees context 1 |
| Schema LDIF preview | `LdifPreviewService` (`:126`, `:190`) | No — single `dc.getBaseDn()` used to classify in-scope vs out-of-scope; context-2 entries mis-flagged |
| Integrity check | `IntegrityCheckService` (`:40`) | No — single `dc.getBaseDn()` fallback |
| Tree-browser parent refresh | `BrowseController.extractParentDn` (`:391`, callers `:97/139/165`) | No — uses single `dc.getBaseDn()` as the stop-ancestor / fallback parent; couples to item 6 |
| Discovery | `DirectoryDiscoveryService` (`:108`) | N/A — discovery is the *writer* of the list; scans an explicit `rootDn` |
| Sync engine (source/target scope) | `RecomputeEngine` (`:410/453`), `SyncContentVerifier`, `MembershipReconciler` (`:251`), `ClosureResolver` (`:57`), `LdapChangelogReader` (`:252`) | No — falls back to `source/target.getBaseDn()`; see "Sync boundary" below |

## Reusable piece

`LdapOperationService.searchUsers` already implements the target pattern: call
`resolveSearchBaseDns`, and if it returns more than one base, loop-search and
dedup by DN (`:213-225`). The clean design is to make `resolveSearchBaseDns`
return the connection's base-DN list for a superadmin when no explicit base is
requested, instead of today's single `[requestedBaseDn]` / `[null]`
(`PermissionService.java:365-367`). Every caller routed through that method
inherits multi-base behaviour for free — user search, group search **and both
bulk CSV exports** (`bulkExportUsers:1070`, `bulkExportGroups:1419`) already loop
over the resolved `bases` list, so a superadmin export will start spanning all
contexts the moment the resolver returns more than one base; the admin profile-OU
fan-out path stays untouched.

## Data model: one flat base-DN list, not user/group split

The connection today carries **two** base-DN lists —
`directory_user_base_dns` and `directory_group_base_dns`. That split is the
wrong shape and is being flattened as part of this work.

**Why flatten (evidence).** The user/group split on the *connection's* base-DN
lists is inert: the two tables are written (discovery commit, `saveBaseDns`) and
read back only by `toResponse` to build the GET response (`DirectoryConnectionService:545-546`)
and by discovery's own add-counters. **No LDAP search, count, browse, scope-count,
or authorization path reads them** — a plain user search and a plain group search
both fall back to the same single `dc.getBaseDn()` (`LdapUserService:96`,
`LdapGroupService:89`). (Config *export* does not touch them at all —
`ConfigExportService` emits admins/permissions, not directory base DNs; the IaC
*import* DTO `DirectoryConnectionRequest` does carry them, so the import contract
changes with the DTO collapse — see item 3.)
The user-vs-group DN distinction that actually scopes behaviour is a
**provisioning-profile** concern (`ProvisioningProfile.targetUserDn` /
`targetGroupDn`, fanned out by `PermissionService.getAuthorizedOuDns:235-245`
and consumed by `resolveSearchBaseDns`). The connection's *legitimate* user/group
distinction is **classification** — `userObjectClasses` / `groupObjectClasses`
and the derived user/group search filters (used at `countUsers:167`,
`countGroups:145`) — which is orthogonal to base DNs and stays as-is.

**Decision.** A directory connection captures a single ordered **flat list of
base DNs** (the naming contexts it exposes). User-vs-group scoping stays entirely
on provisioning profiles. The single `baseDn` column remains as the default /
fallback so single-context directories do not regress; the flat list, when
non-empty, is the set the resolver fans out over.

Rejected alternatives for the list's source of truth:
- **Read Root DSE `namingContexts` live** (already captured at
  `LdapCapabilityProbeService.readRootDse:84`). Zero admin config, but dynamic
  and unscoped (includes `cn=config` etc.) — needs filtering, and can't be
  curated per connection.
- **Keep the two user/group tables and only wire the read side.** Lowest churn,
  but bakes in an inert distinction that misleads anyone configuring a
  connection and forces the edit form to show two lists where one is correct.

## Work breakdown

**Data model & persistence**

1. **Collapse to one table.** Introduce `directory_base_dns` (`directory_id`,
   `dn`, `display_order`) and a migration that copies existing
   `directory_user_base_dns` + `directory_group_base_dns` rows into it
   (de-duplicating by DN, preserving order), then drops the two old tables. Keep
   the `DirectoryConnection.baseDn` column as default/fallback.
2. **DTOs.** Collapse `DirectoryConnectionRequest.userBaseDns` /
   `groupBaseDns` and `DirectoryConnectionResponse.userBaseDns` / `groupBaseDns`
   into a single `baseDns` array. Update `saveBaseDns` accordingly, and fix the
   **null-means-untouched** semantics so a request that omits `baseDns` leaves
   the stored list intact instead of wiping it (today `baseDnsUnchanged:504`
   treats a `null` request field as empty and `saveBaseDns` then deletes). This
   is **live, not latent**: the current edit form sends no base-DN fields, so
   every directory update already silently wipes the discovered lists — a
   regression test for this should land with the fix.
3. **Discovery + IaC import.** Point `DirectoryDiscoveryService` at the flat
   table; collapse the discovery `userBaseDnsAdded` / `groupBaseDnsAdded`
   counters into one. IaC *import* changes transitively via the
   `DirectoryConnectionRequest` DTO collapse (item 2) — `BootstrapConfigReconciler`
   reads that DTO. There is **no directory config *export*** to update
   (`ConfigExportService` doesn't emit base DNs), so the only doc work is showing
   the new `baseDns` list in the example YAML (`bootstrap-config.example.yml`,
   `group_vars/all/main.yml`), which today carry only the singular `baseDn`.

**Search fan-out (the actual feature)**

4. **`resolveSearchBaseDns` superadmin branch** — when `requestedBaseDn` is
   null, return the connection's flat base-DN list (fallback `[dc.getBaseDn()]`
   when empty). Core change. `PermissionService.java:365`. Load the list where
   the resolver runs (inject the repo).
5. **Count parity** — group *search* already fans out through the resolver
   (`LdapOperationService.searchGroups:607-625`), so no work there. The gap is
   **counts**: they live in `ScopeCountService`, not `LdapOperationService`
   (there is no `LdapOperationService.countUsers`). `ScopeCountService`
   (`:104`, `registerScope:143-181`) counts each scope under a single `baseDn`
   via `LdapUserService.countUsers:165` / `LdapGroupService.countGroups:143`,
   caching the result **per `baseDn` cache key** (`userKey`/`groupKey:183-186`)
   with a `-1` failure sentinel. Fanning out here means registering one scope
   per base and summing — more involved than the search merge because each base
   is its own cache entry and its own success/failure, so this is the item most
   likely to exceed the effort estimate. Otherwise dashboard counts won't match
   the multi-base result set.
5a. **Reverse membership** — `LdapGroupService.pagedDnSearch:358` (the
   `isMemberOf` / AD matching-rule lookup reached from `:348`) hardcodes
   `dc.getBaseDn()` and never touches the resolver. Two contexts means a user's
   group memberships in the second context are invisible. Decide whether to fan
   this out now or defer; it is a distinct surface from group search/count.
6. **Tree browser — special case.** `browse(dc, null)` reads *one* entry plus
   its one-level children (`:44-54`); two top-level DNs have no single parent
   entry. Needs a **synthetic root node**: when no DN is requested, return a
   virtual root whose children are the configured base DNs, and descend into a
   real subtree only on click. Small backend shape change plus a frontend tweak
   in the browser view. The only piece with a substantial UI change beyond the
   form. **Also touches `BrowseController`**: `extractParentDn:391` (used by the
   create/delete/move refresh paths at `:97/139/165`) treats the single
   `dc.getBaseDn()` as the top-of-tree stop-ancestor and fallback parent — under
   the synthetic-root model the "parent" of a base-DN-level entry is the virtual
   root, not the primary base, so the refresh-target logic needs the same
   awareness, not just the browse read.
7. **Self-service / auth probe** — decide scope. Login (`AuthController:494`)
   searches by filter; with two contexts it must fan out or logins in the second
   context fail. Easy to overlook. Self-service group lookup (`SelfServiceService:233`)
   calls `LdapGroupService` **directly**, bypassing the resolver — it can't
   inherit the fan-out and must be handled explicitly if in scope.
7a. **Resolver-bypassing read surfaces** — three read paths search by an explicit
   base and never call `resolveSearchBaseDns`, so they silently miss context 2 for
   a superadmin. Decide in-scope vs documented-limitation for each:
   - **Reports** — `OperationalReportService` runs `searchUsers`/`searchGroups`
     under `scopeBaseDn` (null for a whole-directory report) at `runLdapReport:445`,
     `:181`, `:211/221`. A superadmin operational/scheduled report over the whole
     directory only counts context 1.
   - **Schema LDIF preview** — `LdifPreviewService:126,190` classifies entries
     in-/out-of-scope against the single base; context-2 entries are mis-flagged.
   - **Integrity check** — `IntegrityCheckService:40` runs under the single base.
   These share no resolver plumbing with items 4–5, so bringing them in is
   additional work; deferring is fine but must be an explicit "known limitation",
   not silence.

**UI**

8. **Edit form.** `DirectoriesManageView.vue` gains a single **"Base DNs"** list
   editor (add/remove rows, ordered) in Advanced settings, pre-populated from the
   response. This is where an operator adds a second naming context to an
   existing connection — not just the discovery wizard. It also fixes the
   form-save clobber (the form now round-trips the list instead of omitting it).
8a. **Discovery wizard view + codegen.** `DiscoveryWizardView.vue` is a **second**
   frontend surface: it builds and displays `userBaseDns` / `groupBaseDns`
   separately (`:94-110`, `:154-160`, `:547-577`) and reads
   `userBaseDnsAdded` / `groupBaseDnsAdded` from the commit response. It must
   collapse to the single list alongside the DTO change. Re-run `npm run gen:api`
   after the backend DTO change (`openapi.d.ts` currently types both fields) or the
   frontend won't typecheck.

**Tests**

9. Resolver returns multi-base for a superadmin; search/count fan-out + dedup;
   browser synthetic root **and parent-refresh targeting**; single-context
   directory unchanged (regression guard); admin profile scoping unaffected by
   the connection list; migration collapses the two tables without losing DNs;
   **directory-update-omits-baseDns no longer wipes the list** (the live-clobber
   regression); plus fan-out coverage for whichever of the item-7a surfaces land.

## Risks / edge cases

- **Dedup and paging:** the merge dedups by DN, but `limit` is split across
  bases naively (`:216`) — fine for a flat cap, not for true pagination with
  per-base cursors.
- **Cross-context RDN collisions:** DN-based dedup handles it; check any code
  that assumes one entry per `uid`.
- **Admin scoping:** unaffected by design — the connection list only widens a
  *superadmin's* unscoped search; admins stay bound to their profile OUs. Add an
  explicit test.
- **Migration is one-way** (drops the two tables). It's an internal shape change
  with no runtime consumers, so low risk, but the IaC/export contract changes —
  document the `userBaseDns`/`groupBaseDns` → `baseDns` rename for anyone with
  hand-written bootstrap YAML.
- **Discovery already populates the (now flat) list** — an existing discovered
  directory lights up multi-base behaviour the moment the resolver starts reading
  it. That's the intended outcome; confirm it's desirable for already-discovered
  directories or gate behind a toggle.
- **Sync boundary.** The sync engine scopes source/target scans by the sync
  *set's* own `objectScopeBaseDn` / `targetBaseDn`, falling back to
  `source/target.getBaseDn()` (`RecomputeEngine:410/453`, `SyncContentVerifier`,
  `MembershipReconciler:251`, `ClosureResolver:57`). A multi-base connection used
  as a sync endpoint therefore still scans only its **primary** `baseDn` — the
  second context is invisible to sync, and `LdapChangelogReader:252` drops
  changelog entries whose target DN doesn't end in the primary base. This is a
  deliberate boundary (sync scope is a sync-set concern, not a connection
  concern), but it must be **stated** so nobody assumes a discovered second
  context auto-syncs. Extending sync to multi-base is out of scope for this plan.

## Effort

Medium. The search fan-out (item 4) is small because the user- and group-search
paths already fan out; the count fan-out (item 5) is the sleeper — it lands in
`ScopeCountService`'s per-base cache/keying, not a one-line sum. The data-model
flattening (items 1–3) plus the form editors (items 8, 8a — **two** Vue views,
not one) is the bulk — mechanical but touches DTOs, a migration, discovery, and
the IaC import DTO. Item 6 (browser synthetic root + parent-refresh) is the one
with real design shape. The item-7a surfaces (reports / LDIF preview / integrity
check) and the sync boundary are the scope dials: including 7a widens the change
noticeably, deferring keeps it tight. Could land as one PR, or split: **PR A** =
flatten the list + form editors + null-safe save (a clean refactor, shippable on
its own, and it stops the live clobber); **PR B** = the search/count/browser
fan-out on top; item-7a surfaces as **PR C** if pursued.

## Open questions before implementing

- One PR or the A/B split above? (Leaning A/B — the flatten is independently
  valuable and low-risk.)
- Tree-browser synthetic root (item 6) in scope now, or a follow-up?
- Enable multi-base whenever the list is non-empty, or behind a per-connection
  toggle?
- **Which resolver-bypassing read surfaces (item 7a) are in scope** — reports,
  LDIF preview, integrity check — vs. shipped as a documented known-limitation?
- Reverse-membership fan-out (item 5a) and self-service group lookup (item 7) in
  this PR or deferred?
- Confirm the sync boundary (multi-base does **not** extend sync coverage) is the
  intended behaviour.
