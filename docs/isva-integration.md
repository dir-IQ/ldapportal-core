# ISVA full-mode integration

LDAP Portal can speak the IBM Security Verify Access (ISVA, formerly Tivoli
Access Manager) user-management protocol so that ISVA picks up users
provisioned by LDAP Portal as if `pdadmin user import` had been run for
each. This document is for the operator who's enabling the integration
on a directory — what to set up first, what the config knobs mean, and
what to do when something looks wrong.

This is an open-source addon under [`addons/isva`](../addons/isva). It
ships in the **community-plus-isva** distribution; the plain **community**
distribution doesn't include it.

## Decide: inline or linked topology?

Two ways to lay ISVA on top of an existing directory:

| Topology | Shape | Use when… |
|----------|-------|-----------|
| **Inline** | Demographic + `secUser` attributes co-exist on one LDAP entry. The directory carries the `secUser` objectClass alongside `inetOrgPerson` etc. | You control the directory schema. Greenfield deployments. Single-DIT shops. Easiest day-2 — there's only one entry per user to reason about. |
| **Linked** | Demographic entry stays where it is; ISVA's `secUser` entries live in a separate management DIT. The two are paired via the `secDN` back-reference. | You can't change the demographic-side schema (third-party HR system, multi-tenant directory). Multi-DIT shops where the demographic side is read-only-from-ISVA's-perspective. |

Both modes implement the same five lifecycle ops (create, delete,
password-set, group-add, group-remove). Linked mode does extra LDAP
round-trips per op to resolve the paired secUser DN — measurable on
high-throughput batches but invisible on individual operator actions.

If you're unsure, **inline** is the right default. Switch to linked only
when an existing constraint forces it.

## Prerequisites

Before flipping the toggle, the target directory must:

1. **Have the secSchema loaded.** ISVA ships `secschema.ldif` (or
   `secschema.def` in older releases). Apply it to your directory's
   schema *before* enabling — without `secUser`, `secAcctValid`,
   `secValidUntil`, `secPwdLastChanged`, `secPwdValid`, `secLogin`,
   `secDN`, the very first user provisioned will fail with
   `LDAPException: object class violation`. Most directories take
   the LDIF via the usual schema-load tool:
   - OpenLDAP: `ldapadd -Y EXTERNAL -H ldapi:/// -f secschema.ldif`
   - 389DS: `dsconf … schema attributetypes add …` (run the script
     IBM provides, not the LDIF directly).
   - AD/AD LDS: import via `ldifde` or the Schema MMC.
2. **Have a service account with write access** to the demographic
   subtree (and to the management DIT for linked mode). LDAP Portal's
   existing directory connection's bind DN already has this for the
   demographic side; for linked mode the *same* bind DN typically
   needs scope over the management DIT too. Check your bind DN's
   ACLs against `<managementDitBaseDn>` before saving the linked-
   mode config.
3. **Decide the `secUser` object classes.** The default is just
   `secUser`. Deployments whose registry names entries on a non-stock
   attribute need the object class that defines it too — e.g. `eUser`,
   which contributes `principalName`. This applies to both topologies.
4. **For linked mode only:** decide the `secUser` RDN attribute and its
   value source — the attribute *name* (`secUUID`, `secLogin`, or any
   attribute your object classes permit, e.g. `principalName`) is now
   independent of where the *value* comes from (a generated UUID, or the
   user's `uid`). Also decide the `groupMemberTarget` (whether group
   `member`/`uniqueMember` values should point at demographic DNs or at
   the paired secUser DN). The choices are policy-driven and have
   different ACL implications. Talk to your ISVA admin first, and use
   the **Probe** button — it validates your object classes and RDN
   attribute against the live server schema before you enable
   provisioning.

## Config reference

Save the directory config via the **Verify Access integration** action
in the directory list (`/superadmin/directories`). Field by field:

