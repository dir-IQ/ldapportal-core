# Frontend conventions and patterns

Patterns established in this codebase that aren't obvious from the
top-level docs and have caught implementers by surprise. Treat this
as living documentation: when you discover or evolve a pattern, edit
the relevant section here.

Currently scoped to patterns visible on `main`. As feature branches
land, additional patterns will be promoted here when they meet the
"applied 3+ times across the codebase" bar — see "When to update
this doc" at the bottom.

For the high-level setup (Vue 3, Pinia, Tailwind 4, axios, OpenAPI
codegen), see the **Frontend** section of `CLAUDE.md`.

---

## TypeScript on Vue scripts

`<script setup lang="ts">` is the default for new components and
required when touching the `<script>` block of an existing component.
The lang opt-in trips a few patterns worth knowing:

### Error narrowing in catch blocks

Vue/axios error objects have a nested `response.data` shape that's
not in the standard `Error` type. Strict-mode catch (`e: unknown`)
won't let you optional-chain into it directly. The repo-standard
cast captures both axios responses and native errors:

```ts
} catch (e) {
  const err = e as {
    response?: { data?: { detail?: string, message?: string } },
    message?: string,
  }
  notif.error(
    err.response?.data?.detail
    || err.response?.data?.message
    || err.message
    || 'Generic fallback message',
  )
}
```

Consistent across `frontend/src/views/superadmin/IdentityMappingView.vue`,
`frontend/src/views/superadmin/DirectorySearchView.vue`,
`frontend/src/views/reports/ReportJobsView.vue`, and
`frontend/src/views/reports/AuditReportsView.vue`. Don't invent a new
shape — if the API surfaces a new error field, extend the type alias
rather than per-site casts.

### Template binding for `T | null` refs into props typed `T | undefined`

Vue prop validators that allow `null` are uncommon in this codebase;
most accept `T | undefined`. When binding a value that may be `null`
to such a prop, use the `?? undefined` coalescing idiom:

```ts
expectedVersion: props.config?.version ?? undefined,
```

The `?? undefined` is load-bearing; without it, TS rejects the
binding because `null` isn't assignable to the prop's `T | undefined`
type. Used in `frontend/src/components/identity/IdentityMappingCard.vue`
and `frontend/src/views/superadmin/IdentityMappingView.vue`.

---

## Parent ↔ child communication

Two patterns to know about, in order of preference.

### Prop-driven outcomes (preferred)

When the parent has the API call context but the child needs to react
to its resolution (success / conflict / error), pass a result-shape
prop down and have the child watch it.

**Pattern:**
```ts
// Child declares:
type SaveOutcome =
  | { kind: 'success' }
  | { kind: 'conflict' }
  | { kind: 'error', message: string }

const props = defineProps<{
  saveOutcome?: SaveOutcome | null
  // ...
}>()

watch(() => props.saveOutcome, (outcome) => {
  if (!outcome) return
  if (outcome.kind === 'success') { /* close form */ }
  else if (outcome.kind === 'conflict') { /* show reload prompt */ }
  else { /* show inline error */ }
})

// Parent sets a fresh object after each API resolution so the watcher
// fires even on repeat-of-same-kind:
saveOutcomes.value = { ...saveOutcomes.value, [id]: { kind: 'success' } }
```

Why it wins:
- Tests assert on a prop value (`card.props('saveOutcome')`), not on
  DOM internals or template-ref method calls.
- Both ends of the wire have explicit types; no `as unknown as` cast
  in tests.
- Parent never reaches into the child to mutate.

Used by `IdentityMappingCard.vue` ↔ `IdentityMappingView.vue`. The case
study is in PR #324's followup commit body.

### `defineExpose` (use sparingly)

Reserve `defineExpose` for **imperative, user-initiated actions**
where prop-driven communication doesn't fit — for example, a parent
triggering an open/toggle method, or a sidebar exposing `focusSearch`
for a keyboard shortcut.

On-main examples:
- `CommandPalette.vue` exposes `open` so the global shortcut handler
  can toggle it imperatively.
- `DnTree.vue` exposes `refreshNode` so a parent can push a targeted
  tree update after a directory operation.
- `SettingsSidebar.vue` exposes `focusSearch` for keyboard-shortcut
  focus management.

If you find yourself reaching for `defineExpose` to communicate
**reactive state**, stop and reach for a prop-driven outcome instead.
The `IdentityMappingCard` history (replaced a `defineExpose`-based
save callback with a `saveOutcome` prop) is the reference case study,
documented in PR #324.

When you do use `defineExpose`, expose a minimal interface and
document it inline:

```ts
defineExpose({
  open,  // called by AppLayout's global-shortcut handler
})
```

---

## Overlay components and `<Teleport>`

Any component using `fixed inset-0` for a full-screen overlay (modals,
drawers, dialogs) should wrap its template in `<Teleport to="body">`.
The reason isn't visibility today — it's defensiveness against any
ancestor that establishes a containing block for fixed elements:
`transform`, `filter`, `perspective`, `will-change: transform`,
`backdrop-filter`, `contain: paint|layout|strict|content`,
`container-type` (anything other than `normal`). Any of those would
re-anchor the overlay's positioning to the offending ancestor and
clip or mis-position it.

