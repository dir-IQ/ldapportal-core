# Scheduled Reports — Core SPI Seams (PDF / S3 / Compliance) — Implementation Plan

**Status:** Not started (plan, 2026-06-19).
**Audience:** Claude Code / engineers working across `ldapportal-core` and `ldapportal-ee`.
**Scope:** Backend. Pairs 1:1 with the ee plan
`ldapportal-ee/docs/plans/2026-06-19-scheduled-reports-ee-provider-migration.md`
— neither lands alone (see §8, Cross-repo sequencing).

---

## 1. Context

Phase 1 (`docs/plans/2026-06-19-scheduled-reports-csv-email.md`, core PR #282)
brings the **operator subset** of scheduled reports into core on the existing
`scheduled_report_jobs` table: operational report types, **CSV** output,
**email** delivery, gated by `FeatureKey.REPORTS_SCHEDULE`. It deliberately
rejects PDF / S3 / compliance with a 400 and leaves an open question: *with ee's
scheduler retired onto core's, how do commercial users still schedule
compliance report types, PDF output, and S3 delivery?*

This plan answers that. It defines the **core extension seams** that let
`ee/governance` contribute the commercial capabilities back into the single
core scheduler — no `core → ee` dependency, mirroring the existing
`OperationalReportProvider` / `ReportJobHealthProvider` precedents. The paired
ee plan converts ee into a provider and retires its duplicate scheduler.

**Decision already taken (this plan assumes it):** compliance content plugs in
through a **dedicated `ScheduledReportContentProvider` SPI**, *not* by reusing
`OperationalReportProvider`. Rationale in §3.1.

---

## 2. The three capabilities, on three independent axes

PDF, S3, and compliance are **not** one bundle — each is resolved on its own
axis, which is why one of the three needs no ee code at all.

| Axis | Community | GOVERNANCE | Mechanism |
|---|---|---|---|
| report **content** (type) | operational (8) | + compliance (7) | `ScheduledReportContentProvider` SPI; core=operational, ee=compliance |
| output **format** | CSV | + PDF | `ReportRenderer` SPI; core=CSV, ee=PDF |
| **delivery** | EMAIL | + S3 | all core (`EmailService`, `S3UploadService`); S3 gated by entitlement — **no ee code** |

Two layers throughout:

- **Policy (entitlement)** — gated *two different ways*, because core owns the
  format/delivery enums but not the report-type catalogue:
  - **Format & delivery:** `ReportOutputFormat` and `ReportDeliveryMethod` are
    core-owned enums, so they implement `EditionScoped` directly (`PDF` and `S3`
    → `GOVERNANCE`). Create/run validates via `EntitlementService.exposed(...)`
    and the edition-leak guards cover them automatically.
  - **Report type:** core cannot enumerate ee's compliance types, so this axis
    gates on the **provider-contributed `ScheduledReportType` descriptors**
    (`requiredEntitlement`, §3.1), *not* an `EditionScoped` enum. The exposed
    catalogue is the union of all providers' descriptors, guarded by a dedicated
    descriptor-coverage test (a mis-gated descriptor fails it).
- **Mechanism (capability):** the work plugs in through the SPIs above as gated
  `@Component`s. In a community build the PDF renderer and compliance provider
  beans are simply absent, so those values are impossible by *capability
  absence* — the entitlement gate is belt-and-suspenders.

---

## 3. Core design

### 3.1 Content SPI — `ScheduledReportContentProvider` (new)

```java
// com.ldapportal.core.reports.schedule
public interface ScheduledReportContentProvider {
    /** Report-type descriptors this provider serves, incl. edition gating. */
    List<ScheduledReportType> supportedTypes();   // {id, label, requiredEntitlement}  null = community
    boolean appliesTo(DirectoryConnection dir, String reportType);
    ReportData run(DirectoryConnection dir, String reportType,
                   Map<String,Object> params, String scopeBaseDn);
}
```

- Returns the existing `ReportData` record (its javadoc already declares it
  *"Shared by core operational reports and ee compliance reports"*, with
  `toRowLists()` for PDF) — **no new content shape**.
- **Core** ships `OperationalScheduledReportContentProvider`, a thin wrapper that
  delegates to `OperationalReportService.run(...)`. That service already resolves
  built-in `OperationalReportType`s *and* addon `OperationalReportProvider`s, so
  operational scheduling rides the existing path and we add **zero** parallel
  operational logic. All its `supportedTypes()` carry `requiredEntitlement = null`.
- **ee** ships `ComplianceScheduledReportContentProvider` (the 7 compliance
  types), `requiredEntitlement = Entitlement.GOVERNANCE`. See the ee plan.
- The scheduler injects `List<ScheduledReportContentProvider>` and resolves a
  type by `supports`/`appliesTo`.

**Why a dedicated SPI and not `OperationalReportProvider` (the resolved open
question):**

1. **A documented invariant forbids reuse.** `RunOperationalReportRequest`'s
   javadoc: *"Compliance report types still can't run here … aren't registered
   as providers in core,"* and `OperationalReportService.run` resolves the
   requested type straight out of `List<OperationalReportProvider>`. Registering
   compliance there would let the on-demand `/reports/run` + `/reports/run-data`
   endpoints execute compliance reports, bypassing their gating — a license leak.
2. **Wrong gating axis.** `OperationalReportProvider` gates by
   `appliesTo(DirectoryConnection)` (applicability), and its premise is that
   *community* exposes providers when an addon is present (e.g. the IVIA
   orphaned-account scan in community-plus-isva). Compliance is edition-gated — a
   different axis. One SPI shouldn't carry two gating models.
3. **Semantics / leak guards.** The SPI is named/documented "operational"; the
   leak guards reason about `EditionScoped` report types, which operational
   providers deliberately are not.

`OperationalReportProvider` is **left untouched**, invariant intact.

**Entitlement source of truth = the provider-contributed `ScheduledReportType`
descriptors**, not a monolithic core enum. This is the only option that lets the
exposure gate / leak guards see the full catalogue (built-ins + addon +
compliance) while keeping core free of ee's `ReportType`.

### 3.2 Renderer SPI — `ReportRenderer` (new)

```java
// com.ldapportal.core.reports.schedule
public interface ReportRenderer {
    boolean supports(ReportOutputFormat fmt);
    RenderedReport render(ReportData data, RenderContext ctx);   // bytes + contentType + filename
}
```

- **Core** ships the CSV renderer (wraps `CsvUtils.write`); ee ships the PDF
  renderer (relocated `PdfReportService`; **OpenPDF stays a commercial-only
  dependency in ee**). PDF for *operational* reports also flows through here,
  matching today's `/reports/run-pdf` already being ee-served.
- Community has no PDF renderer bean → PDF is structurally impossible there.
- `RenderContext` carries the resolved report **label** (and run date) taken
  from the job's `ScheduledReportType` descriptor — the single source for both
  the PDF title and phase-1's generated email subject, so the two can't drift.

### 3.3 Delivery — stays entirely in core, S3 gated by entitlement

`S3UploadService`, `DeliveryMethod{EMAIL,S3}`, and the extracted `EmailService`
are **already in core**. So delivery needs **no SPI and no ee code**: the
scheduler delivers EMAIL or S3 directly; S3 is withheld from community purely by
the `ReportDeliveryMethod` entitlement gate. This is one fewer SPI than earlier
drafts implied.

### 3.4 Scheduler resolution flow

For each due job (`ScheduledReportJobScheduler` from phase 1):

1. Re-assert exposure for the job's type/format/delivery (see §3.5).
2. **Content:** resolve `ScheduledReportContentProvider` by `report_type` →
   `ReportData`.
3. **Render:** resolve `ReportRenderer` by `output_format` → bytes + contentType
   + filename.
4. **Deliver:** EMAIL via `EmailService` / S3 via `S3UploadService`.
5. Record `last_run_at` / `last_run_status` / `last_run_message`.

Any unresolved step → graceful per-job failure (`last_run_status = FAILED`,
message recorded); never throws out of the poll loop.

### 3.5 Two enforcement points (also handles license downgrade)

- **Create/update** (controller/service): reject non-exposed type/format/delivery
  → 400/402 via `EntitlementService.exposed(...)`.
- **Runtime re-check** (scheduler, step 1): if a license has lapsed and a job
  still references a gated capability (compliance type, PDF, S3), skip and record
  `FAILED — requires GOVERNANCE` rather than silently running a paid feature.
  Missing-provider / missing-renderer degrade the same way.

### 3.6 `chk_report_type` ownership (core side)

Phase 1's `V18__relax_report_type_check.sql` drops the baseline
`chk_report_type` and validates `report_type` in the service against the
provider registry. That is necessary but **not sufficient on commercial**: ee's
`V108` re-adds a restrictive `chk_report_type`, and with `out-of-order: true`
the last writer differs between fresh and existing commercial installs (the
exact `chk_feature_key` dual-ownership trap). The companion ee migration drops
ee's re-declaration (ee plan WS-E3) so core's drop + in-app validation is
authoritative everywhere.

---

## 4. Workstreams (core repo)

- **WS-C1 — SPIs.** Add `ScheduledReportContentProvider`, `ReportRenderer`,
  `ScheduledReportType`, `RenderedReport`, `RenderContext` in
  `com.ldapportal.core.reports.schedule`.
- **WS-C2 — Core providers.** `OperationalScheduledReportContentProvider`
  (delegates to `OperationalReportService`); core CSV `ReportRenderer`.
- **WS-C3 — Scheduler wiring.** Rework the phase-1 scheduler to resolve
  content→render→deliver via the registries; add the runtime exposure re-check
  and graceful per-job failure.
- **WS-C4 — Entitlement.** Make the phase-1 `ReportOutputFormat` and
  `ReportDeliveryMethod` enums implement `EditionScoped` so `PDF` / `S3` gate
  directly (this modifies phase-1 artifacts). Wire the provider-contributed
  `ScheduledReportType` descriptors into `EntitlementService.exposed(...)` for
  the report-type axis. Confirm the edition-leak guards cover the `EditionScoped`
  enums, and add a descriptor-coverage test for the contributed type catalogue.
- **WS-C5 — Controller.** Validate create/run against the exposed catalogue;
  ensure PDF/S3/compliance return 402/400 (not 500) in community. Add a
  **catalogue endpoint** — `GET /api/v1/directories/{directoryId}/report-types`
  → the `EntitlementService.exposed(...)` `ScheduledReportType` descriptors
  (id + label + allowed formats/delivery) — so the schedule form's type dropdown
  shows the 8 operational types in community and all 15 in commercial without
  hard-coding. Also carry over the `…/report-jobs/{jobId}/run-now` endpoint the
  shipped UI uses (absent from the phase-1 contract list).

> The phase-1 entity/repository/controller/scheduler/`EmailService`/migration
> are prerequisites; this plan extends them, it does not re-add them.
> **Recommended:** build the phase-1 scheduler already resolving content through
> `ScheduledReportContentProvider` (core registering the operational provider in
> phase 1), so it is SPI-shaped from the start and WS-C3 is wiring, not rework.

---

## 5. Tests (core)

- SPI resolution: operational provider resolves built-ins + addon ids; unknown
  type → handled (not 500).
- Renderer registry: CSV present in core; PDF absent in a core-only context.
- Exposure: community can schedule operational+CSV+EMAIL; compliance/PDF/S3
  rejected at create (402) and skipped+FAILED at runtime (downgrade path).
- Edition-leak guard coverage of the contributed report-type descriptors
  (mis-gating a descriptor fails the behavioural guard).
- Scheduler: content→render→deliver happy path with a mock compliance provider +
  mock PDF renderer; per-job failure isolation.

---

## 6. Risks & mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Compliance leaks via the operational on-demand endpoint | License leak | Dedicated SPI; `OperationalReportProvider` untouched (§3.1) |
| `core → ee` coupling | Breaks edition boundary + `PackagingArchitectureTest` | All commercial content via SPI; `Entitlement`/`ReportData` already core |
| `chk_report_type` reinstated by ee `V108` | Nondeterministic constraint on commercial | Companion ee migration drops the re-declaration (ee WS3) |
| Class/route collision in commercial build | App won't boot | Resolved by ee retirement; strict sequencing (§8) |
| License downgrade leaves gated jobs runnable | Paid feature runs unlicensed | Runtime exposure re-check (§3.5) |
| OpenPDF pulled into core | Bloats community | PDF renderer + OpenPDF stay in ee |

---

## 7. Acceptance criteria

- [ ] Community: schedule/run operational + CSV + EMAIL; compliance types, PDF,
      and S3 are rejected at the exposure gate (402/400, never 500) and have no
      provider/renderer bean.
- [ ] Commercial (GOVERNANCE): all 15 types × CSV/PDF × EMAIL/S3 run through the
      **single** core scheduler.
- [ ] No `core → ee` dependency; `PackagingArchitectureTest` + edition-leak
      guards pass over core + addons + ee.
- [ ] License downgrade: previously-valid compliance/PDF/S3 jobs record
      `FAILED — requires GOVERNANCE`, don't execute.
- [ ] `OperationalReportProvider` and the on-demand `/reports/*` endpoints are
      unchanged; compliance still cannot run through them.

---

## 8. Cross-repo sequencing (canonical — the ee plan references this)

This cannot be atomic (separate repos/artifacts) and the commercial build won't
boot with both schedulers present, so order matters:

1. **core:** land phase 1 (PR #282 follow-up) + this plan's SPIs and core
   providers; merge. Core ships the seams *and* the operational implementations,
   but the SPIs tolerate zero compliance providers.
2. **core:** cut a release; publish core (+ isva addon) to Maven Central
   (`autoPublish=false`; see core PR #267).
3. **ee:** bump `ldapportal-core.version`; land the ee provider-migration plan —
   register compliance content + PDF renderer providers, **retire** ee's
   duplicate scheduler (split `ScheduledReportJobController`: delete the
   `/report-jobs*` half, **keep** the ad-hoc `/compliance-reports/*` +
   `/reports/run-pdf` endpoints — ee plan WS-E4), add the `chk_report_type`-drop
   migration, re-point `ScheduledReportFailureChecker`.
4. The nightly `core-drift.yml` (`ee vs core@main`) gives early warning between
   steps 1 and 3.

Until step 3 lands, the commercial distribution must not bundle both core's and
ee's scheduler classes (duplicate `ScheduledReportJob` entity name + ambiguous
`/report-jobs` mapping). Treat steps 1–3 as one release train.

> **Dashboard health during the train:** ee's `ScheduledReportJobService`
> implements `ReportJobHealthProvider` as a bean, which suppresses core's
> `@ConditionalOnMissingBean CoreReportJobHealthProvider` until step 3 deletes
> it. So the dashboard tile is served by ee until step 3 and by core after —
> expected, not a conflict.

---

## 9. References

- Phase 1: `docs/plans/2026-06-19-scheduled-reports-csv-email.md` (core PR #282).
- Paired ee plan: `ldapportal-ee/docs/plans/2026-06-19-scheduled-reports-ee-provider-migration.md`.
- SPI precedents: `OperationalReportProvider`, `ReportJobHealthProvider` +
  `NoopReportJobHealthProvider`, `CoreNoopSpiAutoConfiguration`.
- Shared data shape: `core.reports.ReportData` (already cross-edition).
- Edition gating: `core.entitlement.{EditionScoped, EntitlementService,
  EditionLeakGuards}`; `@Entitled(Entitlement.GOVERNANCE)`.
- Cross-repo release mechanics: core PR #267.
