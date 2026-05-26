# OpenLDAP server management — candidate features

**Status:** Idea-stage. Not on the roadmap, no spec, no plan. Captured
here so the thinking isn't lost between sessions.

LDAP Portal today is excellent at the **data plane** — the contents of
the directory (users, groups, lifecycle playbooks, audit, cross-
directory identity, schema browsing). It does little for the
**control plane** — the OpenLDAP server itself: the `slapd` daemon,
its `cn=config` tree, replication topology, ACLs, indexes, overlays,
backends, monitoring, backups. Operators reach for SSH + LDIF +
nerves for any of that.

This doc lists candidate Tier 1 features that would extend LDAP Portal
into control-plane territory **while playing to its existing
strengths**: multi-directory awareness, modern Vue UI, an audit/
alerting backbone, and SUPERADMIN auth gating. Each item is sized
roughly at the cross-directory identity feature's complexity (one
sub-project = a handful of weeks).

When something here gets picked up:

1. Move it from this doc into [`docs/enterprise-roadmap.md`](enterprise-roadmap.md)
   under the right edition + status column.
2. Brainstorm into a design spec at
   `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`.
3. Plan into `docs/superpowers/plans/YYYY-MM-DD-<topic>.md`.
4. Implement via the subagent-driven-development workflow.

---

## 1. Multi-server health dashboard

**What.** A single pane of glass showing the health of every
configured OpenLDAP instance: connections active, ops/sec, db cache
hit ratio, db size, memory headroom, replication lag, last config
change, version, uptime. Per-directory drill-down. Coloured status
chips at the top of each directory's tile.

**Why it fits.** LDAP Portal already manages N directory connections.
Aggregating their `cn=monitor` data into one view is the obvious
extension. The alerts subsystem is already in place — alert rules
can fire on dashboard metrics with no new pipeline.

**What's needed.**
- A `cn=monitor` reader service (UnboundID SDK; the schema is
  documented but version-variant — robust parser is its own small
  project).
- A new "Server health" route in the SUPERADMIN nav, gated by
  whichever entitlement covers control-plane features.
- Periodic poll (server-side scheduler), persisted into the existing
  metrics store if one exists, otherwise a small new table.
- Frontend dashboard page reusing existing `MetricCard` and panel
  scaffolding.

**Scope wrinkle.** `cn=monitor` is sometimes ACL-restricted — bind
DN may need `monitor` access at the suffix. Document the prereq;
optionally provide a "test connection to cn=monitor" button.

**Effort.** Medium. Maybe 1 sub-project on the order of SP2 of
cross-directory identity.

---

## 2. Replication topology + lag monitor

**What.** A graph visualisation of provider/consumer relationships
across the configured servers, with live lag per replica, conflict
detection, and historical lag charts. Click a node → full server
detail. Click an edge → replication-agreement details + "test sync"
button.

**Why it fits.** Replication is the #1 ops concern in any
multi-server OpenLDAP deployment, and it's almost always debugged
via `ldapsearch` against `cn=config` and grep against logs. A live
graph + charted lag would be a real productivity win, particularly
paired with the existing alerting infrastructure.

**What's needed.**
- Topology discovery: walk each server's `olcSyncrepl` and
  `olcSyncProv` config to enumerate edges.
- Lag computation: compare `contextCSN` between provider and each
  consumer, periodically.
- A graph-rendering library on the frontend (Cytoscape, vis-network,
  or D3 hand-rolled — pick one that fits Tailwind).