```vue
<template>
  <Teleport to="body">
    <div v-if="modelValue" class="fixed inset-0 z-40 flex items-center justify-center bg-black/40">
      <!-- backdrop + panel -->
    </div>
  </Teleport>
</template>
```

Used by `ConfirmDialog.vue`. **Do not strip Teleport because tests are
awkward** — adapt the tests instead.

### Z-index stacking

`ConfirmDialog.vue` uses `z-40` for the modal overlay. Use values at
or above `z-40` for modal dialogs so they render above any ancestor
panels. Toast notifications sit higher still (managed by the
notification store).

---

## Notification store usage

Components that perform user-visible operations (save, delete, toggle)
publish results through the Pinia notification store rather than
inline in the parent's tree:

```ts
import { useNotificationStore } from '@/stores/notifications'
const notif = useNotificationStore()

// Success:
notif.success('Identity mapping saved.')

// Failure:
notif.error('Save failed: directory has been deleted.')
```

This couples save/delete sites to the store, which means any test
mounting such a component must initialise Pinia:

```ts
import { setActivePinia, createPinia } from 'pinia'

beforeEach(() => {
  setActivePinia(createPinia())
})
```

Asymmetric save/delete error UX (e.g. inline `saveOutcome` for one
path, `notif.error` for the other) tends to be a pre-existing quirk
inherited from earlier code rather than a deliberate convention —
flag in code review when you see it.

---

## Density mode and utility classes

LDAP Portal has a sitewide Compact density preference (User Preferences
→ Display density). It's implemented purely as CSS overrides keyed
on `[data-density="compact"]` selectors against a fixed list of
class names. This means **the choice of CSS class is load-bearing**
— hand-rolled controls bypass density mode silently.

### Standard utility classes (always use these)

For new form controls and buttons, use the project's utility classes
defined in `frontend/src/assets/main.css`:

- **Form controls:** `.input`, `.input-sm`
- **Buttons:** `.btn-primary`, `.btn-secondary`, `.btn-neutral`,
  `.btn-danger`, `.btn-warning`, `.btn-success`, `.btn-danger-soft`,
  `.btn-success-soft`, `.btn-sm`

Hand-rolling with raw Tailwind (e.g.
`class="border border-gray-300 rounded-lg px-3 py-2"`) defeats
Compact mode — the user toggles their preference and the control
doesn't shrink. A sweep of the codebase found dozens of such
controls across several views and shared components like
`DnPicker`; treat hand-rolled controls as the single biggest cause
of "Compact didn't work for me" bug reports.

If a control's look doesn't match any existing utility, **add a new
one to `main.css`** rather than inlining the styles. The cost of a
new utility class is small; the cost of every consumer hand-rolling
their own variant is large.

### When adding a new utility class

A class isn't "done" until it has all six layers (matching the
existing `.btn-*` family in `main.css`):

```css
.btn-foo                              { /* base */ }
.btn-foo:hover                        { /* hover */ }
.btn-foo:disabled                     { /* disabled — opacity: 0.5 by family default */ }
[data-theme="dark"] .btn-foo          { /* dark-mode base */ }
[data-theme="dark"] .btn-foo:hover    { /* dark-mode hover */ }
[data-density="compact"] .btn-foo     { /* compact override */ }
```

Forgetting any one layer produces a class that looks fine in light-
mode comfortable density and silently breaks in the other three
combinations. The dark-mode hover rule is the most-skipped layer —
easy to ship a class that has every other state covered, then a
dark-mode user hovers it and gets a clashing color from a missing
`[data-theme="dark"] .btn-foo:hover` declaration.

### Layout utilities are also density-mode targets

A small set of standard Tailwind utilities have `[data-density=
"compact"]` overrides in `main.css`:

| Utility | Comfortable | Compact |
|---|---|---|
| `.p-6` | `1.5rem` | `1rem` |
| `.p-5` | `1.25rem` | `0.75rem` |
| `.py-3` | `0.75rem` | `0.5rem` |
| `.py-2.5` | `0.625rem` | `0.375rem` |
| `.mb-6` | `1.5rem` | `0.75rem` |
| `.mb-4` | `1rem` | `0.5rem` |
| `.mb-3` | `0.75rem` | `0.375rem` |

(Comfortable values are standard Tailwind defaults; compact values
are project-specific overrides defined in `main.css`.)

Using these classes opts a layout into density-aware behavior. A
page that explicitly does NOT want density to affect its padding /
margin (e.g. fixed-padding modals where compact would crowd
content) should use a non-listed utility — `.p-4` is unaffected,
for example. Document the deviation in a comment when you do this
deliberately, so a later reviewer doesn't "fix" it.

### Page-level wrapping

Top-level admin views should wrap content in `<PageContainer>`
(`frontend/src/components/PageContainer.vue`). It always emits a
`.p-6` wrapper, which means every page automatically participates
in compact-mode density reduction. Don't replace `<PageContainer>`
with a bare `<div class="p-8">` or similar — you'll opt out of
sitewide spacing AND compact mode.

