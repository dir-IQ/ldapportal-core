# Directory Discovery Wizard — Algorithm Reference

## Profile Discovery Algorithm

### Step 1 — Find user OUs

- Start from the root DN (or directory base DN)
- Search one level for OU/container entries
- For each OU, probe for person entries (`inetOrgPerson` or AD `user`)
- If an OU has users → it becomes a proposed profile
- If it has no users → recurse into its child OUs (up to 10 levels deep)

### Step 2 — Sample each OU

- Count users (capped at 1001)
- Sample up to N entries (configurable, default 20) to detect:
  - **objectClasses** — taken from the first sampled entry (minus `top`)
  - **RDN attribute** — most common RDN across sampled DNs (e.g., `uid` or `cn`)
  - **Populated attributes** — union of all attribute names found across samples (used to suggest which attributes to hide vs show in the profile form)

### Step 3 — One profile per user OU

- Profile name = RDN value of the OU DN (e.g., `ou=Staff` → "Staff")
- Target OU DN = the discovered OU
- Object classes, RDN attribute, and attribute configs all come from the sampling

## Group Selection Algorithm

### Step 1 — Discover all groups

- Search for all group entries (`groupOfNames`, `groupOfUniqueNames`, `posixGroup`, AD `group`) under the root DN, up to 500
- For each group, detect the member attribute type (`member`, `uniqueMember`, or `memberUid`)

### Step 2 — Cross-reference with each user OU

- For each group with members, sample up to 50 member DNs
- For each member DN, check if it ends with `,{ouDn}` (suffix match = this user lives under this OU)
- Count the overlap and calculate a percentage: `overlapCount / sampledCount * 100`

### Step 3 — Rank and pre-select

- Groups are sorted by overlap percentage descending
- Groups with **≥80% overlap** are pre-checked in the wizard (auto-selected for the profile)
- Groups with >0% but <80% overlap are shown but unchecked
- Groups with 0% overlap for an OU are not shown

### Example

A group like `cn=all-employees` that contains 95% of users in `ou=Staff` would be pre-selected, while `cn=vpn-contractors` with 10% overlap would be shown but not pre-checked.

## Attribute Config Inference

For each proposed profile, the wizard infers attribute configurations from the LDAP schema:

1. Fetch MUST/MAY attributes for the discovered object classes
2. For each attribute, look up the schema syntax OID and map to an input type:

| Syntax | Input Type |
|--------|-----------|
| Boolean (`1.3.6.1.4.1.1466.115.121.1.7`) | BOOLEAN |
| Generalized Time (`1.3.6.1.4.1.1466.115.121.1.24`) | DATETIME |
| DN (`1.3.6.1.4.1.1466.115.121.1.12`) | DN_LOOKUP |
| Binary / JPEG | Skipped |
| All others | TEXT |

3. Well-known attribute name overrides: `userPassword` → PASSWORD, `description` → TEXTAREA, `jpegPhoto` → skipped
4. Multi-valued attributes with TEXT type → MULTI_VALUE
5. MUST attributes → `requiredOnCreate = true`
6. Attributes not found in the sampled entries → `hidden = true` (suggested to hide)
7. System attributes (objectClass, entryUUID, timestamps, etc.) are excluded

## Idempotency

- The discovery scan is read-only — it never writes to LDAP or the database
- Existing profiles are detected by `targetOuDn` match and shown as "Already configured" (checkbox disabled)
- The commit endpoint skips profiles whose name already exists and reports warnings
- Base DN additions skip duplicates (case-insensitive comparison)
