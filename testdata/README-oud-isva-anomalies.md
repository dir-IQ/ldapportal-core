<!-- SPDX-License-Identifier: Apache-2.0 -->
# OUD ISVA anomaly + replication-divergence fixtures

Two LDIF datasets that seed the `oud1-primary` and `oud1-alternate` fixtures
(from `compose.yaml`) with **linked-topology** ISVA/IVIA accounts in a range
of anomalous states, and with deliberate differences between the two instances
so they look like a pair of **out-of-sync replicas**.

| File | Load into | Host port |
|---|---|---|
| `oud-isva-primary.ldif`   | `oud1-primary`   | `localhost:3389` |
| `oud-isva-alternate.ldif` | `oud1-alternate` | `localhost:4389` |

Both share base DN `dc=oud1,dc=example,dc=com` and admin
`cn=Directory Manager` / `admin`. The two instances are **independent** — they
do **not** replicate to each other (see `README-directory-fixtures.md`); the
divergence is hand-authored, which is the point: it lets you test
replica-comparison / drift tooling against a known-divergent pair without
standing up real replication.

## Topology: linked only

Every identity is **two entries on the same server**:

- a plain **demographic** entry under `ou=people` (no `secUser` overlay), and
- a paired **`secUser`** entry under `secAuthority=Default,ou=management`,
  joined to the demographic by its `secDN` back-reference.

All IVIA account state (`secAcctValid`, `secPwdValid`, `secValidUntil`,
`secPwdLastChanged`) lives on the `secUser` side. The lookup the app performs is
`(&(objectClass=secUser)(secDN=<demographic-dn>))` over the management DIT
(`IsvaLinkedUserLookup`), and `IsvaAccountStatusProbe.probeLinked()` derives the
status from the matched `secUser`. So an anomaly is a property of the `secUser`
entry, its **absence** (orphan), or its **duplication**.

> `secUser` is AUXILIARY in the fixture schema (`isva-schema-opendj.ldif`);
> IBM ships it STRUCTURAL. Each management-DIT `secUser` leaf is layered on the
> structural `account` class so it is valid in real OpenDJ/OUD. The app keys on
> the `secUser` objectClass **name** + `secDN`, not the structural carrier.

Configure the directory's ISVA integration as **LINKED**, `secAuthority=Default`,
management DIT base `secAuthority=Default,ou=management,dc=oud1,dc=example,dc=com`,
`secuserRdnAttribute=secUUID`.

## Loading

The OpenDJ container creates the `dc=oud1,…` suffix from `BASE_DN` before
import, so the LDIFs start at the OU layer.

```bash
# primary
docker exec -i ldap-oud1-primary /opt/opendj/bin/ldapmodify \
  -h localhost -p 1389 -D 'cn=Directory Manager' -w admin \
  -a < testdata/oud-isva-primary.ldif

# alternate
docker exec -i ldap-oud1-alternate /opt/opendj/bin/ldapmodify \
  -h localhost -p 1389 -D 'cn=Directory Manager' -w admin \
  -a < testdata/oud-isva-alternate.ldif
```

`-a` adds entries; re-running errors harmlessly on already-present entries
(`entryAlreadyExists`). To start clean:
`docker compose down -v && docker compose up -d oud1-primary oud1-alternate`,
then reload.

Reference "now" for all timestamps is **2026-06-02** (UTC generalized-time).
Every `userPassword` equals the `uid` — local dev only.

## A. ISVA account anomalies

Present on **both** instances unless a divergence note says otherwise. The
"Probe" column is what `IsvaAccountStatusProbe.probeLinked()` reports.

| uid | secUser? | State | Probe result |
|---|---|---|---|
| `alice.normal`      | `alice-uuid` | healthy control | present, acctValid, pwdValid, valid→2030 |
| `bob.orphan`        | none | **orphaned (linked)** — demographic with no paired secUser | `orphaned(LINKED)` |
| `carol.suspended`   | `carol-uuid` | **suspended** — `secAcctValid=FALSE`, validUntil future | present, **not** acctValid |
| `dan.expired`       | `dan-uuid` | **expired** — `secAcctValid=TRUE`, `secValidUntil` in the past | present, validUntil in past |
| `erin.pwdinvalid`   | `erin-uuid` | **password invalid** — `secPwdValid=FALSE` | present, **not** pwdValid |
| `frank.stalepwd`    | `frank-uuid` | **stale password** — valid, `secPwdLastChanged` 2019 | present, very old pwdLastChanged |
| `grace.partial`     | `grace-uuid` | **malformed** — secUser findable but missing `secLogin`/`secAuthority`/`secAcctValid`/`secPwdValid` | present-but-broken (authority null, flags false) |
| `henry.expiringsoon`| `henry-uuid` | **expiring soon** — `secValidUntil` ~3 days out | present, near-expiry |
| `karen.dupe`        | `karen-1` (+`karen-2` on alternate) | **duplicate secUser** (alternate) | present; lookup logs "found N … using the first" |
| _(none)_ `ghost`    | `ghost-uuid` | **dangling / reverse-orphan** — secUser whose `secDN` points at a non-existent demographic | not reached by forward probe; integrity-sweep target |

## B. Replication divergences (primary vs alternate)

| ID | uid / entry | Primary | Alternate | Models |
|---|---|---|---|---|
| **R1** | `liam.onlyprimary` | demographic + secUser present | **absent** | replication lag / failed ADD |
| **R2** | `mona.onlyalternate` | **absent** | demographic + secUser present | replica-only ghost / failed delete |
| **R3** | `alice.normal` (demographic) | `mail=…@oud1.example.com`, `title=Staff Engineer` | `mail=…@old.example.com`, `title=Engineer` | divergent modify / attribute conflict |
| **R4** | `carol.suspended` (secUser) | `secAcctValid=FALSE` | `secAcctValid=TRUE` | lifecycle change not propagated — **still live on one replica** |
| **R5** | `cn=isva-admins` | members: alice, dan, liam | members: alice, mona | partial multi-valued (membership) replication |
| **R6** | `dan.expired` (secUser) | `secValidUntil=2025` (expired) | `secValidUntil=2030` (valid) | renew reached only one replica |
| **R7** | `karen.dupe` (secUser) | one secUser (`karen-1`) | two (`karen-1` + `karen-2`, same `secDN`) | replication-conflict duplicate |

R4 is the security-relevant one: a suspend that lands on only one replica
leaves the account usable wherever the app happens to bind.

## Verify after loading

```bash
# Demographic count (expect 10 on primary, 10 on alternate; sets differ by R1/R2)
docker exec ldap-oud1-primary /opt/opendj/bin/ldapsearch \
  -h localhost -p 1389 -D 'cn=Directory Manager' -w admin \
  -b ou=people,dc=oud1,dc=example,dc=com '(objectClass=inetOrgPerson)' uid

# secUser entries in the management DIT
docker exec ldap-oud1-primary /opt/opendj/bin/ldapsearch \
  -h localhost -p 1389 -D 'cn=Directory Manager' -w admin \
  -b secAuthority=Default,ou=management,dc=oud1,dc=example,dc=com \
  '(objectClass=secUser)' secUUID secDN secAcctValid secValidUntil

# Confirm the karen.dupe duplicate exists only on the alternate
docker exec ldap-oud1-alternate /opt/opendj/bin/ldapsearch \
  -h localhost -p 1389 -D 'cn=Directory Manager' -w admin \
  -b secAuthority=Default,ou=management,dc=oud1,dc=example,dc=com \
  '(&(objectClass=secUser)(secDN=uid=karen.dupe,ou=people,dc=oud1,dc=example,dc=com))' \
  secUUID
```
