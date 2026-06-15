# Globex test data — second directory for cross-directory testing

Companion dataset to the 5,000-user **AcmeCorp** tree (`acmecorp-5000-users.ldif`). Together they exercise the cross-directory identity resolution feature (Feature 1, shipped 2026-04-25).

**Story:** AcmeCorp acquired Globex in 2024-Q3. ~30% of Globex employees overlap with AcmeCorp records (joined on `mail` or `employeeNumber`); the rest are Globex-only. Some overlaps have data-quality issues that produce ambiguous matches.

## Schema decision (read first)

Originally planned with native AD attributes (`sAMAccountName`, `userPrincipalName`, `userAccountControl`, `pwdLastSet`, `accountExpires`, `objectGUID`). Stock OpenLDAP rejects those — the `extensibleObject` aux class lets you USE undefined attributes, but only if they're defined SOMEWHERE in the schema. AD attributes aren't.

**Workaround:** every AD-shape concept is encoded into stock `inetOrgPerson` attributes:

| AD concept | Encoded as |
|---|---|
| `userAccountControl=512` (enabled) | `employeeType: Full-Time` |
| `userAccountControl=514` (disabled) | `employeeType: Disabled` + `description: ad-shape: UAC=514 (DISABLED)` |
| `userAccountControl=66048` (svc no-expire) | `employeeType: Service` + `description: ad-shape: UAC=66048 (DONT_EXPIRE_PASSWORD)` |
| `pwdLastSet=0` (must change) | `description: ad-shape: pwdLastSet=0 (must change at next logon)` |
| `accountExpires` past | `employeeType: Expired` + `description: ad-shape: accountExpires=<filetime> (in past)` |
| `sAMAccountName` | `uid` (acts as the same login-identifier semantically) |
| `userPrincipalName` | `mail` (already the primary email) |
| `objectGUID` | (omitted) |

If you want real AD attributes, load a samba/AD schema into your slapd config and run a transform pass over these LDIFs to rename/restructure. The cross-directory feature reads attributes by name, so config-side it doesn't care which schema produced the values.

---

## Files

| File | Purpose | Size |
|---|---|---|
| `globex-250-users.ldif` | Bulk dataset — 250 users + 8 groups + 5 dept OUs. Stock `inetOrgPerson`; AD-shape encoded in `employeeType` + `description` (see schema decision above). | ~5,200 lines |
| `globex-acquisition-overlaps.ldif` | Curated demo personas (5 named users) + group additions. The keystone entries to point at on a demo call. | ~200 lines |
| `globex-cleanup.ldif` | Removes the demo-persona group memberships so `globex-acquisition-overlaps.ldif` can be re-imported. | ~50 lines |
| `globex-cleanup-delete.sh` | Removes the demo-persona entries (run after `globex-cleanup.ldif`). | ~30 lines |
| `generate-globex.py` | Generator for `globex-250-users.ldif`. Deterministic (`seed=0xACE0F1E1`). Run if you want to regenerate the bulk file. | ~430 lines |

There's also a small auto-loaded fixture at `ee/src/test/resources/ldap/globex-baseline.ldif` (~10 deterministic users, suffix `ou=globex,dc=test,dc=local`). That one boots automatically into the OpenLDAP test container alongside `baseline.ldif` and is what Playwright `@smoke` tests assert against. The `testdata/` files in this README are for manual dev/demo loads against your local LDAP.

---

## Loading

### Prerequisites

Your local LDAP server must be configured to serve **two suffixes**: `dc=acmecorp,dc=com` (existing) and `dc=globex,dc=com` (new). With the bitnami / osixia OpenLDAP images, set both base DNs in `docker-compose.yml`:

```yaml
LDAP_BASE_DN: "dc=acmecorp,dc=com"      # for the AcmeCorp container
LDAP_BASE_DN: "dc=globex,dc=com"        # for a separate Globex container
```

