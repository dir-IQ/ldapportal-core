# Directories with several top-level roots

**Status:** Shipped (additional base DNs on a directory connection, 2026-10-21).

Some servers hold more than one top-level root (suffix / naming context) in
the same directory: an OUD or 389-DS instance with `dc=example,dc=com` and
`o=partners`, an OpenLDAP server with several `olcSuffix` databases, or an
IBM Security Directory Server with `secAuthority=Default` beside the
organization suffix. One directory connection can now cover all of them, so
you no longer need one connection per root.

## Configuring

In **Directory Connections → Edit**, keep the main root in **Base DN** and
list the others under **Additional base DNs**, one per line. If the server's
root DSE has been probed, the editor offers an **Add the server's other
naming contexts** button that fills in the roots it advertises that aren't
configured yet.

Rules, enforced on save:

- each root must be a valid DN;
- roots must not nest: a root may not be the base DN, or sit above or below
  it or another root (list each top-level root once);
- Entra ID connections have no base DNs, so the list is ignored there.

Config export / import carries the list (`additionalBaseDns`).

## What covers every root

Anything that searches **without an explicit base** now searches each root
in turn (primary first) and merges the results, stopping at the request's
size limit:

- user, group and entry searches (superadmin search, the directory search
  page, reports and discovery), and the counts behind them;
- integrity checks: a `member` pointing into another root is not "broken",
  and no root is reported as an orphaned entry;
- the self-service group lookups;
- LDIF import preview: an entry is out of scope only when it is under none
  of the roots;
- changelog capture matches changes under any root to the directory.

An **explicit base** (a search base, a profile's container, a sync set's
scope) still searches only under that base, exactly as before. A connection
with no additional roots behaves exactly as it always has.

## Directory Browser

The tree shows one top-level node per root. The base DN is selected when a
directory opens; a "Reveal in browser" link to an entry under any root opens
the tree down to it. A root the bind account can't read is left out of the
tree with an error notification rather than breaking the browser.

## Things to be aware of

- **Self-service login** looks the user up across every root. If the login
  attribute matches entries under more than one root, the login is refused
  (as an invalid username or password, with a warning in the server log)
  rather than binding as whichever entry came back first. Make the login
  attribute unique across roots, or use a more specific one.
- **Directory sync sets** on a multi-root source or target must name their
  **Source scope base DN** and **Target base DN** explicitly; leaving them
  blank (which means "the directory's base DN" on a single-root connection)
  is rejected on save, because it would silently pick just the first root.
  Use one sync set per root you want to mirror.
- Browsing with no DN, and audit entries that record "the directory" rather
  than a specific entry, still refer to the primary base DN.
