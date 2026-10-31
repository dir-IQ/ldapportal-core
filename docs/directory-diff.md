# Directory diff

**Status:** In progress (backend and API shipped; the UI is next, 2026-10-31).

Compare two directories, or two branches of one directory, entry by entry.
Every entry ends up in one of four groups:

| Group | Meaning |
|---|---|
| **Same** | Paired, and every compared attribute holds the same values on both sides |
| **Different** | Paired, but at least one compared attribute differs |
| **Only in A** | No partner on side B |
| **Only in B** | No partner on side A |

A diff only reads. It never writes to either directory.

## Who can run it

Superadmins who hold the **Run directory diffs** permission
(`superadmin.manage_directory_diff`) can run diffs. The owner superadmin holds
it automatically. Results show attribute values from both directories, so grant
the permission only where you would grant read access to both.

## Choosing the sides

Each side is a directory, a base DN, a scope and a filter:

- **Base DN** is optional. Left blank, it is the directory's base DN. It must lie
  under one of the directory's roots.
- **Scope** is `SUB` (the default) or `ONE`.
- **Filter** is optional. Left blank, it is `(objectClass=*)`.

The two sides may be the same directory with different bases, for example to
compare `ou=staging` with `ou=production`. They may not be identical. Entra ID
directories can't be compared, because they aren't read over LDAP.

## Pairing

Pairing decides which entry on A is compared with which entry on B. There are
two modes.

- **By DN (default).** Side A's base is swapped for side B's, and entries whose
  rebased DNs match (case-insensitively) are paired. `uid=alice,ou=people,dc=a`
  pairs with `uid=alice,ou=people,dc=b` when the bases are `dc=a` and `dc=b`.
- **By attribute.** Entries pair on an attribute's value, compared trimmed and
  case-insensitively. Typical attributes are `employeeNumber`, `mail` or
  `entryUUID`. Use this mode when the two trees are laid out differently.

An entry that can't pair is listed under Only in A or Only in B, with a note
saying why. The reasons are:

- it isn't under side A's base;
- it has no value for the pairing attribute, or more than one;
- its key is shared by several entries.

## What counts as different

The diff is meant to be usable for checking sync's work, so it has its own
comparison code. Nothing it uses to pair entries or decide "same" comes from
the sync engine, so a sync bug can't hide itself in the diff. A test runs the
same cases through both and checks they agree, so the two can't drift apart
unnoticed. The rules:

- **DN-valued attributes** (`member`, `uniqueMember`, `manager`, `owner`, …, plus
  any attribute with DN syntax in side B's schema) compare as DNs. Spacing and
  case don't count, and side A's base is swapped for side B's first, so a
  `member` pointing at the "same" user on each side matches.
- **Every other attribute** compares exactly. A case-only change counts as a
  difference.
- **Attribute order and value order** don't count.

### Attributes that are left out

Some attributes are never compared:

- **Operational attributes** are always left out: timestamps, `entryUUID`,
  `entryCSN`, `objectGUID` and the like. The diff keeps its own list rather than
  the sync engine's exclusions, so an attribute sync skips by mistake still
  shows up as missing on one side.
- **Password attributes** (`userPassword`, `unicodePwd`, …) are never read,
  compared or stored. This holds even when you list them yourself.
- **Ignore** is an extra list you can add for a run, for example
  `description`.
- **Compare only** is a list of attributes. When you give one, only those
  attributes count.

The base entries of two differently named trees always differ in their naming
attribute (`dc=a` vs `dc=b`). That is expected.

## Using it to check sync

Sync isn't a straight copy, so expect some differences that are intended:

- **Different tree layout.** If sync places entries under different containers
  on the target, pairing by DN won't match them. Pair by an attribute that is
  the same on both sides instead, such as `employeeNumber` or `mail`.
- **Translated references.** `member` values that sync rewrites to other target
  DNs show as different, because the diff only swaps the base DN.
- **A sync set's excluded attributes** show as only on side A. Add them to
  **Ignore** to leave them out.

## Results

A run works through three stages: it reads side A, then side B, then compares.
Progress is updated about once a second.

For each finished run you can:

- page through each group, newest run first;
- filter the Different group by one attribute;
- search any group by DN substring;
- open an entry to see each differing attribute's values. The values are split
  into only-on-A, only-on-B and on-both, with up to 100 values per side and full
  counts;
- **re-check** an entry. This reads both entries again now and compares them the
  same way, so you can confirm a fix without re-running the whole diff;
- download a group, or all groups, as CSV.

See [Retention](#retention) for how many runs are kept.

## Limits and settings

| Property | Default | Meaning |
|---|---|---|
| `ldapportal.directory-diff.max-concurrent` | `2` | Runs at once; a further start gets 409 |
| `ldapportal.directory-diff.max-entries-per-side` | `200000` | A side larger than this fails the run |
| `ldapportal.directory-diff.memory-percent` | `40` | Share of the server's heap all runs together may fill with entries |
| `ldapportal.directory-diff.memory-budget-mb` | unset | A fixed per-run budget instead, in MB |
| `ldapportal.directory-diff.keep-per-pair` | `10` | Complete runs kept per pair of directories |

### Memory

A run holds both sides in memory while it compares them. It keeps only what it
compares: each entry's DN, the compared attributes and the pairing attribute.
Operational attributes, passwords and ignored attributes are dropped as each
page arrives.

Each run tracks roughly how much heap its entries take. A run that would go
over its budget fails with a message saying how far it got, rather than running
the server out of memory. The budget is `memory-percent` of the heap, split
between `max-concurrent` runs. With the shipped 512 MB heap that comes to about
100 MB per run. The size estimate errs high, so that budget holds about 10,000
to 15,000 typical user entries per side; fewer for entries with large values.

To compare more, try these in order:
- narrow the base DN or the filter;
- compare only the attributes you care about, or ignore large ones such as
  `jpegPhoto`;
- give the server a larger heap.

### Retention

A pair of directories keeps its latest 10 complete runs. Failed and cancelled
runs are kept only while they are among the latest 10 runs overall. A few
failed attempts therefore never push out the last good result.

Runs happen in the background, so the 60-second load balancer idle timeout
doesn't apply. A run whose server restarts mid-way shows as **Failed**, with
the error "Interrupted". Run it again.

## API

All endpoints are under `/api/v1/superadmin/directory-diffs`.

| Method | Path | |
|---|---|---|
| POST | `/` | Start a run (202) |
| GET | `/` | Latest 20 runs |
| GET | `/{id}` | One run, with progress and counts |
| POST | `/{id}/cancel` | Ask a running run to stop |
| GET | `/{id}/entries?category=&attribute=&q=&page=&size=` | A page of one group |
| GET | `/{id}/entries/{seq}` | One entry's stored differences |
| GET | `/{id}/entries/{seq}/live` | One entry, re-read and compared now |
| GET | `/{id}/entries.csv?category=` | CSV export |

## Audit

Starting a run records `INTEGRITY_CHECK` with `detail.kind = "directory-diff"`.
The detail includes the run id, side B's directory and base, and the pairing
mode.
