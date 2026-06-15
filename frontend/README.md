# ldap-portal-ui

This template should help get you started developing with Vue 3 in Vite.

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
  - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
  - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
  - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
  - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Compile and Minify for Production

```sh
npm run build
```

## Generated API types (openapi-typescript)

The TypeScript type definitions in `src/api/openapi.d.ts` are generated from the
backend's live OpenAPI spec at `GET /api/v1/openapi`. The file is committed to
git so frontend builds and type-checks never depend on a running backend.

### When to regenerate

Regenerate whenever the backend contract changes in a way your frontend code
needs to see:

- A backend DTO adds, removes, or renames a field.
- A new endpoint is added that you want to call.
- A response type changes shape.

You do NOT need to regenerate for backend internal refactors that don't change
the wire format.

### How to regenerate

1. Start the backend locally:
   ```
   JAVA_HOME="<your JDK>" ./mvnw spring-boot:run -pl core
   ```
2. Wait for `Started LDAPPortalApplication` in the log.
3. From `frontend/`, run:
   ```
   npm run gen:api
   ```
   Or to target a non-default URL:
   ```
   API_URL=http://localhost:9000 npm run gen:api
   ```
4. `src/api/openapi.d.ts` is overwritten.
5. Commit the regenerated file.

### Using the typed client

See `src/api/directories.ts` for the canonical example. Pattern:

```ts
import { apiGet, apiPost } from '@/api/apiClient';
import type { components } from '@/api/openapi';
import type { AxiosResponse } from 'axios';

type MyDto = components['schemas']['MyDtoName'];

export const listThings = (): Promise<AxiosResponse<MyDto[]>> =>
  apiGet('/api/v1/things');

export const createThing = (data: MyDto): Promise<AxiosResponse<MyDto>> =>
  apiPost('/api/v1/things', data);

// For templated paths (the `${id}` interpolation), TypeScript widens the
// template literal to `string`, which doesn't satisfy the path-keyed generic.
// Cast to the openapi.d.ts literal key — NOT `as any` (which collapses the
// response type to unknown):
export const getThing = (id: string): Promise<AxiosResponse<MyDto>> =>
  apiGet(`/api/v1/things/${id}` as '/api/v1/things/{id}');
```

If you add a templated-path cast, add a matching `keyof paths` guard in
`src/api/apiClient.test-types.ts` so a future spec change (e.g. `{id}` → `:id`)
fails at compile time instead of silently degrading inference.

Return shape is `AxiosResponse<T>` — destructure `.data` at the call site
(matches the pre-migration axios convention). This keeps the 26 `.js` api
files compatible with typed ones — no big-bang migration needed.

### Type-check without building

```
npm run typecheck   # runs tsc --noEmit
```

### Note on Vue single-file components

Today the frontend has zero `.vue` files using `<script setup lang="ts">` — all
Vue SFCs are plain JS. Plain `tsc --noEmit` (our current `typecheck` script)
therefore catches every TS error in the codebase. The moment the first TS-using
SFC lands, swap `tsc` for `vue-tsc` (install `vue-tsc` as a devDep and change
the `typecheck` script). `vue-tsc` extends `tsc` to also type-check inside
`<script setup lang="ts">` blocks, which plain `tsc` silently ignores.

## Running unit tests

The frontend uses Vitest + Vue Test Utils + happy-dom. Tests are colocated
with source as `*.spec.{js,ts}` files (e.g. `useRelativeTime.spec.js` next
to `useRelativeTime.js`).

```bash
npm run test:unit         # single pass, used in CI
npm run test:unit:watch   # watch mode for local dev
npm run test:coverage     # V8 native coverage to coverage/ + console
```

### What's tested today

- `src/composables/useRelativeTime.spec.js` — pure formatters + reactive
  composable with fake timers.
- `src/stores/upgradeModal.spec.js` — Pinia store state transitions.
- `src/components/HelpTip.spec.js` — Vue SFC mount + interaction.
- `src/api/apiClient.spec.ts` — typed apiClient runtime behavior
  (axios delegation, prefix stripping, config pass-through).

These are the patterns to copy when adding new tests.

### Test conventions

- **Pinia stores:** `setActivePinia(createPinia())` in `beforeEach` for clean state.
- **Time-dependent code:** `vi.useFakeTimers()` + `vi.setSystemTime(new Date('...'))` in `beforeEach`, `vi.useRealTimers()` in `afterEach`.
- **API modules:** `vi.mock('./client')` (or `@/api/client`) with a fake axios surface.
- **Vue components:** `mount(Component, { props: {...} })` from `@vue/test-utils`; use `wrapper.trigger('event')` to drive interactions.

### When to write a test

Reach for a unit test when:
- Logic in a composable / Pinia store / utility function (not just plumbing).
- Component renders differently based on props or local state.
- A subtle bug got fixed (regression test).

Skip unit tests for:
- Pure layout / styling components.
- Thin wrappers over a backend call (cover those at the E2E layer in SP4).

### Coverage

`npm run test:coverage` produces an HTML report under `coverage/`. Open
`coverage/index.html` in a browser. There is NO threshold gate today —
adopt one when the team agrees on a number.
