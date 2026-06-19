# Scheduled Reports (CSV + Email) in Core — Implementation Plan

**Status:** Not started (plan, 2026-06-19; revised to reuse the existing
`scheduled_report_jobs` table, 2026-06-19).

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
- **The table already exists in core** — `scheduled_report_jobs` is defined in
  the core baseline (`V1__baseline.sql`). We reuse it rather than create a
  parallel `report_jobs` table; only a small additive migration is needed for a
  couple of gaps (below).

So the work is mostly: entity + repository + DTOs + service + controller + one
poller + a health provider + a small additive migration + tests, reusing the
existing table and the email/report-execution paths.

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
  "recipientEmail": "ops@example.com",    // → delivery_recipients; or null
  "s3KeyPrefix": null,                    // or "reports/"
  "enabled": true
}
```

Response (`Job`) adds: `id`, `lastRunAt`, `lastRunStatus` (`SUCCESS`/`FAILED`).
The list table shows Name, Type, Schedule (cron), Format, Delivery, Last Run +
status, the enabled toggle.

> **Frontend delta:** drop the **Email Subject** input from the job form (and
> from `buildJobPayload`) — the backend now generates the subject. `recipientEmail`
> maps to the table's `delivery_recipients`. Everything else in the already-shipped
> form is unchanged.

## Backend design (all in `core`)

### 1. Schema — reuse the existing `scheduled_report_jobs` table

The table already exists in the core baseline (`V1__baseline.sql`); **we do not
create a new table.** Its columns:

| column | type | maps to |
|---|---|---|
| `id` | uuid pk | response `id` |
| `directory_id` | uuid not null, fk → directory_connections | scope |
| `name` | varchar(255) not null | `name` |
| `report_type` | varchar(50) not null | `reportType` |
| `report_params` | jsonb | `reportParams` |
| `cron_expression` | varchar(100) not null | `cronExpression` |
| `output_format` | varchar(10) not null default 'CSV' | `outputFormat` (CHECK CSV/PDF) |
| `delivery_method` | varchar(10) not null default 'EMAIL' | `deliveryMethod` (CHECK EMAIL/S3) |
| `delivery_recipients` | text | `recipientEmail` (comma-separated; one value today) |
| `s3_key_prefix` | varchar(500) | `s3KeyPrefix` |
| `enabled` | boolean not null default true | `enabled` |
| `last_run_at` | timestamptz | `lastRunAt` |
| `last_run_status` | varchar(50) | `lastRunStatus` (SUCCESS/FAILED) |
| `last_run_message` | text | failure detail (not surfaced in the list) |
| `created_by_admin_id` | uuid, fk → accounts | set from principal |
| `created_at` / `updated_at` | timestamptz | |

Reconciliation notes (vs. the earlier draft and the frontend contract):

- **No `recipient_email` / `email_subject` columns.** Recipients live in
  `delivery_recipients`. The email **subject is generated** from the report type
  + params at send time (see §5) — the operator is not asked for one and it is
  **not stored**. So the frontend's `emailSubject` field is dropped.
- **No `next_run_at` column.** Due-ness is computed in Java from
  `cron_expression` + `last_run_at` at poll time (see §3) — matching the
  existing schema, so no column is added.
- Indexes already present: `idx_report_jobs_dir (directory_id)`,
  `idx_report_jobs_enabled (enabled)`.

**Only DDL needed — a small additive migration `V18__relax_report_type_check.sql`:**
the baseline `chk_report_type` CHECK only allows the original seven built-ins
(it predates `MISSING_PROFILE_GROUPS`, `AUDIT_ENTRIES`, and addon report ids
like `ORPHANED_IVIA_ACCOUNTS`). Since the schedulable report-type set is now
dynamic (built-ins + addon providers), **drop `chk_report_type`** and validate
`report_type` in the service instead (against `OperationalReportType` +
`OperationalReportProvider` ids). The `output_format` and `delivery_method`
CHECKs stay (the value sets are stable).

### 2. Entity + enums

- `ScheduledReportJob` JPA entity mapped to `scheduled_report_jobs`
  (`@Getter/@Setter`, `report_params` as `jsonb` via `@JdbcTypeCode(SqlTypes.JSON)`
  like `AuditEvent.detail`). Fields mirror the columns above, including
  `deliveryRecipients`, `lastRunMessage`, and `createdByAdminId`. **No
  `nextRunAt` / `emailSubject` fields** (not in the table).
- Enums `ReportOutputFormat { CSV, PDF }`, `ReportDeliveryMethod { EMAIL, S3 }`,
  `ReportJobRunStatus { SUCCESS, FAILED }` (`@Enumerated(STRING)`; the
  `output_format` / `delivery_method` DB CHECKs already match).

### 3. Repository — `ScheduledReportJobRepository`

- `findAllByDirectoryId(UUID, Pageable)`
- `findByIdAndDirectoryId(...)` (scope guard)
- `findByEnabledTrue()` — the poller loads enabled jobs and decides due-ness in
  Java from `cron_expression` + `last_run_at` (no `next_run_at` column). The job
  count is small, so this is cheap.
- **Multi-instance safety** (no `next_run_at` to claim on): guard each run with
  a conditional update — `UPDATE scheduled_report_jobs SET last_run_at = :now
  WHERE id = :id AND last_run_at IS NOT DISTINCT FROM :seenLastRunAt` — and only
  run when it updates one row; or wrap the poll in a Postgres advisory lock.
  (If a cleaner claim is wanted later, add a `next_run_at` column and switch to
  the `FOR UPDATE SKIP LOCKED` pattern — but that's an extra column we're
  avoiding for now.)

### 4. DTOs

- `ReportJobRequest` (record, bean-validated: `@NotBlank name`,
  `@NotBlank reportType`, `@NotBlank cronExpression`, format/delivery enums,
  conditional `recipientEmail` when EMAIL). **No `emailSubject`.**
- `ReportJobResponse` (record) — the `Job` shape above; `recipientEmail` is read
  back from `delivery_recipients`.

### 5. Service — `ScheduledReportJobService`

- CRUD scoped to directory; `createdByAdminId` set from the principal.
- **Validation:** `reportType` resolves to a built-in `OperationalReportType`
  or an addon `OperationalReportProvider` id (this replaces the dropped
  `chk_report_type` CHECK); cron parses (see decision); in core, **reject
  `PDF` / `S3`** with `IllegalArgumentException` (→ 400) unless a governance
  capability is present — keeps the edition line.
- **Generated email subject** (operator never supplies one): build it from the
  report's friendly label + key params + the run date, e.g.
  `"[LDAPPortal] Disabled Accounts — Corp LDAP — 2026-06-19"`. A small
  `reportEmailSubject(job, dc)` helper centralises the format; nothing is
  persisted.
- `runJob(job)`: `reportService.run(dc, type, params, dirId)` →
  `CsvUtils.write(...)` → deliver (subject generated here) → record
  `lastRunAt` / `lastRunStatus` / `lastRunMessage`. Never throws out of the
  scheduler loop.

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

### 8. Scheduler — `ScheduledReportJobScheduler`

`@Scheduled(fixedDelay = 60_000)` poller (following `OutboundDispatcherScheduler`'s
shape): load enabled jobs, compute due-ness from `cron_expression` +
`last_run_at`, run each due job via `ScheduledReportJobService.runJob` behind the
conditional-update claim from §3, catch + log per job. One-minute granularity
matches cron resolution.

### 9. Dashboard health

Replace the noop with a real `ReportJobHealthProvider` bean in core, backed by
`scheduled_report_jobs`, returning `new ReportJobHealth(enabledCount, failedCount)`
(failed = `last_run_status = 'FAILED'`). Guard against double-registration with
`CoreNoopSpiAutoConfiguration` (`@ConditionalOnMissingBean`).

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

- `ScheduledReportJobServiceTest` (Mockito): CRUD scope guard; cron validation
  (good/bad); `report_type` validation against built-ins + providers (replaces
  the dropped CHECK); `PDF`/`S3` rejected in core; generated-subject format;
  `runJob` success path (report run → CSV → email sent, status SUCCESS) and
  failure path (status FAILED, `last_run_message` recorded, no throw).
- `ReportJobControllerTest` (MockMvc): authz (`REPORTS_SCHEDULE`), the
  request/response contract (incl. `recipientEmail` ↔ `delivery_recipients`,
  no `emailSubject`), 400 on bad input.
- `ScheduledReportJobRepositoryTest` (`@DataJpaTest`): CRUD + due-ness selection
  + the conditional-update claim (works on H2/Postgres — no `SKIP LOCKED`
  needed since we don't add `next_run_at`).

## File checklist (~10–13 files)

1. `db/migration/core/V18__relax_report_type_check.sql` (drop `chk_report_type`; **reuses the existing `scheduled_report_jobs` table** — no new table)
2. `entity/ScheduledReportJob.java` (+ enums `ReportOutputFormat`, `ReportDeliveryMethod`, `ReportJobRunStatus`)
3. `repository/ScheduledReportJobRepository.java`
4. `dto/reports/ReportJobRequest.java`, `ReportJobResponse.java`
5. `service/EmailService.java` (extracted) + refactor `ApprovalNotificationService`
6. `service/ScheduledReportJobService.java` (incl. generated-subject helper)
7. `controller/ReportJobController.java` (or `core/reports/`)
8. `core/reports/ScheduledReportJobScheduler.java`
9. `core/dashboard/CoreReportJobHealthProvider.java` (+ wire in `CoreNoopSpiAutoConfiguration`)
10. Frontend: re-gate Scheduled Jobs button on `REPORTS_SCHEDULE`; drop the Email Subject field; constrain format/delivery options in core
11. Tests (service, controller, repository)

## Open questions / verification items

1. **`REPORTS_SCHEDULE` availability in community** — confirm the feature key is
   grantable in core (not implicitly governance-gated). If it currently maps to
   governance, expose/grant it for the core scheduling subset.
2. **Multi-instance deployment** — the existing table has no `next_run_at`, so
   the plan claims runs with a conditional `UPDATE` (or an advisory lock). If
   community is always single-instance this is moot; if we want the cleaner
   `FOR UPDATE SKIP LOCKED` claim, add a `next_run_at` column later.
3. **Cron format** — confirm the 5→6 normalization choice with maintainers.
4. **Edition policy sign-off** — bringing the scheduling primitive into core
   narrows the paid boundary to PDF/S3/compliance report types. This is a
   product decision, not just a technical one.
5. **Dropping `chk_report_type`** — the plan removes the baseline CHECK and
   validates `report_type` in the service (so addon/`AUDIT_ENTRIES` types can be
   scheduled). Confirm that's acceptable vs. widening the CHECK to a fixed list
   (which can't enumerate addon ids).
6. **Existing `delivery_recipients` data** — confirm the column holds a single
   address (or a delimiter convention) so the `recipientEmail` ↔
   `delivery_recipients` mapping round-trips cleanly.

## Effort

Medium — roughly a focused multi-day change; ~10–13 mostly-straightforward
files. No new heavy dependencies (scheduling, SMTP-with-attachment, report+CSV
generation all already exist in core).
