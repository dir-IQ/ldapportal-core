# OUD test fixture

Local-development fixture for **Oracle Unified Directory (OUD)** support.
Used by the OUD support phases (P0-P5) — see the OUD support design plan.

The fixture is **opt-in**: `docker compose up` without flags will not
start it. Use the `oud` profile.

## Substitution: OpenDJ stands in for Oracle OUD

The container is `openidentityplatform/opendj`, not the official
`container-registry.oracle.com/middleware/oud` image. Rationale:

| Concern | OpenDJ | Oracle OUD |
|---|---|---|
| License | Apache-2.0 (community fork) | OTN — requires registry login + acceptance |
| CI / contributor friction | Anonymous pull | Per-contributor OTN credentials |
| Code lineage | Sun ONE → ODSEE → OpenDJ | Sun ONE → ODSEE → OUD |
| `isMemberOf` operational attr | ✓ | ✓ |
| ODSEE-style `cn=changelog` | ✓ | ✓ |
| `vendorName` in root DSE | `OpenDJ` / no value | `Oracle Corporation` / `Sun Microsystems` |
| ACI dialect | Sun ACI | Sun ACI |

OpenDJ is a direct community fork of ODSEE — the same codebase Oracle
forked to build OUD. The protocol surface this project depends on
(root-DSE capability probing, ODSEE-format changelog, `isMemberOf` for
nested-group resolution) behaves identically. Known divergences are
listed in *Behavioural notes vs real OUD* below; none affect the P0-P5
test paths.

## What's inside

| File | Purpose |
|---|---|
| `oud-bootstrap.ldif` | 15 `inetOrgPerson` users + 3 `groupOfUniqueNames` groups, one of which is a nested aggregate (`cn=allTechStaff`) whose members are the other two groups. |

Designed to exercise the OUD-specific code paths from the later phases:
- **P3** (`DseeChangelogStrategy` reuse) — needs the external changelog
  enabled; see *Enable the changelog* below.
- **P4** (`isMemberOf` nested-group resolution) — `cn=allTechStaff`
  exists only as a parent; users gain it indirectly via membership in
  `cn=engineering` or `cn=design`. OpenDJ populates `isMemberOf` on the
  user entry automatically, including the transitive aggregate.

## Bind credentials

| Field | Value |
|---|---|
| Host | `localhost` |
| Port (plain LDAP) | `3389` |
| Port (LDAPS) | `3636` |
| Base DN | `dc=oudtest,dc=example,dc=com` |
| Admin DN | `cn=Directory Manager` |
| Admin password | `admin` (override via `ROOT_PASSWORD` in `compose.yaml`) |

User entries each have a `userPassword` set to their `uid`
(e.g. `alice.smith` → `alice.smith`). Lengthen / scramble before
exposing the fixture to anything beyond local dev.

## Starting the fixture

```bash
docker compose --profile oud up opendj-oud
```

Wait for the *The Directory Server has started successfully* log line,
then verify with `ldapsearch`:

```bash
ldapsearch -x -H ldap://localhost:3389 \
  -D 'cn=Directory Manager' -w 'admin' \
  -b 'dc=oudtest,dc=example,dc=com' \
  '(objectClass=inetOrgPerson)' uid mail
```

You should see all 15 users. If the search returns zero entries the
seed step didn't run — see *Manually load the LDIF* below.

## Reset to a clean fixture

The bootstrap LDIF is loaded only on **first** volume init. To wipe and
re-seed:

```bash
docker compose --profile oud down -v
docker compose --profile oud up opendj-oud
```

## Enable the changelog (for P3 work)

The openidentityplatform/opendj image ships with the external changelog
**disabled** — it's part of OpenDJ's replication subsystem, which the
image leaves off for a single-node fixture. P3 (`DseeChangelogStrategy`
reuse) only needs the changelog when you want to verify end-to-end
audit-source ingestion against the fixture; the strategy itself is
already wired to handle ODSEE-format entries (see
`DseeChangelogStrategyTest`), so you can skip this for happy-path
verification.

When you do want to exercise the live changelog: OpenDJ's enable
sequence is multi-step (configure a replication server, then a
replication domain on the local backend) and the exact `dsconfig`
incantation varies by build. Rather than transcribe a command that may
drift, follow OpenDJ's *External Change Log* admin guide. You'll know
it's working when:

```bash
docker exec ldap-oud /opt/opendj/bin/ldapsearch \
  -h localhost -p 1389 \
  -D 'cn=Directory Manager' -w 'admin' \
  -b 'cn=changelog' -s one \
  '(objectClass=changeLogEntry)' changeNumber targetDN changeType
```

…returns a non-empty list after you make a write through the UI or
ldapmodify. Once it does, set up an audit source against this
directory with **Changelog Format = Oracle DSEE / cn=changelog** and
**Changelog Base DN = cn=changelog**.

## Manually load the LDIF

If the on-first-init seed didn't run (e.g. the bootstrap directory was
populated by a previous run before this LDIF was added), import by hand:

```bash
docker exec -i ldap-oud /opt/opendj/bin/ldapmodify \
  -h localhost -p 1389 \
  -D 'cn=Directory Manager' -w admin \
  -a < testdata/oud-bootstrap.ldif
```

`-a` adds new entries; existing entries error with
`entryAlreadyExists`, which is harmless if you're re-running after a
partial load.

## Image availability + licensing

`openidentityplatform/opendj` is a community-maintained Apache-2.0
distribution of the OpenDJ codebase, hosted on Docker Hub. Anonymous
pull, no OTN acceptance required. Image tag pinned to `5.1.0-alpine`
so Dependabot's docker ecosystem can surface upgrades; the floating
tag `latest` would silently drift on every rebuild.

If your environment can't reach Docker Hub, mirror the image to your
internal registry and override the `image:` line in `compose.yaml`.

## Behavioural notes vs real OUD

Known divergences from Oracle's OUD distribution that the capability
probe (P2) and per-type dispatch (P3, P4) need to handle gracefully:

- **`vendorName` differs.** OpenDJ advertises `OpenDJ` (or omits the
  value depending on build); real OUD advertises `Oracle Corporation`
  or sometimes `Sun Microsystems`. The probe should record whatever the
  server actually returns rather than fail-fast on a specific string.
- **`supportedExtension` overlap is partial.** Both implement the
  password-modify extension and similar; OUD adds a handful of
  Oracle-specific OIDs (e.g. `2.16.840.1.113894.*`). No P0-P5 code
  depends on those.
- **`cn=monitor` schema differs.** Out of scope until / unless someone
  builds an OUD replication-health alerting checker.

The P5 Playwright `@smoke` spec targets the protocol surface this
project actually uses; behaviour identical to real OUD on those paths.

## When you eventually deploy against real OUD

Nothing in the application code should need to change. Swap the
fixture's `image:` line for the Oracle image, accept the OTN terms in
the registry login, and re-seed. The bootstrap LDIF imports unchanged.