| Field | What it controls |
|-------|------------------|
| **Enabled** | Master switch. With this off the interceptor is dormant and provisioning behaves identically to a no-ISVA directory. Use this to stage a config before enabling. |
| **Topology mode** | `INLINE` vs `LINKED` (see above). Toggling from one to the other clears the linked-only fields below so a stale value can't haunt you. |
| **secAuthority** | Domain name written to `secAuthority` on every entry. Defaults to `Default`, which matches a vanilla ISVA install. Override if your ISVA deployment is sliced into multiple authorities. |
| **Default valid-until (years)** | Provisioning sets `secValidUntil` to `now + N years` on new users. The default of 100 effectively means "never expires"; lower it to enforce a periodic re-credentialling cadence. |
| **Delete policy** | `DISABLE` (soft) flips `secAcctValid=FALSE` + `secValidUntil=now`; `HARD_DELETE` issues a real LDAP DEL. Default is `DISABLE` — the safer choice for environments that need an audit trail for departed users. |
| **Require secGroup** | When on, every group-membership write first checks the target group for `objectClass: secGroup` (ISVA's group representation) and refuses with a 422 when it's missing — the LDAP write would succeed but ISVA would silently ignore the membership. Remediate by adding the secGroup overlay to the group (`pdadmin group import`) or turning the flag off. A group the bind can't read is let through; the write then fails (or succeeds) on the server's own verdict. Off (the default) matches deployments that manage groups outside of ISVA. |
| **secUser object classes** | The objectClass set written to the secUser identity — overlaid onto the demographic entry (inline) or stamped on the paired entry (linked). `secUser` is always present (re-added if you remove it); add others your schema needs, e.g. `eUser` to bring in `principalName`. Applies to both modes. |
| **Management DIT base DN** *(linked only)* | The subtree under which secUser entries live (e.g. `secAuthority=Default,o=acme,c=us`). The bind DN needs write access here. Required when topology mode is LINKED — the server will reject a save without it. |
| **secUser RDN attribute** *(linked only)* | The attribute that names secUser entries — free-form. `secUUID` (default) and `secLogin` are the stock choices; any attribute your configured object classes permit works (e.g. `principalName` from `eUser`). |
| **RDN value source** *(linked only)* | Where the RDN value comes from, independent of the attribute name. `GENERATED_UUID` (default) mints an opaque UUID per user; `UID` mirrors the user's `uid` (a rename then forces a directory rename). `secUUID`+`GENERATED_UUID` and `secLogin`/`principalName`+`UID` are the natural pairings. |
| **Group member target** *(linked only)* | `DEMOGRAPHIC_DN` writes the user's demographic DN into group member attributes; `SECUSER_DN` writes the paired secUser DN. The right answer depends on how your ACLs resolve group membership — ISVA's own ACLs follow `SECUSER_DN`, but if your application checks groups against demographic DNs, use that instead. |
| **On demographic delete** *(linked only)* | What to do on the demographic side when a delete-with-DISABLE-policy fires. `LEAVE` leaves the demographic entry alone (only the secUser is disabled). `DISABLE_AND_MARK` is reserved for a future v1.1; today it logs and treats as `LEAVE`. |

### Deployment setting: exposed topology modes

The fields above are per-directory and saved through the UI. One option is set
at the **deployment** level instead, via an environment variable read by the
addon:

| Env var | Default | Effect |
|---------|---------|--------|
| `APP_ISVA_EXPOSED_TOPOLOGY_MODES` | `linked` | Comma-separated subset of `inline,linked` — which topology modes the integration page offers. When only one is listed the **Topology mode** selector is hidden and that mode is pinned (the "Topology mode" row above won't appear). |

This is **UI-only**: it controls what the config page presents, not what the
API accepts — a value already persisted via the API (or a prior deployment
setting) is preserved even if its mode is no longer exposed, and the page shows
a note rather than silently dropping it. Bound from `app.isva.*` and owned by
the addon, so it has no effect on community (non-ISVA) builds. Unknown or empty
values fall back to `linked`.

## Scoping ISVA per provisioning profile

The directory **Enabled** flag above is all-or-nothing: with it on, *every*
user create / delete / password / group operation in that directory produces
ISVA writes. Real directories are mixed, though — a single directory often
hosts populations that should be ISVA-managed (employees under one profile)
alongside ones that should not (service accounts, contractors under another).

To handle that, each **provisioning profile** carries an ISVA override,
layered on the directory-level toggle:

| Setting | Effect |
|---------|--------|
| **Inherit** (default) | Follow the directory. A profile with no override set inherits whatever the directory **Enabled** flag says. |
| **Exempt** (`FORCE_OFF`) | Fully exempt this profile from ISVA in an otherwise ISVA-enabled directory. Create / delete / password / group operations for entries under the profile are plain LDAP — no `secUser` writes, no soft-disable, no linked-mode secUser lookup — exactly as if ISVA weren't installed. |

The override is **narrowing-only**: a profile can turn ISVA *off* for itself,
but it cannot turn ISVA *on* in a directory where the master **Enabled** flag
is off. Disabling ISVA on the directory is therefore guaranteed off
everywhere — it stays the authoritative kill-switch.

Set it from the **superadmin profile editor** (`/superadmin/profiles` → edit a
profile → "Exempt this profile from … provisioning" on the General tab). The
control only appears when the ISVA addon is installed **and** the profile's
directory has ISVA enabled.

> ⚠️ **Forward-looking caveat — don't exempt an actively-provisioning profile
> without a reconcile.** The override is evaluated per operation, not
> retroactively. If a profile has already created `secUser` entries and you
> then flip it to **Exempt**, subsequent deletes / password changes for those
> entries skip ISVA and leave the `secUser` side unmaintained (orphaned). Today
> this is safe because no deployed directory has provisioned `secUser` entries
> yet; once one has, treat an Inherit → Exempt transition as needing a reconcile
> (deferred — tracked with the entitlement-model work). Exempting a profile
> *before* it provisions anything has no such risk.

## Bulk and LDIF import behavior

Both bulk paths create users through the provisioning-plan SPI, so ISVA's
interceptor fires and `secUser` overlays are provisioned for imported users
exactly as they are for a single create:

- **CSV bulk import** routes every row through `LdapUserService.createUser`,
  so an ISVA-enabled directory gets `secUser` provisioning per row.
- **LDIF import** routes **user** entries (identified by the directory's
  configured *user object classes* — see below) through the same path.
  Non-user entries (OUs, groups, containers), `modify`/`delete`/`modifyDN`
  change records, and conflict overwrites are written as-is.

**Operator control.** When the IVIA addon is enabled, the LDIF import dialog
shows a *"Provision IVIA accounts (secUser) for imported users"* checkbox,
on by default. Unchecking it suppresses the overlay for that import (the
records are written as-is) — use this when re-importing an export that
already contains `secUser` entries.

**Safety on re-import.** If the LDIF file itself already contains any
`secUser` entries (a prior inline or linked-mode export), import detects this
and writes everything as-is — it does **not** layer a fresh overlay on top.
This avoids duplicating the overlay and, in linked mode, avoids the secUser
`ADD` colliding with the entry already in the file (which would trigger the
compensating delete of the just-added demographic).

**Which entries are users** is governed per directory by the configurable
*user / group object classes* (directory editor → Advanced settings),
pre-populated with vendor-appropriate defaults. The same configuration drives
user/group classification in search and the dashboards.

## Verifying it works

After saving the config:

1. **Probe.** Use the "Probe" button on the config panel. It connects
   to the directory and validates the configured `secUser` object
   classes against the server schema — that every class exists, that
   the set contains at most one STRUCTURAL inheritance chain (two
   unrelated structural classes — e.g. `secUser` + `eUser` on a server
   whose `secUser` is not `SUP eUser` — would make the server reject
   every secUser write with "multiple conflicting structural
   objectclasses") — and, in linked mode, that the configured RDN
   attribute is permitted by one of those classes (so a
   `principalName` RDN without the `eUser` class is caught here, not at
   provisioning time) and that the management DIT is reachable. A green
   probe doesn't *guarantee* every operation will succeed, but a red
   probe always points at one of the prereqs above. (The schema verdict
   shows as **yes / no / unknown** — *unknown* means the server didn't
   return its schema to the bind DN.)
2. **Provision a test user.** Create one through LDAP Portal's normal
   user-create flow. Inspect the resulting entry in your directory
   browser — you should see the `sec*` attributes alongside the
   demographic ones (inline mode) or two entries paired by `secDN`
   (linked mode).
3. **Soft-disable a test user.** Delete the user with the
   policy=DISABLE config. Confirm `secAcctValid=FALSE` and
   `secValidUntil=<now in generalizedTime>` on the secUser entry.
4. **Check the audit log.** Every row touching the ISVA-enabled
   directory carries `details.vendorIntegration = "ISVA"`. Soft-
   disable rows additionally carry `details.softDisable = true`.
   When the create / delete was driven by a provisioning profile the
   row also carries `details.profileId = "<uuid>"` so reports can
   slice by profile.
   These are JSON tags on the existing `audit_events.detail` column
   — query directly in Postgres or filter via the audit-log UI.

## Reserved attribute namespace: `isva.*`

In LINKED-mode directories, user reads are enriched with attributes
from the paired `secUser` entry (the "joined read" feature). Those
attributes always surface under the **reserved `isva.*` namespace**
— `isva.secLogin`, `isva.secAuthority`, `isva.orphaned`, etc. —
regardless of which attributes the provisioning profile's
`attributeConfigs` lists as visible. This is intentional: the
profile attribute config drives the *create form* and the *list-
view columns*, but the joined-read data is a faithful representation
of the underlying directory state and must be available for raw
viewers (Directory Browser, API consumers, audit replay).

If you don't want `isva.*` keys surfacing through a particular
viewing surface, filter them at the UI / consumer layer — the
`isva.` prefix is unique to the ISVA addon and never collides with
native LDAP attributes.

## Troubleshooting

**`Schema-validation: missing attribute [secUser]` / object class
violation on first create.** The secSchema isn't loaded into the
directory. Apply `secschema.ldif` (see Prerequisites) and re-probe.

**Probe fails with "management DIT not reachable" in linked mode.**
Either the bind DN doesn't have read access under
`managementDitBaseDn`, or the DN itself is wrong. SSH into a host
with `ldapsearch` and run a manual bind against the management
subtree to isolate which.

**`No linked secUser entry found for demographic DN …` on
delete / password-set / group-add.** You have an orphan demographic
entry: a user exists on the demographic side without a paired secUser
in the management DIT. Two paths:
- Repair via `pdadmin user import <uid>` — ISVA will create the
  missing secUser using whatever defaults its config says.
- Hard-delete the demographic via LDAP Portal (switch delete policy
  to `HARD_DELETE` temporarily, delete, switch back). Hard-delete is
  the operator's escape hatch for orphan cleanup.

**`Group target is configured as SECUSER_DN, but no linked secUser
entry was found …` on a group-membership add.** Same orphan issue,
but caught at group-add time instead of delete. Same two fixes.

**Group memberships disagree between the demographic and secUser
sides.** Probe re-runs a small consistency check; what you're likely
seeing is a `groupMemberTarget` change after groups were already
populated. The new convention applies only to new memberships;
existing memberships keep whichever DN was written at the time.
Either accept the mixed state, or write a one-shot script to rewrite
existing memberships to the new convention.

**Audit rows for the ISVA-enabled directory aren't carrying
`vendorIntegration`.** The addon's jar isn't on the classpath of the
running backend. Check the distribution: the plain `community/`
distribution doesn't ship `addons/isva`. Use `community-plus-isva/`.

## See also

- Deployment runbook: [`docs/deployment-fly.md`](deployment-fly.md) — the
  `ldapportal-ci` Fly demo runs `community-plus-isva` for prospects who
  want to evaluate ISVA with no license key.
