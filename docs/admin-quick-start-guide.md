# LDAP Portal Quick-Start Guide

Get from `docker compose up` to your first auditor-ready evidence in under 30 minutes.

This guide covers OpenLDAP, 389 Directory Server, and Active Directory.

---

## 1. Install & Start

### Prerequisites

- Docker and Docker Compose
- An LDAP directory (OpenLDAP, 389DS, or Active Directory) with network access from the Docker host

### Configure

```bash
cp .env.example .env
```

Edit `.env` and fill in all required values:

```env
POSTGRES_PASSWORD=<strong-database-password>

# Generate these:
#   openssl rand -base64 32    → ENCRYPTION_KEY
#   openssl rand -base64 64    → JWT_SECRET
ENCRYPTION_KEY=<base64-encoded-32-byte-key>
JWT_SECRET=<base64-encoded-random-secret>

BOOTSTRAP_SUPERADMIN_USERNAME=superadmin
BOOTSTRAP_SUPERADMIN_PASSWORD=<your-initial-password>
```

### Start

```bash
docker compose up -d
```

The UI starts at **http://localhost:9080**. The Spring Boot backend is exposed on **http://localhost:9090** for direct API access (the UI's nginx also proxies `/api/v1` requests, so curl examples in the docs can hit either port). PostgreSQL runs on port 5432. Database schema is created automatically on first startup.

---

## 2. First Login & Setup Wizard

1. Go to **http://localhost:9080/login**
2. Log in with your bootstrap superadmin credentials
3. The **Setup Wizard** starts automatically on first login

The wizard walks you through four actions:

### Step 1: Connect Your LDAP Directory

Fill in your directory connection details:

| Field | OpenLDAP | 389 Directory Server | Active Directory |
|-------|----------|---------------------|-----------------|
| Host | `ldap.example.com` | `ds.example.com` | `dc01.corp.local` |
| Port | `389` (or `636` for LDAPS) | `389` (or `636`) | `389` (or `636`) |
| SSL Mode | NONE / LDAPS / STARTTLS | NONE / LDAPS / STARTTLS | LDAPS (recommended) |
| Bind DN | `cn=admin,dc=example,dc=com` | `cn=Directory Manager` | `cn=Administrator,cn=Users,dc=corp,dc=local` |
| Base DN | `dc=example,dc=com` | `dc=example,dc=com` | `dc=corp,dc=local` |

Click **Test Connection** to verify. You should see a green success message with response time.

### Step 2: Verify

The wizard queries your directory and shows user and group counts. If you see zeros, go back and check your Base DN.

### Step 3: Create a Profile

A provisioning profile defines how users are managed in an OU. The wizard pre-fills sensible defaults:

| Field | OpenLDAP / 389DS | Active Directory |
|-------|-----------------|-----------------|
| Object Classes | `inetOrgPerson`, `organizationalPerson`, `person`, `top` | `user`, `person`, `organizationalPerson`, `top` |
| RDN Attribute | `uid` | `cn` |
| Target OU | Your Base DN (refine later to a specific OU) | `CN=Users,DC=corp,DC=local` |

### Step 4: First Access Review (Optional)

Pick a group to review, set a deadline (e.g., 30 days), and the wizard creates your first campaign. You can skip this and create one later.

### Complete

Click **Complete Setup & Go to Dashboard**. The wizard won't appear again.

---

## 3. Run Your First Access Review

If you skipped the access review in the wizard, create one now:

1. In the sidebar, click **Access Reviews**
2. Click **New Campaign**
3. Fill in:
   - **Name:** e.g., "Q1 2026 Access Review"
   - **Deadline:** 30 days
   - **Groups:** Use the group picker to browse and select groups to review
   - **Reviewer:** Assign yourself (or another admin account)
4. Click **Create**
5. Click **Activate** to start the review period

### Make Decisions

1. Open the campaign and click on a review group
2. For each member, choose:
   - **Confirm** — user should keep access
   - **Revoke** — user should lose access
3. Add optional comments explaining your decision
4. Use **Bulk Decide** to confirm or revoke multiple members at once

### Close the Campaign

Once all decisions are made, click **Close Campaign**. Revocations are applied to the LDAP groups automatically (if `autoRevoke` is enabled).

---

## 4. Generate Audit Evidence

Navigate to **Compliance Reports** in the sidebar. Three PDF reports are available:

| Report | What It Contains | Who Asks For It |
|--------|-----------------|-----------------|
| **User Access Report** | Every group and its members in the directory | Auditors reviewing entitlements |
| **Access Review Summary** | Campaign decisions: who confirmed, who revoked, with timestamps | Auditors verifying periodic reviews |
| **Privileged Account Inventory** | All admin/superadmin accounts, their roles, and permissions | Auditors assessing admin access |

