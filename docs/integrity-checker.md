# Referential Integrity Checker

**Status:** Shipped (broken-member, orphaned-entry and empty-group checks; removal of broken member references; IVIA `secDN` reference check; 2026-10-28).

The superadmin **Referential Integrity Checker** scans a directory for
references that point nowhere and entries that have lost their place, and can
remove the broken group member references it finds.

## Running a check

Pick a directory, optionally a base DN (blank checks every root of the
directory), and the checks to run:

| Check | Finds |
|---|---|
| **Broken member references** | A group's `member` or `uniqueMember` value naming a DN no entry has. Values are compared as DNs, so one that differs from the entry's DN only in case or spacing isn't broken. A member under another root of the same directory isn't broken either. |
| **Orphaned entries** | An entry whose parent doesn't exist. Each searched root is exempt. |
| **Empty groups** | A `groupOfNames`, `groupOfUniqueNames` or `posixGroup` with no members. |
| **Broken references (secDN)** | *Offered only on a directory with an active linked-mode IVIA configuration.* An IVIA account (a `secUser` under the configured user container in the management DIT) whose `secDN` names no entry, or that has no `secDN` at all. The same scan as the **Orphaned IVIA accounts** report. With a base DN, only accounts whose `secDN` (or the account itself) is under it are listed; accounts with no `secDN` are then left out. These aren't removed from here: fix the account's `secDN`, or remove the orphaned account, on the directory. |

Any superadmin can run a check; each run is audited as `integrity.check`.
The checks a directory offers come from
`GET /api/v1/superadmin/directories/{id}/browse/integrity-check/checks`
(`[{"type","attributes"}]`); `BROKEN_REFERENCE` is listed only where an addon
contributes a reference check for that directory (the IVIA addon's `secDN`
check, through the core `IntegrityCheckContributor` SPI), and is requested
like the others (`checks=…,BROKEN_REFERENCE`).

A check run with a base DN loads only the DNs under that base, so a group
member that lives elsewhere in the directory is reported as broken. Removing
references re-checks each one (below), so such a member is kept — but leave
the base blank when you mean to clean up.

## Removing broken member references

With *manage directory data*, the **Broken Member References** section has a
checkbox per reference and **Remove broken references**. Tick the references
to remove (or all of them) and confirm. For each one the server:

1. **Checks it again.** The report may be stale: if an entry now exists at the
   referenced DN (a user was re-created, or the member lives outside the base
   the check searched), the reference is **kept**. If the group no longer
   holds the value, or the group is gone, there's nothing to do.
2. **Removes the rest in one modify per group**, using the value exactly as
   the group stores it. A group either loses all of its selected references
   or, if the server refuses the modify, none. The usual refusal is a group
   whose object class requires a member (`groupOfNames` and
   `groupOfUniqueNames` on most servers) being left with none: remove the
   group, or add a member first.

The result says how many were removed, kept, already gone or refused (with the
server's reason), and the check runs again. Each removal is audited as
`group.member_remove` on the group, with `detail.member` (the removed DN),
`detail.attribute`, `detail.source = integrity-check` and
`detail.operation = remove-broken-reference`, so the audit log's target-DN
filter finds it from either the group or the member.

Only `member` and `uniqueMember` values can be removed this way. The write is
a plain LDAP modify on the group: it doesn't go through vendor provisioning
(an IVIA `secUser` lookup can't succeed for a member that doesn't exist), and
portal-made group changes are picked up by directory sync as usual.

API:
`POST /api/v1/superadmin/directories/{id}/browse/integrity-check/remove-broken-references`
with `{"references":[{"groupDn","attribute","value"}, …]}` (at most 5,000; the
`dn`, `attribute` and `value` of the check's `BROKEN_MEMBER` issues), requiring
*manage directory data*. It returns `removed` and an `outcomes` entry per
reference with `status` `REMOVED`, `KEPT_ENTRY_EXISTS`, `ALREADY_GONE` or
`FAILED` (and `message`).

## Preventing broken references

The checker repairs references after the fact. Preventing them belongs in the
directory server, which sees every delete and rename — not just the portal's:

| Server | Mechanism |
|---|---|
| Active Directory | Built in: `member` / `memberOf` are linked attributes, kept consistent by the DC. |
| Microsoft Entra ID | Managed by the service. |
| OpenLDAP | The `refint` overlay (`slapo-refint`), listing `member`, `uniqueMember` and any other DN-valued attributes to maintain. |
| 389 DS / Red Hat DS, ODSEE / Oracle Unified Directory | The Referential Integrity plugin (often off by default). In multi-supplier topologies follow the vendor's guidance on which suppliers run it. |
| IBM Security Directory Server (IVIA's registry) | Check your version's documentation for group referential integrity support. |

These also rewrite references when an entry is renamed or moved, which a
cleanup on delete wouldn't. Use the checker to find and clear what's already
broken, and after deletes made outside the portal on a server without such a
plugin.
