# First-Run Setup Wizard — Manual Testing Guide

## Overview

The setup wizard runs automatically on the first superadmin login. It guides the user through connecting an LDAP directory, verifying it works, creating a provisioning profile, and optionally starting an access review campaign.

## How It Works

1. **On first startup**, `BootstrapService` creates a superadmin account and the `setup_completed` flag defaults to `false`.
2. **On login**, the auth store checks `GET /api/v1/auth/setup-status`. If `setupCompleted` is `false` and the user is a superadmin, the router redirects to `/setup`.
3. **The wizard** walks through 6 steps, calling existing REST endpoints.
4. **On completion**, the wizard sets `setupCompleted: true` via `PUT /api/v1/settings`, and the guard stops redirecting.

## Testing the Wizard (Fresh Install)

### Prerequisites

- Running PostgreSQL with an empty `ldapportal` database
- Application started with `BOOTSTRAP_SUPERADMIN_USERNAME` and `BOOTSTRAP_SUPERADMIN_PASSWORD` set
- An accessible LDAP directory for testing (e.g., OpenLDAP, 389 DS, or Active Directory)

### Step-by-step

1. **Open the app** at `http://localhost:9080/login`
2. **Log in** with the bootstrap superadmin credentials
3. **Expect redirect** to `/setup` — the wizard should appear automatically
4. **Step 1 (Welcome):** Read the intro, click "Get Started"
5. **Step 2 (Connect LDAP):**
   - Fill in: Display Name, Host, Port, SSL Mode, Bind DN, Bind Password, Base DN
   - Click "Test Connection" — expect green success message with response time
   - Click "Save & Continue" — directory is created
6. **Step 3 (Verify Connection):**
   - Expect user count and group count to appear
   - If zero, go back and check Base DN
   - Click "Continue"
7. **Step 4 (Create Profile):**
   - Profile name defaults to directory display name
   - Target OU defaults to Base DN
   - Object classes default to inetOrgPerson, organizationalPerson, person, top
   - Schema dropdown loads available object classes from the directory
   - Note the Discovery Wizard callout at the bottom
   - Click "Save & Continue"
8. **Step 5 (Access Review):**
   - Optional — click "Skip" to go to Step 6, OR:
   - Use the GroupDnPicker to select a group
   - Set campaign name and deadline
   - Click "Create Campaign & Continue"
9. **Step 6 (Done):**
   - Verify summary shows all created resources
   - Note the Discovery Wizard link for future use
   - Click "Complete Setup & Go to Dashboard"
10. **Expect redirect** to `/superadmin/dashboard`
11. **Refresh the page** — should NOT redirect to `/setup` again

## API Testing (curl)

### Check setup status (public, no auth)

```bash
curl -s http://localhost:9080/api/v1/auth/setup-status
# Before wizard: {"setupCompleted": false}
# After wizard:  {"setupCompleted": true}
```

### Manually mark setup complete

```bash
# 1. Login
curl -s -c cookies.txt http://localhost:9080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"superadmin","password":"YOUR_PASSWORD"}'

# 2. Get current settings
curl -s -b cookies.txt http://localhost:9080/api/v1/settings | jq .

# 3. PUT with setupCompleted: true (include all required fields from step 2)
```

## Verification Checklist

| Scenario | Expected |
|----------|----------|
| Fresh install, first superadmin login | Redirects to `/setup` |
| Non-superadmin (admin) login | No redirect, normal flow |
| Setup already completed, superadmin login | No redirect, normal flow |
| `/api/v1/auth/setup-status` unauthenticated | 200 OK with `setupCompleted` field |
| Test connection with wrong credentials | Red error message in step 2 |
| Test connection with correct credentials | Green success with response time |
| Empty directory (no users/groups) | Yellow warning in step 3, wizard continues |
| Skip access review | Step 6 shows "Access review skipped" |
| Complete setup | `setupCompleted` becomes `true`, no more redirects |
| Refresh after completion | Stays on dashboard |

## Test Coverage

- **ApplicationSettingsServiceTest** — 4 new tests:
  - Default settings return `setupCompleted: false`
  - Existing settings with `setupCompleted: true` return correctly
  - `upsert` with `setupCompleted: true` persists the flag
  - `upsert` with `null setupCompleted` preserves existing value

- **AuthControllerTest** — 2 new tests:
  - `setupStatus` unauthenticated returns `false` by default
  - `setupStatus` after setup returns `true`

- **All 311 project tests pass with 0 failures**
