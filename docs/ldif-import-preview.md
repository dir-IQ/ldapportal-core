# LDIF Import Preview

**Status:** Shipped (v1: per-entry preview, conflict detection, member deltas, 2026-06-03).

The Directory Browser's **Import LDIF** action (superadmin only) shows a
per-record **preview** of exactly what an LDIF file would do before anything is
written. It replaces the old count-only "dry run".

## Workflow

1. **Directory Browser → Import LDIF.** Drop an `.ldif` file and pick a
   **conflict mode**:
   - **Skip** — leave existing entries unchanged.
   - **Update** — overwrite the attributes of existing entries.
2. **Preview.** The file is parsed and classified read-only (nothing is
   written). You get:
   - **Summary chips** — `Add / Modify / Delete / Skip`, plus `⚠ warnings` and
     `✕ errors`.
   - **Filter** — `All / Adds / Modifies / Deletes / Conflicts / Errors`
     (defaults to **Errors** when any record failed to parse).
   - **DN search** and a paged table (Op · DN · attrs / member Δ · issues).
   - **Row detail** — click any row to see its full attributes (and, for group
     `modify`, the member `+added / −removed` delta).
3. **Import.** Applies the **exact** records you previewed (no re-upload). The
   button shows how many records will be acted on (adds + modifies + deletes).
   The result (added / updated / skipped / failed) is shown and audited as
   `LDIF_IMPORT`.

## How records are classified

| Record | Classified as |
|---|---|
| Content entry, not in directory | **Add** |
| Content entry, exists, mode = Update | **Modify** (with a `CONFLICT_EXISTS` note) |
| Content entry, exists, mode = Skip | **Skip** (with a `CONFLICT_EXISTS` note) |
| `changetype: add / modify / delete / moddn` | **Add / Modify / Delete / Move** |
| Unparseable record | **Error** |

Per-row issues: `PARSE_ERROR`, `INVALID_DN`, `OUT_OF_SCOPE` (DN not under the
directory base), `CONFLICT_EXISTS`.

## Notes & limits

- **Conflict detection is batched** — existence is checked one level per parent
  OU, so a few-thousand-entry import doesn't make thousands of round-trips.
- **Group v1 = member delta only.** A group `modify` shows `+N / −N`; a group
  `add` shows its member count. It does **not** yet verify that referenced
  members resolve (deferred).
- **Preview is held in memory** under a short-lived id (per superadmin,
  ~30 min TTL). If it expires, re-run the preview.
- **Size cap** — `ldapportal.ldif.preview.max-records` (default 50,000). Larger
  files are rejected with guidance; split them.
- Deferred to later tiers: schema (`MUST`-attribute) validation, attribute-level
  old→new diffs, full membership resolution, and async progress for very large
  files. See `docs/plans/2026-06-02-ldif-import-preview.md`.
