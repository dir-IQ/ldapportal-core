# Scheduled Reports (CSV + Email) in Core — Implementation Plan

**Status:** Not started (plan, 2026-06-19).

## Goal

Bring the **operator-grade subset** of scheduled reports into core: run an
existing operational report on a cron schedule and **deliver it as a CSV by
email**. Keep the commercial pieces — **PDF rendering, S3 delivery, and
compliance report types** — in `ee/governance`, exactly as they are today.

This makes the already-shipped Scheduled Jobs UI functional on community
builds (it currently 404s because the backend lives only in `ee`; see #277,
which hid the entry point).

## Why this is a contained change

The expensive infrastructure already exists in core:

- **Scheduling** — `@EnableScheduling` is on (`LDAPPortalApplication`), and
  there's an established DB-polling scheduler pattern in
  `OutboundDispatcherScheduler` (`@Scheduled(fixedDelay)` + a `claimBatch`
  query with `FOR UPDATE SKIP LOCKED`).
- **SMTP email with attachments** — `ApprovalNotificationService` already
  sends SMTP mail with a `byte[]` attachment
  (`sendEmailWithAttachment(to, subject, body, name, contentType, bytes)`),
  using SMTP config from `ApplicationSettings`
  (`smtpHost`, `smtpPort`, `smtpSenderAddress`, `smtpUsername`,
  `smtpPasswordEncrypted`).
- **Report execution + CSV** — `OperationalReportService.run(...)` produces
  `ReportData`, and `CsvUtils.write(columns, rows)` renders the CSV bytes
  (the same path the on-demand `POST /reports/run?format=CSV` uses).
- **Feature key** — `FeatureKey.REPORTS_SCHEDULE ("reports.schedule")`
  already exists.
- **Dashboard SPI** — core defines `ReportJobHealthProvider` +
  `NoopReportJobHealthProvider`; a real provider slots in.

So the work is mostly: one migration + entity + repository + DTOs + service +
controller + one poller + a health provider + tests, reusing the email and
report-execution paths.

## Frontend contract (already shipped — the backend must match it)

`frontend/src/api/reports.js` + `ReportJobsView.vue`:

- `GET    /api/v1/directories/{dirId}/report-jobs?size=` → list
- `GET    /api/v1/directories/{dirId}/report-jobs/{jobId}` → one
- `POST   /api/v1/directories/{dirId}/report-jobs` → create
- `PUT    /api/v1/directories/{dirId}/report-jobs/{jobId}` → update
- `DELETE /api/v1/directories/{dirId}/report-jobs/{jobId}` → delete
- `PATCH  /api/v1/directories/{dirId}/report-jobs/{jobId}/enabled?enabled=` → toggle

Create/update request body:

```jsonc
{
  "name": "Weekly disabled accounts",
  "reportType": "DISABLED_ACCOUNTS",      // OperationalReportType name
  "reportParams": { "lookbackDays": 30 }, // type-specific, same as on-demand run
  "cronExpression": "0 8 * * 1",          // NOTE: 5-field (see Cron decision)
  "outputFormat": "CSV",                  // CSV | PDF
  "deliveryMethod": "EMAIL",              // EMAIL | S3
  "recipientEmail": "ops@example.com",    // or null
  "emailSubject": "Weekly report",        // or null
  "s3KeyPrefix": null,                    // or "reports/"
  "enabled": true
}
```

Response (`Job`) adds: `id`, `lastRunAt`, `lastRunStatus` (`SUCCESS`/`FAILED`).
The list table shows Name, Type, Schedule (cron), Format, Delivery, Last Run +
status, the enabled toggle.

## Backend design (all in `core`)

### 1. Migration — `db/migration/core/V18__report_jobs.sql`

`report_jobs` table:

