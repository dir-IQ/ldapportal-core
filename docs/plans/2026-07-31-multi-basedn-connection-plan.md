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
| User count | `LdapUserService.countUsers` (`:166`) | No — single `dc.getBaseDn()` |
| Group search/count | `LdapGroupService` (`:89`, `:144`, `:360`) | No — single |
| Tree browser | `LdapBrowseService.browse` (`:44`) | No — structurally can't subtree (see item 6) |
| Self-service group lookup | `SelfServiceService` (`:233`) | No — single |
| Auth / login probe | `AuthController` (`:494`) | No — single |
| Schema LDIF preview, integrity check, discovery | `LdifPreviewService`, `IntegrityCheckService`, `DirectoryDiscoveryService` | No — single |

## Reusable piece

`LdapOperationService.searchUsers` already implements the target pattern: call
`resolveSearchBaseDns`, and if it returns more than one base, loop-search and
dedup by DN (`:213-225`). The clean design is to make `resolveSearchBaseDns`
return the connection's base-DN list for a superadmin when no explicit base is
requested, instead of today's single `[requestedBaseDn]` / `[null]`
(`PermissionService.java:365-367`). Every caller routed through that method
inherits multi-base behaviour for free; the admin profile-OU fan-out path stays
untouched.

## Data model: one flat base-DN list, not user/group split

The connection today carries **two** base-DN lists —
`directory_user_base_dns` and `directory_group_base_dns`. That split is the
wrong shape and is being flattened as part of this work.

**Why flatten (evidence).** The user/group split on the *connection's* base-DN
lists is inert: the two tables are written (discovery commit, `saveBaseDns`) and
read back only to build the GET response, the IaC export, and discovery's
add-counters. **No LDAP search, count, browse, scope-count, or authorization
path reads them** — a plain user search and a plain group search both fall back
to the same single `dc.getBaseDn()` (`LdapUserService:96`, `LdapGroupService:89`).
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
   the stored list intact instead of wiping it (today `baseDnsUnchanged` treats
   `null` as empty and deletes — the latent clobber bug).
3. **Discovery + IaC.** Point `DirectoryDiscoveryService` at the flat table;
   collapse the discovery `userBaseDnsAdded` / `groupBaseDnsAdded` counters into
   one. Update the IaC export/import shape and the example YAML
   (`bootstrap-config.example.yml`, `group_vars/all/main.yml`).

**Search fan-out (the actual feature)**

4. **`resolveSearchBaseDns` superadmin branch** — when `requestedBaseDn` is
   null, return the connection's flat base-DN list (fallback `[dc.getBaseDn()]`
   when empty). Core change. `PermissionService.java:365`. Load the list where
   the resolver runs (inject the repo).
5. **Count parity + group search** — route `LdapOperationService.countUsers`,
   `LdapGroupService` search/count (`:89`, `:144`, `:360`) through the same
   fan-out-and-merge, or counts won't match the result set.
6. **Tree browser — special case.** `browse(dc, null)` reads *one* entry plus
   its one-level children (`:44-54`); two top-level DNs have no single parent
   entry. Needs a **synthetic root node**: when no DN is requested, return a
   virtual root whose children are the configured base DNs, and descend into a
   real subtree only on click. Small backend shape change plus a frontend tweak
   in the browser view. The only piece with a substantial UI change beyond the
   form.
7. **Self-service / auth probe** — decide scope. Login (`AuthController:494`)
   searches by filter; with two contexts it must fan out or logins in the second
   context fail. Easy to overlook.

**UI**

8. **Edit form.** `DirectoriesManageView.vue` gains a single **"Base DNs"** list
   editor (add/remove rows, ordered) in Advanced settings, pre-populated from the
   response. This is where an operator adds a second naming context to an
   existing connection — not just the discovery wizard. It also fixes the
   form-save clobber (the form now round-trips the list instead of omitting it).

**Tests**

9. Resolver returns multi-base for a superadmin; search/count fan-out + dedup;
   browser synthetic root; single-context directory unchanged (regression
   guard); admin profile scoping unaffected by the connection list; migration
   collapses the two tables without losing DNs.

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

## Effort

Medium. The search fan-out (items 4–5) is small because the user-search path
already fans out. The data-model flattening (items 1–3) plus the form editor
(item 8) is the bulk — mechanical but touches DTOs, a migration, discovery, and
IaC. Item 6 (browser synthetic root) is the one with real design shape. Could
land as one PR, or split: **PR A** = flatten the list + form editor + null-safe
save (a clean refactor, shippable on its own); **PR B** = the search/count/browser
fan-out on top.

## Open questions before implementing

- One PR or the A/B split above? (Leaning A/B — the flatten is independently
  valuable and low-risk.)
- Tree-browser synthetic root (item 6) in scope now, or a follow-up?
- Enable multi-base whenever the list is non-empty, or behind a per-connection
  toggle?
