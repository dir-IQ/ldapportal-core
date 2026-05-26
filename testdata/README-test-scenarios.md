# Test Scenarios — AcmeCorp LDIF Data

## Loading the Data

```bash
# 1. Load the base directory (5000+ users, groups, OUs)
ldapadd -x -D "cn=admin,dc=acmecorp,dc=com" -w <password> \
  -f testdata/acmecorp-5000-users.ldif

# 2. Load the test scenarios (adds groups, modifies memberships, adds test users)
ldapmodify -x -D "cn=admin,dc=acmecorp,dc=com" -w <password> \
  -a -f testdata/acmecorp-test-scenarios.ldif

# 3. (Optional) Create orphaned entries for integrity check:
#    Delete the temporary parent OUs — their children become orphaned.
#    NOTE: Requires the LDAP server to support deleting non-leaf entries,
#    or use the tree-delete control. If this fails, skip the orphaned-entry test.
ldapdelete -x -D "cn=admin,dc=acmecorp,dc=com" -w <password> \
  "ou=Contractors,ou=People,dc=acmecorp,dc=com"
ldapdelete -x -D "cn=admin,dc=acmecorp,dc=com" -w <password> \
  "ou=Vendors,ou=People,dc=acmecorp,dc=com"
```

### Re-importing (cleanup first)

If you need to re-import after a previous load:

```bash
# 1. Remove members added to existing groups
ldapmodify -x -D "cn=admin,dc=acmecorp,dc=com" -w <password> \
  -f testdata/acmecorp-test-cleanup.ldif

# 2. Delete test entries (users, new groups, OUs)
bash testdata/acmecorp-test-cleanup-delete.sh \
  "cn=admin,dc=acmecorp,dc=com" <password>

# 3. Re-import
ldapmodify -x -D "cn=admin,dc=acmecorp,dc=com" -w <password> \
  -a -f testdata/acmecorp-test-scenarios.ldif
```

---

## Test 1: SoD Violation Detection

### Setup — Create SoD Policies

Create these 4 SoD policies in LDAP Portal (Superadmin > SoD Policies > New Policy):

| Policy Name | Group A DN | Group B DN | Severity |
|---|---|---|---|
| Payroll Admin / Approver | `cn=Payroll-Admin,ou=Access,ou=Groups,dc=acmecorp,dc=com` | `cn=Payroll-Approver,ou=Access,ou=Groups,dc=acmecorp,dc=com` | CRITICAL |
| Vendor Mgmt / AP Processing | `cn=Vendor-Management,ou=Access,ou=Groups,dc=acmecorp,dc=com` | `cn=AP-Processing,ou=Access,ou=Groups,dc=acmecorp,dc=com` | HIGH |
| Code Deploy / Code Review | `cn=Code-Deploy-Prod,ou=Access,ou=Groups,dc=acmecorp,dc=com` | `cn=Code-Review-Approver,ou=Access,ou=Groups,dc=acmecorp,dc=com` | HIGH |
| HR Data Admin / PII Access | `cn=HR-DataAdmin,ou=Access,ou=Groups,dc=acmecorp,dc=com` | `cn=DataAccess-PII,ou=Access,ou=Groups,dc=acmecorp,dc=com` | MEDIUM |

### Run — Click "Run Scan" on the SoD Policies page

### Expected Violations

| Policy | Violating Users | Reason |
|---|---|---|
| Payroll Admin / Approver | howard.blanc, heather.fontaine, madison.castro | In both Payroll-Admin AND Payroll-Approver |
| Vendor Mgmt / AP Processing | jason.ross2, jose.graham | In both Vendor-Management AND AP-Processing |
| Code Deploy / Code Review | brian.moreau, maria.dubois | In both Code-Deploy-Prod AND Code-Review-Approver |
| HR Data Admin / PII Access | dennis.dubois2, alexis.meyer, grace.garcia | In both HR-DataAdmin AND DataAccess-PII |

**Total: 10 SoD violations across 4 policies.**

---

## Test 2: Access Drift Detection

### Setup — Create Peer Group Rules

Create this rule (Superadmin > Access Drift > Manage Rules):

| Rule Name | Grouping Attribute | Normal Threshold | Anomaly Threshold |
|---|---|---|---|
| Department Peer Group | `department` | 50% | 15% |

The `department` attribute comes from the `ou` field on each user. Users are bucketed by department, and any group membership held by fewer than 15% of the peer group is flagged.

### Run — Click "Run Analysis"

### Expected Drift Findings

| User | Department | Anomalous Group | Why Flagged |
|---|---|---|---|
| nancy.watanabe | Sales | AWS-Developers | ~0.1% of Sales in AWS-Developers |
| james.richardson | Sales | DataAccess-PII | ~0.1% of Sales in DataAccess-PII |
| pierre.gupta | Marketing | DataAccess-Financial | ~0.3% of Marketing in DataAccess-Financial |
| alyssa.davis | Marketing | DataAccess-Financial | ~0.3% of Marketing in DataAccess-Financial |
| ann.roux | Legal | BuildSystem-Users | ~0.7% of Legal in BuildSystem-Users |
| howard.blanc | Finance | GitHub-Users | ~0.7% of Finance in GitHub-Users |
| bobby.harris2 | Finance | GitHub-Users | ~0.7% of Finance in GitHub-Users |
| zachary.weber | HumanResources | AWS-Developers | ~0.5% of HR in AWS-Developers |
| disabled.jane | Finance | Payroll-Admin, DataAccess-Financial | Terminated account still in sensitive groups |
| disabled.marcus | Engineering | Code-Deploy-Prod, AWS-Developers | Terminated account still in sensitive groups |
| disabled.priya | HumanResources | DataAccess-PII, HR-DataAdmin | Terminated account still in sensitive groups |
| disabled.tom | IT | AWS-Developers, SecurityChampions | Terminated account still in sensitive groups |

