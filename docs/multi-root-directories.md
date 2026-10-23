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

The Directory Connections list shows the primary base DN with a "+N roots"
hint (hover for the other roots).

## Directory Browser and DN pickers

The tree shows one top-level node per root. The base DN is selected when a
directory opens; a "Reveal in browser" link to an entry under any root opens
the tree down to it. A root the bind account can't read is left out of the
tree with an error notification rather than breaking the browser.

The **Browse** button next to DN fields (sync set scope and target bases,
integrity check base, profile containers, …) opens the same one-node-per-root
tree, so a DN under any root can be picked rather than typed. (The tree's
roots come from the server's browse of the directory root, which lists the
other roots as `otherRoots`.) A picker the caller restricts to an admin's
authorized OUs still shows only those OUs.

## Things to be aware of

- **Self-service login** looks the user up across every root. If the login
  attribute matches more than one entry — under different roots, or twice
  under the same one — the login is refused (as an invalid username or
  password, with a warning in the server log naming both entries) rather
  than binding as whichever entry came back first. This applies to
  single-root directories too: before this change a duplicate login value
  silently signed in as the first match. Make the login attribute unique, or
  use a more specific one.
- **Directory sync sets** on a multi-root **source** must name their
  **Source scope base DN**: leaving it blank (which means "the directory's
  base DN" on a single-root connection) is rejected on save, because it would
  silently pick just the first root. Use one sync set per root you want to
  mirror. On a multi-root **target**, a blank **Target base DN** means the
  entries keep their source DNs; that's accepted when the source scope base DN
  lies under one of the target's roots, and rejected otherwise.
- **Adding a root to a directory that already has sync sets** doesn't change
  those sets: one with a blank source scope (or, when the directory is a sync
  target, a blank target base) keeps using the primary base DN only. The save
  goes through, and the editor shows a warning naming each such set, so you
  can give it an explicit base if it should cover the new root.
- With no explicit base, a search runs once per root, and its time limit
  applies to each root's search, so a search across N roots can take up to N
  times the limit.
- Browsing with no DN, and audit entries that record "the directory" rather
  than a specific entry, still refer to the primary base DN. An integrity
  check run without a base records every root it checked in the audit
  entry's `bases` detail.