| column | type | notes |
|---|---|---|
| `id` | uuid pk | |
| `directory_id` | uuid not null, fk → directory_connections | scope |
| `name` | varchar not null | |
| `report_type` | varchar not null | OperationalReportType name or addon id |
| `report_params` | jsonb not null default '{}' | |
| `cron_expression` | varchar not null | normalized 6-field (see below) |
| `output_format` | varchar(8) not null default 'CSV' | |
| `delivery_method` | varchar(8) not null default 'EMAIL' | |
| `recipient_email` | varchar | |
| `email_subject` | varchar | |
| `s3_key_prefix` | varchar | |
| `enabled` | boolean not null default true | |
| `next_run_at` | timestamptz | computed from cron |
| `last_run_at` | timestamptz | |
| `last_run_status` | varchar(12) | SUCCESS / FAILED |
| `last_run_error` | text | truncated message on failure |
| `created_at` / `updated_at` | timestamptz | |

Index: `(enabled, next_run_at)` for the due-query.

### 2. Entity + enums

- `ReportJob` JPA entity (`@Getter/@Setter`, `report_params` as `jsonb` via
  `@JdbcTypeCode(SqlTypes.JSON)` like `AuditEvent.detail`).
- Enums `ReportOutputFormat { CSV, PDF }`, `ReportDeliveryMethod { EMAIL, S3 }`,
  `ReportJobRunStatus { SUCCESS, FAILED }` (`@Enumerated(STRING)`).

### 3. Repository — `ReportJobRepository`

- `findAllByDirectoryId(UUID, Pageable)`
- `findByIdAndDirectoryId(...)` (scope guard)
- Due-claim query (native, Postgres): select enabled jobs with
  `next_run_at <= now()` `FOR UPDATE SKIP LOCKED` — mirrors
  `OutboxEntryRepository.claimBatch`, so multiple app instances don't double-run.

### 4. DTOs

- `ReportJobRequest` (record, bean-validated: `@NotBlank name`,
  `@NotBlank reportType`, `@NotBlank cronExpression`, format/delivery enums,
  conditional `recipientEmail` when EMAIL).
- `ReportJobResponse` (record) — exactly the `Job` shape above.

### 5. Service — `ReportJobService`

- CRUD scoped to directory.
- **Validation:** `reportType` resolves to a built-in `OperationalReportType`
  or an addon `OperationalReportProvider` id; cron parses (see decision);
  in core, **reject `PDF` / `S3`** with `IllegalArgumentException` (→ 400)
  unless a governance capability is present — keeps the edition line.
- `nextRunAt` computed from the cron on save and after each run.
- `runJob(job)`: `reportService.run(dc, type, params, dirId)` →
  `CsvUtils.write(...)` → deliver → record `lastRunAt/lastRunStatus/error`,
  recompute `nextRunAt`. Never throws out of the scheduler loop.

### 6. Email delivery

Extract the SMTP send-with-attachment logic from `ApprovalNotificationService`
into a small reusable `EmailService` (`sendWithAttachment(...)`), and have both
the approval notifier and the report job use it. (Alternative: inject
`ApprovalNotificationService` directly — but extracting is cleaner and avoids a
weird dependency direction.) Attachment = `<report_type>.csv`, `text/csv`.

### 7. Controller — `ReportJobController`

`@RestController @RequestMapping("/api/v1/directories/{directoryId}/report-jobs")`,
all methods `@RequiresFeature(FeatureKey.REPORTS_SCHEDULE)`, `@DirectoryId
@PathVariable UUID directoryId`, `@AuthenticationPrincipal`, mirroring
`ReportController`'s authz + rate limiting. Maps request/response DTOs.

### 8. Scheduler — `ReportJobScheduler`

`@Scheduled(fixedDelay = 60_000)` poller following `OutboundDispatcherScheduler`:
claim due jobs (SKIP LOCKED), run each via `ReportJobService.runJob`, catch +
log per job. One-minute granularity matches cron resolution.

### 9. Dashboard health