Click **Download PDF** on any report card. The PDF includes your organization name, generation timestamp, and tabular data — ready to hand to an auditor.

### Existing Report Types

For operational reports, go to **Reports** in the sidebar:

- Users in Group, Users in Branch, Users with No Group
- Recently Added / Modified / Deleted users
- Disabled Accounts, Missing Profile Groups
- All available in CSV and PDF format

---

## 5. Set Up Approval Workflows

Approval workflows require admin actions (like user creation) to be approved before they execute.

1. Go to **Superadmin → Profiles**
2. Select a profile and open its **Approval** settings
3. Enable approvals and assign approvers
4. When an admin creates a user through that profile, the request goes to the **Approvals** queue
5. Approvers see pending requests in the sidebar (with a badge count) and can approve or reject with comments

---

## 6. Create Admin Accounts

Don't share the superadmin account. Create dedicated admin accounts:

1. Go to **Superadmin → Accounts**
2. Click **Create Account**
3. Set username, display name, email, and password
4. Assign a role:
   - **SUPERADMIN** — full access to everything
   - **ADMIN** — access scoped to assigned profiles
5. For ADMIN accounts, assign profiles with a base role:
   - **ADMIN** — can create, edit, delete users
   - **READ_ONLY** — can view users and run reports
6. Fine-tune permissions per feature (e.g., allow bulk export but not bulk import)

---

## 7. Enable Self-Service (Optional)

Let users reset their own passwords and request accounts:

1. Go to **Superadmin → Directories** and enable **Self-Service Portal** on your directory
2. Set the **Login Attribute** (usually `uid` or `sAMAccountName`)
3. Go to **Superadmin → Profiles** and enable **Self-Registration** on a profile
4. Users access the self-service portal at **/self-service/login**
5. New registration requests appear in the **Approvals** queue for admin review

---

## 8. Configure SIEM Export (Optional)

Send audit events to your SIEM in real time:

1. Go to **Settings** (superadmin sidebar)
2. Scroll to the **SIEM / Syslog** section
3. Configure:
   - **Protocol:** Syslog UDP, Syslog TCP, Syslog TLS, or Webhook
   - **Host / Port:** Your SIEM collector address
   - **Format:** RFC 5424, CEF (Common Event Format), or JSON
4. Click **Test Connection** to verify
5. Enable the toggle

All audit events (logins, user changes, access review decisions, approval actions) are forwarded automatically.

---

## Common LDAP Connection Settings

### OpenLDAP

```
Host:     ldap.example.com
Port:     389 (or 636 for LDAPS)
SSL:      STARTTLS or LDAPS
Bind DN:  cn=admin,dc=example,dc=com
Base DN:  dc=example,dc=com
```

### 389 Directory Server / RHDS

```
Host:     ds.example.com
Port:     389 (or 636 for LDAPS)
SSL:      STARTTLS or LDAPS
Bind DN:  cn=Directory Manager
Base DN:  dc=example,dc=com
```

### Active Directory

```
Host:     dc01.corp.local
Port:     636 (LDAPS recommended)
SSL:      LDAPS
Bind DN:  cn=Administrator,cn=Users,dc=corp,dc=local
          (or use UPN: admin@corp.local with bind DN pattern)
Base DN:  dc=corp,dc=local
```

**AD-specific notes:**
- Use `cn` as the RDN attribute (not `uid`)
- Object classes: `user`, `person`, `organizationalPerson`, `top`
- Group member attribute: `member`
- User containers may be `CN=Users` (not an OU) — use the directory browser to verify

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Test Connection" fails | Check host/port reachability from the Docker container. Try: `docker exec ldap-admin-app wget -qO- ldap://your-host:389` |
| Zero users/groups found | Verify the Base DN matches your directory structure. Use the **Directory Browser** (superadmin sidebar) to explore the tree. |
| SSL certificate errors | Enable "Trust all certificates" for testing, or paste your CA certificate PEM in the directory settings. |
| Login fails after setup | Check that the bootstrap password hasn't been changed. If locked out, reset via the database. |
| Audit log is empty | Audit events are recorded for admin actions. Log in and perform an operation (e.g., search users) to generate entries. |
| PDF reports are empty | Ensure the directory has group memberships. The User Access Report queries `member`, `uniqueMember`, and `memberUid` attributes. |

---

## What's Next

- **Discovery Wizard** — If you have multiple OUs, use the Discovery Wizard from the Directories management page to auto-generate provisioning profiles from your directory structure.
- **Lifecycle Playbooks** — Automate onboarding/offboarding sequences in the Playbooks section.
- **Scheduled Reports** — Set up recurring reports with email or S3 delivery in the Reports section.
- **Bulk Operations** — Import/export users via CSV in the Bulk Import/Export section.
