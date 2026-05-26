# Bulk import test fixtures

Sample CSV template + data files for exercising the **Bulk Import / Export**
page (`/admin/directories/:dirId/bulk`). The fixtures assume a base DN
of `dc=example,dc=com` and a `ou=people` / `ou=groups` OU layout. Adjust
the parent DNs in the UI (or rewrite the `members` column in `groups.csv`)
to match a different directory.

## Files

| File                     | Purpose                                                        |
|--------------------------|----------------------------------------------------------------|
| `users-template.json`    | CSV template definition for an `inetOrgPerson` user import.   |
| `users.csv`              | 8 sample users keyed by `uid`. Header row included.            |
| `groups.csv`             | 4 sample groups referencing the users above. Header included.  |

## How to use

### 1. User import

The user import requires a CSV template. You can create it either via the
UI or via the API.

**Via the UI**

1. Open *Bulk Import / Export → Users → Import*.
2. Click **Template ▾ → Add Template**.
3. Fill the modal to match `users-template.json`:
   - **Template Name:** `Sample inetOrgPerson`
   - **RDN Attribute:** `uid`
   - **Object Class:** add `inetOrgPerson` (and its parents will follow)
   - **Conflict Handling:** Skip existing
   - **CSV first row is header:** ✓ checked
   - Map the CSV columns: `username → uid`, `first_name → givenName`,
     `last_name → sn`, `full_name → cn`, `email → mail`, `title → title`,
     `phone → telephoneNumber`. Leave the rest blank.
4. **Save**, then back on the import view:
   - **Parent DN:** e.g. `ou=people,dc=example,dc=com`
   - **Import Template:** `Sample inetOrgPerson`
   - **CSV File:** upload `users.csv`
   - Click **Preview Import** to inspect the computed DNs, then
     **Confirm Import**.

**Via the API** (substitute `<dirId>` and a JWT for `<token>`):

```bash
curl -X POST "http://localhost:9090/api/directories/<dirId>/csv-templates" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d @users-template.json
```

Then drive the import endpoint as the UI does (multipart upload of
`users.csv` with a JSON `request` part referencing the new template id).

### 2. Group import

Group imports do not need a template — the CSV columns are fixed.

1. Open *Bulk Import / Export → Groups → Import*.
2. **Parent DN:** e.g. `ou=groups,dc=example,dc=com`
3. **Object Class:** `groupOfNames` (member attribute auto-resolves to
   `member`).
4. **Conflict Handling:** Skip existing.
5. **CSV File:** upload `groups.csv`.
6. **Preview Import → Confirm Import**.

The `groups.csv` `members` column uses pipe (`|`) as the separator
between full member DNs — this matches the parser's expectation. The
DN values themselves contain commas, so each cell is wrapped in
double quotes per RFC 4180.

## Re-running the imports

Both panels default to `Skip existing` conflict handling, so re-running
an import is idempotent — already-present DNs are reported under
`Skipped` rather than overwritten or erroring out. Switch the template's
conflict handling to `OVERWRITE` (or pick that on the group panel) to
exercise the update path.