- New routes + alert-rule integrations ("alert when lag > 60s for
  5 minutes").

**Scope wrinkle.** Multi-master / mirror-mode topologies have edges
in both directions and look ugly in naive graph layouts. Plan for a
"role" labelling (provider, consumer, peer) and consider a tabular
view as a fallback.

**Effort.** Medium-large. ~1.5 sub-projects.

---

## 3. ACL viewer + access simulator (read-only first)

**What.** Two halves:

1. **ACL viewer.** Renders `olcAccess` directives as a sortable rule
   list per database. Each rule shows: who (by clause), what
   (`access` clause), targets (filter, attrs, dn.regex/dn.exact),
   evaluation order. Surfaces overlap warnings and shadowed rules.

2. **Access simulator.** A form: "If user `<DN or pick from list>`
   attempts `<read|write|search|compare|...>` on `<entry>` `<attr>`,
   would it pass?" Output: PASS/FAIL with the specific rule(s) that
   matched, and a "why" trace.

The simulator is the killer feature. Every LDAP admin has spent
hours debugging a 403 by guessing which rule fired.

**Why it fits.** ACLs are notoriously the most confusing part of
operating OpenLDAP. There's no decent existing tooling. LDAP Portal's
existing audit + reports infrastructure pairs naturally with a
"saved access tests" feature that runs as a regression check on
every config change.

**What's needed.**
- `olcAccess` parser (the syntax is documented but tricky — quote
  handling, regex escaping, `by` clause permutations).
- A Java-side ACL evaluator that mirrors slapd's semantics. There
  are reference implementations in academic libraries; verify against
  slapd's actual behaviour with a test corpus.
- New routes: ACL list per directory, simulator form, saved-test
  history.

**Why read-only first.** Editing live ACLs is dangerous; "viewer
only" is high-value-now and de-risks the bigger editor PR later.
Edit support comes in a follow-up sub-project once the parser/
evaluator is battle-tested.

**Effort.** Medium. ~1 sub-project for read-only + simulator;
follow-up sub-project for the editor.

---

## 4. Slow query log analyzer

**What.** Ingests `slapd` log output (level: stats), parses query
patterns, groups by filter shape, and surfaces:

- Top 20 slowest queries by P95 latency.
- Queries returning no results consistently (often a misconfigured
  filter).
- Queries lacking an index (suggested `olcDbIndex` to add).
- Queries that scan the whole database (bad filter design).

Each finding has a "view sample queries" drill-down and (optionally)
a "create alert rule" button.

**Why it fits.** This pattern is well-trodden in the SQL world (slow
query log analysis tools), but for LDAP it's largely "tail the log
and squint." LDAP Portal already has reports + alerts — this is a
specialised report category.

**What's needed.**
- A log shipper or pull mechanism. **Note:** the existing audit-
  source pipeline is changelog-specific (Oracle DSEE / OpenLDAP
  accesslog readers) and can't ingest syslog or arbitrary slapd
  logs — generic log ingestion would require new infrastructure.
- Parser tuned to the slapd log format(s); version variance.
- Index-recommendation engine — given a filter shape, what indexes
  would speed it up.
- New "Slow queries" report under the existing reports nav.

**Scope wrinkle.** Requires `loglevel stats` on the server — that's
not always on by default and adds non-trivial log volume. Document
the trade-off and provide a "sampling mode" for high-traffic
deployments.

**Effort.** Medium. ~1 sub-project, or smaller if generic log
ingestion already exists.

---

## 5. Backup orchestration

**What.** Schedule regular `slapcat` dumps per directory; retention
policies (keep N daily, M weekly, K monthly); on-demand backup with
progress; restore wizard with safety guards (dry-run, target
directory selection, conflict detection on existing entries). Backup
artefacts go to S3 / NFS / local filesystem (configurable).

**Why it fits.** Backups are a control-plane concern that LDAP Portal
is well-positioned to own — it already has scheduling (alert rules,
playbooks). S3 integration exists but is scoped to report exports
via `S3UploadService`; **there is no generic blob-store abstraction**
that a backup orchestrator can reuse, so a small artefact-storage
service is part of this scope. A scheduled-jobs view + a restore
wizard fits the "operator daily-driver" framing.

**What's needed.**
- Server-side scheduler invocation of `slapcat` on the LDAP server's
  filesystem. **Architecture wrinkle:** LDAP Portal runs on a different
  host from the LDAP server, so this needs either an agent component
  on the LDAP host (significant new infrastructure) or remote slapcat
  via LDAP (no — slapcat is filesystem-direct, the LDAP equivalent is
  a paged dump via a privileged bind, which is a different code path).
- Restore wizard: parse the LDIF, diff against current directory
  state, preview, confirm, apply.
- New "Backups" route per directory; new global "Backup schedules"
  view.

**Scope wrinkle.** The agent question is real and significant.
Either:
- Ship "LDIF dump via LDAP" (works without an agent, slower, doesn't
  capture indexes or `cn=config`), OR
- Define an "LDAP Portal agent" component that runs on each LDAP host
  (much bigger scope, but unlocks several features here).

**Effort.** Small if going LDAP-only; large if going agent. Worth a
brainstorming session on its own before committing to either.

---

## Cross-cutting architecture wrinkles

These apply to several of the above:

1. **Connection model break.** A "directory connection" today maps
   to a suffix. A "server" hosts N suffixes (and N databases under
   N backends). Needs a new entity (`Server`) above
   `DirectoryConnection`, or a flag on existing connections that says
   "this is also a control-plane handle." The `cn=config` and
   `cn=monitor` lookups happen at server scope, not suffix scope.

2. **Two binds per server.** The user-data bind (existing) and a
   control-plane bind (`cn=config` is often locked to SASL/EXTERNAL
   over Unix socket; `cn=monitor` may need a different identity).
   UI needs to capture both, with "use same bind" as the default.

3. **`cn=config` writeability.** Not every deployment uses
   `cn=config` — some still use static `slapd.conf`. Detect at
   connect time; gracefully degrade features that require dynamic
   config.

4. **OpenLDAP version variance.** Features added between 2.4 / 2.5 /
   2.6: delta-syncrepl, `olcMemberOfDangling`, several monitor
   attributes. A version-aware feature table is worth maintaining.

5. **Audit integration.** Every control-plane mutation (config edit,
   ACL edit, schema add, backup execute, restore execute) MUST
   produce an audit event in the existing audit pipeline. Not a
   separate log.

6. **Edition placement.** Most of these are "operator tooling"
   territory and would naturally fit in the **commercial** edition.
   Confirm against [`docs/edition-boundary.md`](edition-boundary.md)
   when each item gets picked up.

7. **Entitlement gates.** Add a new entitlement (e.g.
   `OPENLDAP_OPS` or `SERVER_MANAGEMENT`) and gate at the service
   layer via `@Entitled`, per the established refactor convention.

---

## What deliberately ISN'T in Tier 1

For reference (not committed to either):

- **`cn=config` browser/editor.** Dangerous; needs a confirmation/
  dry-run flow + auto-revert on broken config. Worth doing eventually
  but after read-only items prove the pattern.
- **Schema editor.** Adding custom OIDs is rare; misuse breaks
  replication. Read-only schema browser already exists.
- **TLS cert lifecycle UI.** Multi-step, integration-heavy
  (vault/letsencrypt). Defer.
- **Live ops view (`SHOW PROCESSLIST` analogue).** Mostly
  observational; "kill connection" semantics in slapd are awkward.
- **Multimaster/mirrormode setup wizard.** Hard to abstract; better
  served by Ansible + docs.
- **Performance benchmarking / load gen.** Out of admin-tool scope.

---

## Recommended bundling

If picking up multiple items at once, **(1) + (2)** form a coherent
"observability" PR: multi-server health dashboard with the
replication topology layered into it. **(3)** stands alone as a
high-value individual feature. **(4)** is best after **(1)** ships
because they share log-ingestion plumbing. **(5)** has its own
agent-vs-no-agent design fork that wants its own brainstorming
session before commitment.

Smallest credible "now LDAP Portal manages OpenLDAP too" cycle:
**(1) + (2) + (3) read-only**. Three sub-projects, ~6–8 weeks of
focused work, no agent component required.
