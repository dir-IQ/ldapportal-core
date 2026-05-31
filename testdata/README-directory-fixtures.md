<!-- SPDX-License-Identifier: Apache-2.0 -->
# Local directory-server fixtures (compose.yaml)

`compose.yaml` brings up a fleet of **empty** directory servers for
multi-directory / failover testing. **No test data is bootstrapped** —
every directory comes up empty. The OpenLDAP and OUD fixtures get the
IVIA/ISVA fixture schema (`secUser` / `secAuthorityInfo`); AD uses its
own native schema.

> **Authored without a local Docker daemon — UNVERIFIED.** Smoke-test
> with `docker compose up` and use the verification commands below.
> The two most likely things to need the manual fallback are the **OUD
> schema import path** and the **Samba AD image's env contract**.

## Servers & connection details

| Service | ldap / ldaps host port | Base DN | Bind DN / user | Password |
|---|---|---|---|---|
| `openldap-primary`   | 1389 / 1636 | `dc=openldap,dc=example,dc=com` | `cn=admin,dc=openldap,dc=example,dc=com` | `admin` |
| `openldap-alternate` | 2389 / 2636 | `dc=openldap,dc=example,dc=com` | `cn=admin,dc=openldap,dc=example,dc=com` | `admin` |
| `oud1-primary`   | 3389 / 3636 | `dc=oud1,dc=example,dc=com` | `cn=Directory Manager` | `admin` |
| `oud1-alternate` | 4389 / 4636 | `dc=oud1,dc=example,dc=com` | `cn=Directory Manager` | `admin` |
| `oud2-primary`   | 5389 / 5636 | `dc=oud2,dc=example,dc=com` | `cn=Directory Manager` | `admin` |
| `oud2-alternate` | 6389 / 6636 | `dc=oud2,dc=example,dc=com` | `cn=Directory Manager` | `admin` |
| `ad-primary`     | 7389 / 7636 | `dc=samdom,dc=example,dc=com` | `CN=Administrator,CN=Users,DC=samdom,DC=example,DC=com` | `Passw0rd!` |

`primary` + `alternate` are **independent** instances that share a base DN
and credentials (for pointing the app at one or the other); they do **not**
replicate to each other. TLS is off on every fixture (dev only); the
`ldaps` ports are published for convenience but the servers answer plain
LDAP.

## Start

```bash
docker compose up -d            # everything, including the directories
# or just the directories:
docker compose up -d openldap-primary openldap-alternate \
                     oud1-primary oud1-alternate oud2-primary oud2-alternate \
                     ad-primary
```

Reload a fixture from scratch (re-runs schema/init): `docker compose down -v && docker compose up`.

## Verifying the ISVA fixture schema

The schema adds attributes `secLogin secAuthority secAcctValid secPwdValid
secValidUntil secPwdLastChanged secDN secUUID` and object classes
`secUser` (AUXILIARY) + `secAuthorityInfo` (STRUCTURAL).

### OpenLDAP (`openldap-primary` / `openldap-alternate`)

```bash
docker exec ldap-openldap-primary \
  ldapsearch -Y EXTERNAL -H ldapi:/// -b cn=schema,cn=config \
  '(|(olcAttributeTypes=*secAcctValid*)(olcObjectClasses=*secUser*))' 2>/dev/null \
  | grep -E 'secUser|secAcctValid'
```

**Manual fallback** (if the bootstrap didn't add it):

```bash
docker cp testdata/isva-schema-openldap.ldif ldap-openldap-primary:/tmp/isva.ldif
docker exec ldap-openldap-primary ldapadd -Y EXTERNAL -H ldapi:/// -f /tmp/isva.ldif
```

### OUD / OpenDJ (`oud1-*`, `oud2-*`)

```bash
docker exec ldap-oud1-primary \
  /opt/opendj/bin/ldapsearch -h localhost -p 1389 \
  -D 'cn=Directory Manager' -w admin \
  -b cn=schema -s base '(objectclass=*)' objectClasses \
  | grep secUser
```

**Manual fallback** — OpenDJ loads every `config/schema/*.ldif` at boot,
so if `bootstrap/schema` wasn't honoured:

```bash
docker cp testdata/isva-schema-opendj.ldif \
  ldap-oud1-primary:/opt/opendj/config/schema/99-isva.ldif
docker restart ldap-oud1-primary
```

(Repeat for `oud1-alternate`, `oud2-primary`, `oud2-alternate`.)

## Active Directory (`ad-primary`)

Samba provisioned as an AD DC (`nowsci/samba-domain`), realm
`SAMDOM.EXAMPLE.COM`, admin `Administrator` / `Passw0rd!`. No ISVA schema
(AD has its own). Verify the LDAP endpoint:

```bash
ldapsearch -x -H ldap://localhost:7389 \
  -D 'CN=Administrator,CN=Users,DC=samdom,DC=example,DC=com' -w 'Passw0rd!' \
  -b 'DC=samdom,DC=example,DC=com' -s base '(objectClass=*)' defaultNamingContext
```

**Caveats to verify before relying on it:**
- The image's env contract (`DOMAIN` / `DOMAINPASS` / `INSECURELDAP` /
  `NOCOMPLEXITY`) and required privileges (`privileged: true`, `hostname`,
  `dns`) — confirm against the image's current docs; swap the image if you
  prefer another (`instantlinux/samba-dc`, `diegogslomp/samba-ad-dc`).
- Only LDAP ports (389/636) are published. Add `88`, `445`, `464` if you
  exercise Kerberos/SMB.
- `INSECURELDAP=true` permits plain-LDAP simple binds (dev only).

## Pointing the app at a fixture

From the host: `ldap://localhost:<port>`. From another compose service
(e.g. the backend `app`): use the service name + the **container** port —
`ldap://openldap-primary:389`, `ldap://oud1-primary:1389`,
`ldap://ad-primary:389`.
