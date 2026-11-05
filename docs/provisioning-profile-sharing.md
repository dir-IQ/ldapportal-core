# Sharing provisioning profiles

Identical directories (for example dev, test and prod copies of the same
tree) usually need the same provisioning profile, and above all the same user
form. There are two ways to keep profiles in step, both on the superadmin
**Provisioning Profiles** page and both needing the *Manage provisioning
profiles* permission.

| You want to… | Use |
|---|---|
| Push one profile's form onto other profiles in this install | **Copy form to…** |
| Move a profile to another install, keep it in Git, or create it in another directory | **Export** / **Import** |

Both are also on the **Other** tab of the profile editor, where they act on the
profile you're editing: **Copy form to other profiles…**, **Export to file**
and **Import form from file…** (which replaces this profile's form and then
reloads the editor with it). They use the last saved version of the profile,
so save your changes first; while you're creating a new profile they're
disabled. The Other tab also holds the profile's **Theme Color**.

## Copy form to…

In a profile's row menu, **Copy form to…** lists every other profile, grouped
by directory. Tick the ones to update and confirm. Their user form is replaced
with this profile's.

- **Copied:** every form field (label, input type, required/editable/hidden,
  validation, defaults, computed values, allowed values), the
  self-registration and self-service layouts, and where the DN field sits.
- **Not copied:** each profile keeps its own name, target OUs, object
  classes, DN mode and template, RDN attribute, password policy, groups,
  approvals and enabled state.
- Each target checks the form against its own settings, as a manual save
  would. If any target can't take it, none are changed and the dialog says
  which one and why.

## Export and import

**Export** (row menu) downloads the profile as `<name>.profile.json`. The file
names everything by name rather than by internal id, so it can be imported
into another directory or another install. It never contains people:
approvers and the escalation account are left out.

**Import** (next to *Create Profile*) reads such a file into a directory you
choose, in one of two ways:

- **A new profile.** Created from the whole file, **disabled**, so you can
  review it before it's used. Linked "additional profiles" are matched by
  name in the target directory; any that don't exist are reported and
  skipped.
- **The form of an existing profile.** Only its form changes, as with
  *Copy form to…*. Useful to bring a form from another install.

When creating a new profile you can **move DN settings to another base DN**.
The target OUs, DN template, group assignments, lifecycle "move to" DN and
approver group are moved from the file's base DN (for example
`dc=dev,dc=corp`) to the target directory's (for example `dc=prod,dc=corp`).
DNs outside that base are left alone. **Preview** shows every DN that would
change, plus warnings such as "approvers aren't part of an export", without
saving anything.

Imports go through the same checks as creating or editing a profile by hand:
the target OU must exist (unless you tick *Create even if the target OU or
groups don't exist yet*), the name must be free in that directory, and the
license's profile limit applies.

### Not included

- Approvers and the approval escalation account (add them before enabling).
- Admin roles on the profile.
- Addon-specific per-profile settings, such as the IVIA override.

### Audit

An import that creates a profile is recorded as *Profile created* with
`operation: import`; one that replaces a form as *Profile updated* with
`operation: import-form`. *Copy form to…* records *Profile updated* with
`operation: copy-form` on each target. All three name the source profile.

### File format

The file is JSON with `"kind": "ldapportal.provisioning-profile"` and a
`formatVersion` (currently `1`). A portal refuses files from a newer format
version than it reads, rather than importing them partly.