Or, if running a single multi-suffix slapd, add `dc=globex,dc=com` as an additional database via `slapd.conf` / `cn=config`.

### Load order (Globex side)

```bash
# 1. Bulk users + groups + OUs
ldapadd -x -D "cn=admin,dc=globex,dc=com" -w <password> \
  -f testdata/globex-250-users.ldif

# 2. Demo personas (modifies groups + adds 6 named users)
ldapmodify -x -D "cn=admin,dc=globex,dc=com" -w <password> \
  -a -f testdata/globex-acquisition-overlaps.ldif
```

### Re-load (cleanup first)

```bash
# 1. Remove demo-persona group memberships
ldapmodify -x -D "cn=admin,dc=globex,dc=com" -w <password> \
  -f testdata/globex-cleanup.ldif

# 2. Delete demo-persona entries
bash testdata/globex-cleanup-delete.sh "cn=admin,dc=globex,dc=com" <password>

# 3. (Or, nuclear: drop the whole tree)
ldapdelete -x -D "cn=admin,dc=globex,dc=com" -w <password> \
  -r dc=globex,dc=com
```

---

## Composition

### Bulk file (`globex-250-users.ldif`)

| Cohort | Count | Cross-directory join behaviour |
|---|---|---|
| **EXACT-tier overlap** | 75 | Same `mail` as an AcmeCorp user. Resolver matches on primary key → EXACT badge. |
| **HIGH-tier overlap** | 25 | Same `employeeNumber` as an AcmeCorp user, different `mail` domain (post-acquisition rebranding). Resolver falls through to secondary key → HIGH badge. |
| **AMBIGUOUS pairs** | 10 (5 × 2) | Two Globex users share the same `employeeNumber`. Resolver can't pick a unique winner → AMBIGUOUS badge. |
| **Globex-only** | 140 | No AcmeCorp counterpart. Renders as unresolved single-directory candidates. |
| **Edge cases (within Globex-only)** | (subset) | 2 disabled (`employeeType=Disabled`), 2 must-change-password (description-encoded), 1 expired (`employeeType=Expired`). |
| **Service accounts** | 3 | `employeeType=Service`. `svc-backup`, `svc-monitoring`, `svc-ci-runner`. |

### Filtering on AD-shape concepts

To find all "disabled" users in browse/search:

```
(employeeType=Disabled)
```

To find "must-change-password" users:

```
(description=*pwdLastSet=0*)
```