Replace the noop with a real `ReportJobHealthProvider` bean in core returning
`new ReportJobHealth(enabledCount, failedCount)` (failed = `last_run_status =
FAILED`). Guard against double-registration with `CoreNoopSpiAutoConfiguration`
(`@ConditionalOnMissingBean`).

## Cron format decision

The UI placeholder `0 8 * * 1` is **5-field (unix)**; Spring's `CronExpression`
is **6-field** (leading seconds). Pick one:

- **(Recommended)** Accept 5-field in the API and normalize to 6-field by
  prepending `"0 "` before persisting/parsing. Keeps the existing UI hint and
  is friendlier. Validate with `CronExpression.parse` after normalization.
- Alternatively require 6-field and update the UI hint/help text.

Document whichever in the field help. Either way, validate server-side and
return 400 on a bad expression.

## Edition boundary

- Core accepts **`outputFormat=CSV` + `deliveryMethod=EMAIL`** only; `PDF`/`S3`
  → 400 (governance-only). The `ee` module can later widen this via the same
  controller or an override.
- **Frontend:** re-show the Scheduled Jobs button (gated on the
  `REPORTS_SCHEDULE` feature instead of `isComplianceEnabled`), and in the job
  form restrict Output Format to CSV and Delivery to Email unless governance is
  present (hide the PDF/S3 options — consistent with the existing PDF-export and
  S3-settings gating from #277/#278).

## Testing

- `ReportJobServiceTest` (Mockito): CRUD scope guard; cron validation
  (good/bad); `PDF`/`S3` rejected in core; `runJob` success path
  (report run → CSV → email sent, status SUCCESS) and failure path
  (status FAILED, error recorded, no throw).
- `ReportJobControllerTest` (MockMvc): authz (`REPORTS_SCHEDULE`), the
  request/response contract, 400 on bad input.
- `ReportJobRepositoryTest` (`@DataJpaTest`): basic CRUD + due-query. The
  `FOR UPDATE SKIP LOCKED` claim is Postgres-only (H2 can't run it) — cover it
  at the integration/E2E layer or `@Disabled` with a note, matching
  `OutboxEntryRepositoryTest`.

## File checklist (~10–13 files)

1. `db/migration/core/V18__report_jobs.sql`
2. `entity/ReportJob.java` (+ enums `ReportOutputFormat`, `ReportDeliveryMethod`, `ReportJobRunStatus`)
3. `repository/ReportJobRepository.java`
4. `dto/reports/ReportJobRequest.java`, `ReportJobResponse.java`
5. `service/EmailService.java` (extracted) + refactor `ApprovalNotificationService`
6. `service/ReportJobService.java`
7. `controller/ReportJobController.java` (or `core/reports/`)
8. `core/reports/ReportJobScheduler.java`
9. `core/dashboard/CoreReportJobHealthProvider.java` (+ wire in `CoreNoopSpiAutoConfiguration`)
10. Frontend: re-gate Scheduled Jobs button on `REPORTS_SCHEDULE`; constrain format/delivery options in core
11. Tests (service, controller, repository)

## Open questions / verification items

1. **`REPORTS_SCHEDULE` availability in community** — confirm the feature key is
   grantable in core (not implicitly governance-gated). If it currently maps to
   governance, expose/grant it for the core scheduling subset.
2. **Multi-instance deployment** — if community can run multiple replicas, the
   `SKIP LOCKED` claim is required (planned); if always single-instance, it's
   still harmless.
3. **Cron format** — confirm the 5→6 normalization choice with maintainers.
4. **Edition policy sign-off** — bringing the scheduling primitive into core
   narrows the paid boundary to PDF/S3/compliance report types. This is a
   product decision, not just a technical one.

## Effort

Medium — roughly a focused multi-day change; ~10–13 mostly-straightforward
files. No new heavy dependencies (scheduling, SMTP-with-attachment, report+CSV
generation all already exist in core).
