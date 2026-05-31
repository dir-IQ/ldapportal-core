# LDAP write surface

**Status:** In progress (R1b chokepoint inventory, 2026-05-30).

This is the authoritative inventory of every class that may issue a
*mutating* LDAP call (`add` / `modify` / `delete` / `modifyDN`) directly
against the UnboundID SDK. Each site carries the
`@com.ldapportal.ldap.annotation.LdapWriteAuthorized` marker, and the
ArchUnit rule `WriteSurfaceCoverageTest` fails the build if any direct
UnboundID write originates from a class *not* on this list.

Capture (replication, audit) is only reliable when the write surface is a
known, enumerated set. **This doc is the audit trail for that set — update
it whenever a site is added or removed**, in the same change.

> **Transitional (R1b).** Today the marker asserts only "this is a known
> write site". R6 will additionally require that every annotated class is
> reachable only via `PlanExecutor`, then retire the wrapper. The list
> below therefore shrinks in R6; the direct-write service entries
> (`LdapUserService`, `LdapGroupService`, `LdapBrowseService`,
> `LdifService`) are the ones R6 folds behind the provisioning-plan SPI.

## Authorized chokepoints

| Class | Package | What it writes | Captured? |
|---|---|---|---|
| `PlanExecutor` | `com.ldapportal.core.provisioning` | Applies `AddStep`/`ModifyStep`/`DeleteStep` for every provisioning plan against a pooled connection. | Yes — via the wrapper the pool hands out. |
| `ReplicatingLdapInterface` | `com.ldapportal.ldap.replication` | The SDK-level capture wrapper itself: passes `add`/`modify`/`delete`/`modifyDN` through to the delegate `LDAPConnection`, then enqueues a replication event on success. | n/a — it *is* the capture point. |
| `ReplicationDelivery` | `com.ldapportal.ldap.replication` | Target-side apply of replicated events, via `withConnectionUnreplicated`. | No — deliberately uncaptured so replicated writes don't loop back. |
| `LdapUserService` | `com.ldapportal.ldap` | Direct user `modify` (`updateUser`) and `modifyDN` (`moveUser`). Create/delete/password go through `PlanExecutor`. | Yes — via `withConnection`. |
| `LdapGroupService` | `com.ldapportal.ldap` | Direct group `createGroup`/`deleteGroup`/`updateGroup` and `removeMember`. Member-add goes through `PlanExecutor`. | Yes — via `withConnection`. |
| `LdapBrowseService` | `com.ldapportal.ldap` | Superadmin directory-browser `createEntry`/`updateEntry`/`deleteEntry`/`deleteSubtree`/`moveEntry`/`renameEntry`. | Yes — via `withConnection`. |
| `LdifService` | `com.ldapportal.ldap` | LDIF import: applies change records and content entries (`add`/`modify`) directly. | Yes — via `withConnection`. |
| `LdapConnectionFactory` | `com.ldapportal.ldap` | Constructs the write surface — wraps pooled connections in `ReplicatingLdapInterface` and hands out the write-capable interface. Issues **no** mutating calls itself; annotated because it holds/produces the surface. | n/a |

## How the rule works

`core/src/test/java/com/ldapportal/architecture/WriteSurfaceCoverageTest.java`
walks every class's outgoing method calls and flags any call to a method
named `add`/`modify`/`delete`/`modifyDN` whose target owner is assignable
to `com.unboundid.ldap.sdk.LDAPInterface` (i.e. `LDAPConnection`,
`FullLDAPInterface`, `LDAPConnectionPool`, …). The owning class must carry
`@LdapWriteAuthorized` (directly or meta-annotated), or the build fails.

If a new caller surfaces, it is either (a) a legitimate chokepoint — add
the annotation **and a row above** — or (b) a real bypass that should route
through an existing chokepoint instead. There are no narrowly-exempted
bypasses today.