The cross-directory secondary key is `employeeNumber` (a real defined attribute on stock `inetOrgPerson`'s parent class hierarchy).

### Anchored EXACT overlaps (real AcmeCorp counterparts)

These six were verified against the AcmeCorp 5K dataset and overlap by `employeeNumber`. Use them in tests or demos when you want to point at a specific known counterpart on the AcmeCorp side.

| Globex `uid` | AcmeCorp counterpart | `employeeNumber` |
|---|---|---|
| `glx-jack.roberts` | `jack.roberts@acmecorp.com` (Engineering) | `EMP000100` |
| `glx-danielle.perez` | `danielle.perez@acmecorp.com` (Engineering) | `EMP000200` |
| `glx-marie.anderson` | `marie.anderson@acmecorp.com` (Engineering) | `EMP000500` |
| `glx-nancy.allen` | `nancy.allen@acmecorp.com` (Engineering) | `EMP001000` |
| `glx-denise.nelson` | `denise.nelson@acmecorp.com` (Sales) | `EMP002000` |
| `glx-victoria.roberts2` | `victoria.roberts2@acmecorp.com` (CustomerSupport on AcmeCorp; Operations on Globex) | `EMP004000` |

The other 69 EXACT-tier users use synthesized `*@acmecorp.com` mails; the resolver joins on mail regardless of whether the AcmeCorp side actually has the counterpart entry.

---

## Demo personas (`globex-acquisition-overlaps.ldif`)

Six named users designed for screen recordings and demo calls. Each has a `description` starting with `DEMO —` and a deliberately on-the-nose name.

| `uid` | Tier | Story |
|---|---|---|
| `glx-demo-exact` | EXACT | Joins to `acmecorp/jack.roberts` by mail. Single-row resolver output, EXACT badge, both directories side-by-side. |
| `glx-demo-high` | HIGH | Joins to `acmecorp/danielle.perez` by `employeeNumber`. Mail differs (`@globex.com` vs `@acmecorp.com`). |
| `glx-demo-ambig-1` + `glx-demo-ambig-2` | AMBIGUOUS | Both share `employeeNumber=EMP000500` (which is `marie.anderson` on AcmeCorp). Spelling diverged at migration ("Anderson" vs "Andersen"). |
| `glx-demo-orphan` | (none) | Globex-only. No counterpart. Renders as unresolved card — useful contrast against the resolved tiers. |
| `glx-demo-offboard` | (none) | Disabled (UAC=514) but member of `globex-finance-readonly` + `globex-leadership`. Stages data for the future Feature 2 (multi-system orchestrated offboarding) demo. Today renders as "disabled but in sensitive group" in integrity check. |

---

## Feature coverage

| Feature | Coverage |
|---|---|
| User browse / search / view | ✅ 250 users across 5 OUs, paginates |
| Group browse / nested groups | ✅ 8 groups; `globex-all-staff` is nested (contains other groups by DN) |
| OU hierarchy | ✅ 3 levels: `dc=globex,dc=com → ou=People → ou={dept}` |
| AD-style attributes | ✅ All carried via `extensibleObject` |
| Disabled accounts (UAC=514) | ✅ 2 in bulk, 1 demo persona |
| Must-change-password (pwdLastSet=0) | ✅ 2 in bulk |
| Expired accounts (accountExpires past) | ✅ 1 in bulk |
| Service accounts (UAC bit set) | ✅ 3 in `ou=ServiceAccounts` |
| **Cross-directory EXACT** | ✅ 75 + 1 named demo persona |
| **Cross-directory HIGH** | ✅ 25 + 1 named demo persona |
| **Cross-directory AMBIGUOUS** | ✅ 5 pairs + 1 named demo pair |
| **Cross-directory Globex-only** | ✅ 140 + 1 named demo persona |
| Internationalisation (UTF-8 attrs) | ✅ Globex-eve in test fixture (Évelyne Müller / München) |
| Lifecycle playbook target | ✅ `glx-demo-offboard` (Feature 2 prep) |
| Drift / SoD / integrity checks | ⊘ Already covered by AcmeCorp scenarios; not duplicated here |

---

## Regenerating the bulk file

The bulk file is committed as a static LDIF (matching the AcmeCorp pattern), but it's reproducible. The generator uses `random.seed(0xACE0_F1E1)` for byte-identical output across runs.

```bash
python testdata/generate-globex.py > testdata/globex-250-users.ldif
```

Bump the seed in the script if you want a different shuffle (you'd lose the named "anchored EXACT" mappings in the table above unless you preserve those ranges manually).

---

## Auto-loaded test fixture

`ee/src/test/resources/ldap/globex-baseline.ldif` is the smaller deterministic version that auto-loads into the OpenLDAP test container. It uses the suffix `ou=globex,dc=test,dc=local` (subtree under the existing `dc=test,dc=local`) so a second slapd database isn't needed for tests.

The auto-loaded fixture is referenced by name in:

- The cross-directory `@smoke` Playwright test — joins `seedAlice` (acmecorp side at `ou=seed,ou=test,dc=test,dc=local`) ↔ `globex-alice` (Globex side at `ou=globex,dc=test,dc=local`)
- Future integration tests for Feature 1 sub-project 3 (config UI)

The 10 users in `globex-baseline.ldif` mirror the same EXACT/HIGH/AMBIGUOUS/Globex-only/edge-case pattern as the bulk file, but at minimum scale for fast test boot and assertable-by-name determinism.
