# Multi-base-DN directory connection — one connection traversing several naming contexts

**Status:** Not started (scoped, 2026-07-31).

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
| Tree browser | `LdapBrowseService.browse` (`:44`) | No — structurally can't subtree (see item 5) |
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

## Source-of-truth decision (gates everything)

1. **Reuse the existing `directory_user_base_dns` / `directory_group_base_dns`
   tables.** They already store ordered per-connection lists and are populated
   by the discovery wizard, but nothing reads them at search time yet. Lowest
   schema churn — the missing wiring is purely read-side. **← recommended.**
2. **Read Root DSE `namingContexts` live** (already captured at
   `LdapCapabilityProbeService.readRootDse:84`). Zero admin config, but dynamic
   and unscoped (includes `cn=config` etc.) — needs filtering.
3. **Add a new "additional base DNs" list field on the connection + edit form.**
   Most explicit and user-controlled, most schema + UI work.

Recommendation: **option 1.** Keep the single `baseDn` as the default / fallback
so single-context directories do not regress.

## Work breakdown (option 1)

1. **`resolveSearchBaseDns` superadmin branch** — when `requestedBaseDn` is
   null, return the connection's configured base-DN list (fallback
   `[dc.getBaseDn()]` when the list is empty). Core change.
   `PermissionService.java:365`.
2. **Load the lists where the resolver runs** — expose `userBaseDns` /
   `groupBaseDns` on `DirectoryConnection`, or inject the repos (today only
   `DirectoryConnectionService` / discovery touch them).
3. **Count parity** — `LdapOperationService.countUsers` and the group counts
   need the same fan-out-and-sum, or counts will not match the result set.
4. **Group search** — route `LdapGroupService` search/count through the
   resolver (currently direct `dc.getBaseDn()` at `:89`, `:144`, `:360`).
5. **Tree browser — special case.** `browse(dc, null)` reads *one* entry plus
   its one-level children (`:44-54`); two top-level DNs have no single parent
   entry. Needs a **synthetic root node**: when no DN is requested, return a
   virtual root whose children are the configured base DNs, and descend into a
   real subtree only on click. Small backend shape change plus a frontend tweak
   in the browser view. Most visible part; the only piece with a UI change.
6. **Self-service / auth probe** — decide scope. Login (`AuthController:494`)
   searches by filter; with two contexts it must fan out or logins in the second
   context fail. Easy to overlook.
7. **Tests** — resolver returns multi-base for superadmin; search/count fan-out
   + dedup; browser synthetic root; single-context directory unchanged
   (regression guard); admin profile scoping unaffected.

## Risks / edge cases

- **Dedup and paging:** the merge dedups by DN, but `limit` is split across
  bases naively (`:216`) — fine for a flat cap, not for true pagination with
  per-base cursors.
- **Cross-context RDN collisions:** DN-based dedup handles it; check any code
  that assumes one entry per `uid`.
- **Admin scoping:** unaffected by design — add an explicit test that a
  profile-scoped admin does not gain visibility into the second context.
- **Discovery wizard already writes these tables** — an existing discovered
  directory could light up multi-base behaviour the moment we start reading
  them. Confirm that is desirable, or gate it behind an explicit toggle.

## Effort

Medium. Items 1–4 are contained edits to `PermissionService` /
`LdapOperationService` / `LdapGroupService` plus repo wiring (the user-search
fan-out is already done). Item 5 (browser synthetic root) is the one with a real
design shape and a frontend touch. Roughly one focused PR, comparable to the
recently merged schema PRs — not a large refactor.

## Open questions before implementing

- Confirm the source of truth (recommending option 1).
- Item 5 (tree browser synthetic root) in the same PR, or split out (it is the
  only UI-touching piece)?
- Gate multi-base behind a toggle, or enable it whenever the base-DN list is
  non-empty?