**Note:** The disabled accounts will also appear here because they're anomalous within their peer groups — they're in sensitive groups while being terminated.

---

## Test 3: Integrity Check

### Run — Superadmin > Integrity Check

- **Directory:** Select the AcmeCorp directory
- **Base DN:** `dc=acmecorp,dc=com` (or leave blank for default)
- **Checks:** Select all three: BROKEN_MEMBER, ORPHANED_ENTRY, EMPTY_GROUP

### Expected Results

#### Broken Member References (6 findings)

| Group | Broken DN | Cause |
|---|---|---|
| cn=OnCall-Rotation | uid=deleted.user1,ou=Engineering,... | User deleted, group not cleaned |
| cn=OnCall-Rotation | uid=terminated.employee,ou=Sales,... | User deleted, group not cleaned |
| cn=OnCall-Rotation | uid=former.contractor,ou=IT,... | User deleted, group not cleaned |
| cn=SecurityChampions | uid=old.securitylead,ou=Engineering,... | Stale reference |
| cn=SecurityChampions | uid=past.ciso,ou=IT,... | Stale reference |
| cn=AWS-ReadOnly | uid=susan.schimdt,ou=Engineering,... | Typo in DN (schimdt vs schmidt) |

#### Orphaned Entries (2 findings)

| Entry DN | Missing Parent |
|---|---|
| uid=ghost.admin,ou=Contractors,ou=People,dc=acmecorp,dc=com | ou=Contractors,ou=People does not exist |
| uid=vendor.integrator,ou=Vendors,ou=People,dc=acmecorp,dc=com | ou=Vendors,ou=People does not exist |

#### Empty Groups (3 findings)

| Group DN | Cause |
|---|---|
| cn=Decommissioned-ProjectX,ou=Projects,... | Former project team, all members removed |
| cn=Temp-Audit-2025,ou=Access,... | Temporary group never populated |
| cn=Migration-Staging,ou=Access,... | Staging group left empty |

**Total: 11 integrity issues (6 broken + 2 orphaned + 3 empty).**

---

## Test 4: Users in No Groups

These 5 users exist in the directory but have zero group memberships — they should appear in the User Access Report with no groups:

| User | Department | Title |
|---|---|---|
| new.hire.emma | Engineering | Junior Developer |
| new.hire.raj | Finance | Financial Analyst |
| new.hire.sofia | Sales | Account Executive |
| new.hire.omar | Marketing | Content Strategist |
| new.hire.mei | HumanResources | HR Coordinator |

### How to Verify

Run the **User Access Report** (Superadmin > Compliance Reports > User Access Report) with no group filter. These users should appear with empty group lists.

Alternatively, search for any of these users in the **Directory Browser** and confirm they have no `memberOf` or group memberships.

---

## Test 5: Missing Department Group Membership

These 3 users are in a department OU and in AllEmployees but NOT in their `dept-*` group:

| User | OU | Missing From Group |
|---|---|---|
| missing.dept.kyle | ou=Engineering | cn=dept-engineering |
| missing.dept.anna | ou=Finance | cn=dept-finance |
| missing.dept.lucas | ou=Sales | cn=dept-sales |

### How to Verify

Run the **Group Membership Report** for each department group and confirm these users are absent. Then browse each user — they'll show as members of `AllEmployees` but not their department group.

This simulates an incomplete onboarding process where the employee was created in the correct OU but the group membership step was skipped.

---

## Test 6: Disabled Accounts in Sensitive Groups

These 4 terminated accounts have `employeeType: Terminated` and `loginShell: /sbin/nologin` but remain in sensitive groups:

| User | Former Role | Sensitive Groups Still In |
|---|---|---|
| disabled.jane | Finance Director | DataAccess-Financial, Payroll-Admin, dept-finance |
| disabled.marcus | Lead Architect | AWS-Developers, Code-Deploy-Prod, dept-engineering |
| disabled.priya | HR Manager | DataAccess-PII, HR-DataAdmin, dept-humanresources |
| disabled.tom | IT Security Admin | AWS-Developers, SecurityChampions, dept-it |

### How to Verify

1. **Access Drift** will flag these users as anomalies in their peer group (their terminated status makes them outliers).
2. **User Access Report** will show them with active group memberships despite being disabled.
3. **Search** for `(employeeType=Terminated)` in the LDAP Search page to list all disabled accounts, then cross-reference with group memberships.

---

## Reports Configuration Summary

| Report | Where | Parameters |
|---|---|---|
| **SoD Scan** | Superadmin > SoD Policies > Run Scan | Directory (auto) |
| **Access Drift Analysis** | Superadmin > Access Drift > Run Analysis | Directory (auto), rules must exist |
| **Integrity Check** | Superadmin > Integrity Check | Directory, Base DN: `dc=acmecorp,dc=com`, Checks: all 3 |
| **User Access Report** | Superadmin > Compliance Reports | No group filter (shows all users including ungrouped) |
| **Group Membership Report** | Superadmin > Reports > Run Report Now | Type: GROUP_MEMBERSHIP, Param: specific group DN |
| **LDAP Search (disabled accts)** | Superadmin > LDAP Search | Base DN: `dc=acmecorp,dc=com`, Filter: `(employeeType=Terminated)` |