### Auditing for utility-class compliance

When a "Compact didn't work for me on page X" report comes in, three
audit rules prevent the failure modes that recent bug rounds exposed:

**Rule 1 — Always grep the entire frontend, not just named files.**
The first move on any density bug is a single grep over
`frontend/src/`:

```bash
grep -rnE 'border border-gray-300 rounded-lg px-3 py-(1\.5|2)' frontend/src/
grep -rnE '<button[^>]+(class|:class)="[^"]*bg-(blue|green|red|amber)-[0-9]+' frontend/src/
grep -rnE 'class="[^"]*\b(btn-|input)[^"]*h-\[[0-9]+px\]' frontend/src/
```

The output IS the audit set. Don't scope to the user's named pages
— `HybridSearchInput` had a hand-rolled input + button that no
report flagged because the user only saw the page-level symptoms,
not the shared component underneath.

**Rule 2 — Follow shared components inward.** If a view uses
`<FormField>`, `<DnPicker>`, `<HybridSearchInput>`, or any other
shared form component, the audit follows the import — open and
inspect the wrapper. Marking "uses the wrapper, ticked" without
reading the wrapper's own classes is the failure mode that hid
the FormField bug across every Settings section. Wrapper components
that handle form controls MUST themselves use `.input` / `.btn-*`
internally. Verify the leaf, not the call-site.

**Rule 3 — Look for layout overrides AS WELL AS class names.**
When a class-based fix doesn't visibly land, search for `h-[`,
`min-h-[`, `style="height:`, etc. on the same elements. Compact
mode's CSS only changes padding and font-size — it can't override a
pixel-fixed height or width. The SOD "Run Scan" / Access Drift
"Manage Rules" buttons used `.btn-secondary` correctly but had
`h-[38px]` arbitrary-value heights that froze the visible size.
The Class Check A/B from the lint rules below catches this directly.

### Automated enforcement (CI)

Three custom ESLint rules in `frontend/eslint-rules/` block these
failure modes from re-entering the codebase:

| Rule | Catches |
|---|---|
| `local/no-hand-rolled-form-control` | `<input>`/`<select>`/`<textarea>` with border + rounded + padding utilities but no `.input` class |
| `local/no-hand-rolled-button` | `<button>` with `bg-*` + rounded + padding utilities but no `.btn-*` class |
| `local/no-fixed-height-on-utility` | `h-[Npx]` on elements that use `.btn-*` or `.input` |

`npm run lint` fails with these. Genuinely intentional cases
(checkbox/radio inputs, color swatches, bespoke tabbar elements)
suppress with `// eslint-disable-next-line local/...` and a one-
line comment explaining why.

A complementary Playwright `@smoke` test
(`frontend/tests/e2e/spec/density-coverage.spec.ts`) walks key
pages with compact toggled on and asserts measurable height
shrinkage. This catches the height-override class of bug that the
ESLint rules might miss if the override sneaks past the `.btn-*` /
`.input` token check.

---

## Specs (Vitest) — file naming and structure

- One `.spec.ts` per source file, colocated as
  `Component.vue` ↔ `Component.spec.ts`.
- A second spec is acceptable when one file has clearly separable
  concerns. Use sparingly — most components fit in a single spec.
- API mocks at module level via `vi.mock('@/api/...', () => ({ ... }))`,
  reset in `beforeEach`. The `vi.mocked(fn)` helper preserves the
  function's signature for `mockResolvedValue` / `mockRejectedValue`
  / `mockImplementation`.
- `AxiosResponse<T>` helper for canned responses:
  ```ts
  function ok<T>(data: T): AxiosResponse<T> {
    return { data, status: 200, statusText: 'OK', headers: {}, config: {} }
      as unknown as AxiosResponse<T>
  }
  ```
  Used in `IdentityMappingCard.spec.ts` and
  `IdentityMappingView.spec.ts`. The `as unknown as` double-cast is
  acceptable for the helper itself (avoids stubbing the full Axios
  config); don't propagate it to test bodies.
- For components that use `vue-router` composables, the component under
  test will need a router context. Either pass a stub router via
  `global.plugins` or mock the module:
  ```ts
  vi.mock('vue-router', () => ({
    useRouter: () => ({ replace: vi.fn() }),
    useRoute: () => ({ query: {} }),
  }))
  ```
  Check the existing specs under `frontend/src/views/` for the
  current approach before adding a new one.

---

## When to update this doc

- A pattern was applied 3+ times across different files but isn't
  written down.
- A code review caught the same anti-pattern in two different PRs
  (the doc was the missing piece).
- A new constraint emerges (a TS-strict mode setting, an ESLint rule,
  a build flag) that changes how a routine task is done.

Don't pre-emptively document one-off patterns. If a convention is
still being negotiated across two implementations, wait for it to
settle before writing it down here.

---

**Coming soon:** A pattern for testing teleported content with
`@vue/test-utils` is being established as part of
`feat/identity-workspace-consolidation`. Once that PR merges, this
doc will gain a section covering `attachTo: document.body`, `afterEach`
cleanup, and `document.body.querySelector` for queries that bypass the
wrapper's VNode tree.
