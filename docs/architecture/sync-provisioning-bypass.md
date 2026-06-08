<!-- SPDX-License-Identifier: Apache-2.0 -->
# Directory sync bypasses the provisioning interceptor chain

## Invariant

The directory-sync engine writes to target directories through **raw LDAP**, not
through the provisioning-plan SPI. `RecomputeEngine` issues `add` / `modify` /
`delete` directly on a borrowed connection obtained via
`LdapConnectionFactory.withConnectionUnreplicated(...)` — e.g.:

```java
// RecomputeEngine.targetAdd(...)
connectionFactory.withConnectionUnreplicated(target, conn ->
    conn.add(new AddRequest(dn, attrs)).getResultCode());
```

It never calls `ProvisioningInterceptorChain`, `PlanExecutor`, or the
`LdapUserService` / `LdapGroupService` write paths.

## Why it matters (IVIA → IVIA sync)

Vendor behaviour — most notably the IVIA addon's `IsvaProvisioningInterceptor`,
which adds a paired **secUser** entry on user create — only runs when a write
goes through the provisioning SPI. Because sync bypasses that SPI:

- **Creating a demographic entry via sync does not create a secUser** on the
  target, even when the target is an IVIA-enabled directory. This is the
  intended guard for the "one IVIA-enabled directory syncing to another"
  scenario.
- A consequence is that sync-provisioned users land on the target as
  **`isva.orphaned`** demographics (demographic present, no paired secUser).
  That state is surfaced by the IVIA badges, the user edit modal, and the
  *Orphaned IVIA Accounts* operational report. Provisioning their secUsers, if
  desired, is a separate, deliberate step (e.g. the IVIA Account tab's *Grant*).

The `ProvisioningContext.suppressVendorOverlay` flag (used by LDIF import to
decline secUser provisioning) is the *explicit* opt-out for callers that **do**
go through the SPI. Sync doesn't need it — it never enters the interceptor path
in the first place.

## How it's enforced

`SyncProvisioningBypassArchitectureTest` (ArchUnit) fails if anything in
`com.ldapportal.ldap.sync..` gains a dependency on the provisioning write SPI
(`ProvisioningInterceptorChain`, `PlanExecutor`, `LdapUserService`,
`LdapGroupService`). If a future change routes sync writes through the
provisioning chain, that test breaks — forcing a conscious decision about
whether vendor interceptors (and thus secUser creation per synced demographic)
should fire for sync.
