# Frontend scripts

## `check-utility-classes.mjs`

Scans `frontend/src/**/*.vue` for hand-rolled form-control and button
styling that bypasses the project's `.input` / `.btn-*` utility
classes (and therefore bypasses Compact density mode). Catches three
patterns:

1. `<input>` / `<select>` / `<textarea>` with `border` + `rounded` +
   `px-N` + `py-N` utilities but no `.input` class.
2. `<button>` with `bg-*` + `rounded` + `px-N` + `py-N` utilities
   but no `.btn-*` class.
3. `h-[Npx]` arbitrary-value heights on elements that ARE using
   `.btn-*` / `.input` (the pixel-fixed height blocks compact
   mode's padding-driven shrinkage).

### Run

```bash
cd frontend
npm run lint:utility-classes
```

Exits 0 on clean, 1 on any violations. Outputs `file:line` for each
violation with the offending tag snippet and the rule that fired.

### Suppress

For genuinely intentional cases (checkbox/radio inputs, color
swatches, bespoke chip controls), append a marker to the offending
opening tag:

```html
<!-- utility-class-lint:disable — color picker swatch, not a text input -->
<input type="color" v-model="primaryColor"
  class="h-9 w-10 rounded border border-gray-300" />
```

The script greps the line for `utility-class-lint:disable` (anywhere
in the tag's open). Always include a one-line comment explaining
why the case is intentional.

### CI integration

Not yet wired into CI as a hard gate — the codebase has ~120
pre-existing violations, several of which are being cleaned up in
in-flight PRs. Once the count drops, either fix all remaining or
add a baseline-ratchet mechanism (only NEW violations fail) before
adding a `npm run lint:utility-classes` step to `.github/workflows/
ci.yml`.

For now, run manually before committing changes that touch form
controls or buttons. Or run on the whole codebase to see the
current violation count.

### Why a Node.js script, not ESLint

The frontend has no ESLint setup. Adding ESLint just for these
three rules would scaffold a new dependency, config, and toolchain.
A purpose-built Node.js script gets the same coverage with zero new
deps and runs in <1 second on the full codebase.

If ESLint is added later for other reasons, port these checks to
custom rules and remove the script.
