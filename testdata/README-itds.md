# ITDS test fixture

Local-development fixture for IBM Directory Server (Tivoli / Security /
Verify Directory). Used by the IBM Directory Server support phases —
see [`../docs/superpowers/plans/2026-05-11-ibm-directory-server-support.md`](../docs/superpowers/plans/2026-05-11-ibm-directory-server-support.md).

The fixture is **opt-in**: `docker compose up` without flags will not
start it. Use the `itds` profile.

## What's inside

| File | Purpose |
|---|---|
| `itds-bootstrap.ldif` | ~20 inetOrgPerson users + 3 groupOfNames groups + 1 `ibm-nestedGroup` + 1 `ibm-dynamicGroup`. |

Designed to exercise the IBM-specific code paths from later phases:
- **P3** (`IbmChangelogStrategy`) — needs the changelog plugin enabled
  manually; see *Enable the changelog plugin* below.
- **P4** (`ibm-allMembers` nested-group resolution) — the
  `cn=allTechStaff` and `cn=allEngineers` groups both surface members
  via `ibm-allMembers`.
- **P5** (operational attribute name normalisation) — every entry has
  `ibm-entryuuid` / `ibm-createtimestamp` auto-populated by the server.

## Bind credentials

| Field | Value |
|---|---|
| Host | `localhost` |
| Port (plain) | `3389` |
| Port (LDAPS) | `3636` (TLS disabled in the default compose env — adjust before using) |
| Base DN | `dc=itdstest,dc=example,dc=com` |
| Admin DN | `cn=root` |
| Admin password | `admin` (override via `LDAP_ADMIN_PASSWORD` env in `docker-compose.yml`) |

User entries each have a password set to their first name (e.g.
`alice.smith` → `alice`). Reset / lengthen before exposing the fixture
to anything beyond local dev.

## Starting the fixture

```bash
docker compose --profile itds up ldap-itds
```

Wait for the *server is ready* log line, then verify with `ldapsearch`:

```bash
ldapsearch -x -H ldap://localhost:3389 \
  -D 'cn=root' -w 'admin' \
  -b 'dc=itdstest,dc=example,dc=com' \
  '(objectClass=inetOrgPerson)' uid mail
```

You should see all 20 users. If the search returns zero entries the
seed step didn't run — see *Manually load the LDIF* below.

## Image availability + licensing

The compose entry points at `icr.io/isva/verify-directory:11.0.0`. IBM
distributes the image through the IBM Container Registry; in most
local-dev contexts it pulls without auth, but some corporate networks
gate `icr.io` behind an IBM Cloud login.

If the pull fails with a 401 or "image not found":

1. **Authenticate** to the IBM Container Registry per
   [IBM's docs](https://cloud.ibm.com/docs/Registry?topic=Registry-getting-started),
   then re-run `docker compose --profile itds pull ldap-itds`.
2. **Self-host the image**: pull it once on an authenticated machine,
   `docker save | docker load` into your local docker, then override the
   compose entry's `image:` to point at the local tag.
3. **Skip ITDS locally** and rely on CI for verification — the support
   phases can be developed against unit tests + the in-memory UnboundID
   server. Real ITDS verification waits until P6 (`@smoke` Playwright)
   in the plan.

CI integration is not part of P0. The P6 smoke spec adds the workflow
step when image availability is sorted; if IBM's licence terms
prohibit CI use, that spec stays local-only and we document the
limitation.

## Manually load the LDIF

The verify-directory image's first-boot seed path varies between
container minor versions. If the LDIF doesn't load automatically:

```bash
# From the host
docker exec ldap-itds ldapadd \
  -x -H ldap://localhost:1389 \
  -D 'cn=root' -w 'admin' \
  -c -f /seed/itds-bootstrap.ldif
```

`-c` (continue on error) is helpful because the base DN may already
exist after the image initialises itself.

## Enable the changelog plugin

ITDS ships with the changelog plugin available but disabled. To enable
(required for P3's `IbmChangelogStrategy`):

```bash
docker exec ldap-itds idscfgchglg -I dsrdbm01 -e
# Or, against a running config:
#   ldapmodify -x -H ldap://localhost:1389 -D 'cn=root' -w 'admin' <<'EOF'
#   dn: cn=ChangeLog,cn=Log Management,cn=Configuration
#   changetype: modify
#   replace: ibm-slapdLogEnabled
#   ibm-slapdLogEnabled: true
#   EOF
```

Verify by searching the changelog:

```bash
ldapsearch -x -H ldap://localhost:3389 -D 'cn=root' -w 'admin' \
  -b 'cn=changelog,cn=ibmpolicies' '(objectClass=changelogentry)' \
  changenumber targetdn changetype
```

The first few `changelogentry` entries are the bootstrap LDIF's own
adds — useful for verifying P3's parser against real ITDS output.

## Resetting the fixture

```bash
docker compose --profile itds down -v
docker compose --profile itds up ldap-itds
```

`-v` removes the `itds_data` volume so the next boot replays the LDIF
seed cleanly.
