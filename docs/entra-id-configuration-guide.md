# Microsoft Entra ID Integration — Configuration & Testing Guide

## Overview

LDAP Portal integrates with Microsoft Entra ID (formerly Azure AD) to provide unified identity governance across on-premises LDAP directories and cloud identity. The integration is read-only — LDAP Portal queries Entra ID for users, groups, memberships, and audit events but never writes to it.

## Prerequisites

- A Microsoft Entra ID tenant (any tier — Free, P1, or P2)
- Permission to register an application in the tenant (Application Administrator or Global Administrator role)
- LDAP Portal instance running with SMTP configured (for alert email delivery)

---

## Step 1: Register an Application in Entra ID

1. Go to the [Azure Portal](https://portal.azure.com) → **Microsoft Entra ID** → **App registrations** → **New registration**
2. Set:
   - **Name**: `LDAP Portal` (or your preferred name)
   - **Supported account types**: "Accounts in this organizational directory only"
   - **Redirect URI**: Leave blank (not needed for client credentials flow)
3. Click **Register**
4. On the app's **Overview** page, note:
   - **Application (client) ID** — this is the `Client ID`
   - **Directory (tenant) ID** — this is the `Tenant ID`

### Create a Client Secret

1. Go to **Certificates & secrets** → **Client secrets** → **New client secret**
2. Set a description (e.g., "LDAP Portal") and expiration (recommended: 24 months)
3. Click **Add**
4. **Copy the secret value immediately** — it's only shown once. This is the `Client Secret`

### Grant API Permissions

1. Go to **API permissions** → **Add a permission** → **Microsoft Graph** → **Application permissions**
2. Add each permission **one at a time**, clicking "Add permissions" after each:
   - `User.Read.All` — read all user profiles **(required)**
   - `Group.Read.All` — read all groups and memberships **(required)**
   - `AuditLog.Read.All` — read directory audit logs **(required for audit event sync)**
   - `Directory.Read.All` — read directory roles and structure **(optional — enhances privileged role detection but not required for core functionality)**
3. After all permissions appear in the list, click **Grant admin consent for [your tenant]** (requires admin)
4. Verify all permissions show "Granted" status

> **Note**: `AuditLog.Read.All` requires Entra ID P1 or P2 for sign-in log data. Directory audit logs are available on all tiers.
>
> **Troubleshooting consent errors**: If "Grant admin consent" fails with a `RequiredResourceAccess` error, remove all permissions, then re-add them one at a time. The consent button only works after all permissions are saved to the app's manifest.

---

## Step 2: Configure the Connection in LDAP Portal

1. Log in as **superadmin**
2. Navigate to **Directory Connections** → **+ New Directory**
3. Select **Directory Type**: `Microsoft Entra ID`
4. Fill in:
   - **Display Name**: A descriptive name (e.g., "Corporate Entra ID")
   - **Tenant ID**: The directory (tenant) ID from Step 1
   - **Client ID**: The application (client) ID from Step 1
   - **Client Secret**: The secret value from Step 1
   - **Graph Endpoint**: Leave blank for global Azure (`https://graph.microsoft.com`). For sovereign clouds:
     - US Government: `https://graph.microsoft.us`
     - China (21Vianet): `https://microsoftgraph.chinacloudapi.cn`
5. Check **Enabled**
6. Click **Test Connection** to verify:
   - On success: "Connection successful"
   - On failure: Check tenant ID, client ID, secret, and that admin consent was granted
7. Click **Save**

After saving, alert rules are automatically initialized for the new directory (all disabled by default).

---

## Step 3: Initial Sync

The sync scheduler runs automatically every 5 minutes, but the first sync must be triggered manually since there's no cached data yet.

1. On the **Directory Connections** page, click **Browse** next to your Entra ID directory
2. The **Entra ID Browser** page shows sync status. You should see "No data synced yet."
3. Click **Full Sync**
4. Wait for the sync to complete. The status card will update with:
   - **Last Full Sync**: timestamp
   - **Cached Users**: count of synced users
   - **Cached Groups**: count of synced groups
5. The **Users** and **Groups** tabs now show the cached data

### What the sync does

| Step | Description |
|------|-------------|
| Pull users | `GET /v1.0/users` with `$select=id,displayName,userPrincipalName,mail,accountEnabled,department,jobTitle,employeeId` |
| Pull groups | `GET /v1.0/groups` with `$select=id,displayName,description` |
| Pull memberships | For each group: `GET /v1.0/groups/{id}/members` |
| Acquire delta tokens | `GET /v1.0/users/delta` and `/groups/delta` for future incremental sync |
| Poll audit events | `GET /v1.0/auditLogs/directoryAudits` with timestamp filter |

### Subsequent syncs

After the initial full sync, the scheduler runs **delta sync** every 5 minutes:
- Uses delta tokens to get only changes since last sync
- Updates/inserts modified users and groups
- Removes deleted users and groups
- Re-syncs memberships for changed groups
- Polls new audit events

If a delta sync fails (e.g., expired delta token), it automatically falls back to a full sync.

---

## Step 4: Verify Data

### Users Tab

Check that the users table shows:
- **Name**: display name from Entra
- **UPN**: user principal name (e.g., `alice@contoso.com`)
- **Email**: mail attribute
- **Enabled**: Yes/No based on `accountEnabled`

Guest accounts appear with UPNs like `bob_partner.com#EXT#@contoso.onmicrosoft.com`.

### Groups Tab

Check that the groups table shows:
- **Name**: group display name
- **Description**: group description
- **Members**: member count

### Audit Log

Navigate to **Audit Log** (superadmin). Entra ID events appear alongside LDAP events:
- Events are mapped from Entra's `activityDisplayName` to LDAP Portal's action types (USER_CREATE, GROUP_MEMBER_ADD, etc.)
- Events that don't map to a specific action show as `LDAP_CHANGE`
- Each event is deduplicated by its Entra ID event ID to prevent duplicates on re-sync

---

## Step 5: Configure Alert Rules

Navigate to **Alerts** → **Configure Rules** → find your Entra ID directory.

### Entra-specific alert rules

| Rule | Default | Description |
|------|---------|-------------|
| **Privileged Role Assignment** | CRITICAL, disabled | Detects Global Admin, Security Admin, and other privileged role assignments |
| **Guest Account Added** | MEDIUM, disabled | Detects new guest accounts (`#EXT#` UPN pattern) |
| **Conditional Access Change** | HIGH, disabled | Detects modifications to conditional access policies |
| **App Consent Granted** | HIGH, disabled | Detects application consent grants (admin or delegated) |
| **Sign-In Anomaly** | HIGH, disabled | Detects failed sign-in spikes per user (configurable threshold) |

### General alert rules that work for Entra

These rules also fire for Entra ID directories:
- **Directory Unreachable** — Graph API connectivity test
- **Changelog Gap** — No audit events received in N hours
- **High Change Volume** — Excessive events in time window

To enable a rule:
1. Check the **On** checkbox
2. Set the **Severity** (CRITICAL, HIGH, MEDIUM, LOW)
3. Set **Cooldown** hours (minimum time between re-alerts for the same condition)
4. Optionally enable **Email** and enter recipient addresses
5. For rules with thresholds, edit the inline parameters (e.g., threshold count, time window)

---

## Step 6: Use with Existing Features

### SoD Policies

The `EntraSodScanService` checks Entra group memberships for SoD violations:
- Create SoD policies referencing Entra ID groups by their object ID (displayed as group name in the UI)
- The scan runs against cached membership data, not live Graph API
- Cross-directory SoD (one LDAP group + one Entra group) is architecturally supported but requires extending the SoD policy UI to select from both directory types

### Access Reviews

The `EntraAccessReviewService` provides:
- **Reviewable groups**: list of all Entra groups with member counts
- **Group members**: user details for populating review decisions
- Access review campaigns can target Entra ID groups (requires extending the campaign creation UI to select from Entra groups)

### Entitlements / Auditor Portal

The `EntraEntitlementService` provides:
- **User entitlements**: each user with their list of Entra group names
- **Group details**: each group with member count and description
- This data can be included in auditor portal evidence packages

---

## Configuration Reference

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ENTRA_POLL_INTERVAL_MS` | `300000` (5 min) | Sync scheduler polling interval |
| `ENTRA_POLL_INITIAL_DELAY_MS` | `60000` (1 min) | Delay before first sync poll after startup |

### application.yml

```yaml
ldapportal:
  entra:
    poll-interval-ms:      ${ENTRA_POLL_INTERVAL_MS:300000}
    poll-initial-delay-ms: ${ENTRA_POLL_INITIAL_DELAY_MS:60000}
```

### Database Tables

| Table | Purpose |
|-------|---------|
| `directory_connections` | Stores tenant_id, entra_client_id, entra_client_secret_encrypted, graph_endpoint |
| `entra_users` | Cached user profiles |
| `entra_groups` | Cached group metadata |
| `entra_group_memberships` | Cached user↔group relationships |
| `entra_sync_state` | Delta tokens and last sync timestamps |

---

## Troubleshooting

### "Test Connection" fails

| Error | Cause | Fix |
|-------|-------|-----|
| "Token request failed (HTTP 401)" | Invalid client ID or secret | Verify client ID and regenerate the secret |
| "Token request failed (HTTP 400)" | Invalid tenant ID | Verify the tenant ID (UUID format) |
| "AADSTS700016: Application not found" | Client ID doesn't exist in the tenant | Check the app registration |
| "Insufficient privileges" | Missing API permissions | Grant admin consent for all required permissions |
| Connection timeout | Network/firewall issue | Verify outbound HTTPS to `login.microsoftonline.com` and `graph.microsoft.com` |

### Sync issues

| Issue | Cause | Fix |
|-------|-------|-----|
| 0 users after full sync | Insufficient permissions | Verify `User.Read.All` permission with admin consent |
| 0 groups after full sync | Insufficient permissions | Verify `Group.Read.All` permission with admin consent |
| Delta sync keeps falling back to full | Expired delta token (>30 days) | Normal behavior — tokens expire if not used |
| Audit events missing | No `AuditLog.Read.All` permission | Grant permission and consent |
| Rate limiting (429 errors) | Too many API calls | Increase poll interval; the client handles Retry-After automatically |

### Alert rules not firing

- Verify the rule is **enabled** (checkbox checked)
- Entra-specific rules only evaluate for `ENTRA_ID` type directories — they silently skip LDAP directories
- Check that the audit event sync is running (audit last poll timestamp is recent)
- Check the **cooldown** — a rule won't re-fire within the cooldown period for the same context key

### Logs

Relevant log messages use these loggers:
- `com.ldapportal.entra.EntraSyncScheduler` — sync scheduling
- `com.ldapportal.entra.EntraSyncService` — sync execution details
- `com.ldapportal.entra.EntraTokenService` — OAuth token acquisition
- `com.ldapportal.entra.GraphApiClient` — HTTP calls, rate limiting, retries

---

## Security Notes

- **Client secret** is encrypted at rest using AES-256 (same `EncryptionService` as LDAP bind passwords)
- **OAuth tokens** are cached in memory only — never persisted to disk or database
- **All API calls** use `https` — no plaintext credentials over the network
- **Read-only integration** — LDAP Portal never writes to Entra ID
- **Minimum permissions** — only `*.Read.All` scopes; no write permissions requested
- **Sovereign cloud support** — configurable Graph API endpoint for government/China clouds
