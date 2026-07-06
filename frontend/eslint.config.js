// Project-wide ESLint config: general JS/TS/Vue correctness linting plus the
// accessibility gate that predates it.
//
// Scope decisions:
// - eslint-plugin-vue runs at the `essential` level (error-prevention rules:
//   template no-undef, duplicate keys, v-for keys, …). The stylistic tiers
//   (`strongly-recommended`/`recommended`) are deliberately excluded — there
//   is no Prettier here and formatting churn isn't worth the diff noise.
// - typescript-eslint `recommended` covers .ts files; `no-explicit-any` is a
//   warning while the JS→TS migration is in flight.
// - The vuejs-accessibility layer is unchanged from the original a11y-only
//   config: it complements the runtime axe-core check
//   (tests/e2e/spec/a11y.spec.ts). Warn-level a11y rules are advisory; see
//   the comments below for why each is not an error.
//
// Run: npm run lint  (lint:a11y is kept as an alias for CI compatibility)
import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import vueA11y from 'eslint-plugin-vuejs-accessibility'
import tsParser from '@typescript-eslint/parser'

export default [
  {
    ignores: [
      'dist/**',
      'dist-commercial/**',
      'dist-community/**',
      'playwright-report/**',
      'test-results/**',
      'coverage/**',
      'src/api/openapi.d.ts', // generated
    ],
  },

  // ── General JS ─────────────────────────────────────────────────────────
  {
    ...js.configs.recommended,
    files: ['**/*.{js,mjs}'],
  },
  {
    files: ['**/*.{js,mjs}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      // `_`-prefix marks deliberately unused params/catches (ee-shim stubs,
      // empty catch bindings) — the ecosystem-standard escape hatch.
      'no-unused-vars': ['error', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
      }],
    },
  },

  // ── TypeScript ─────────────────────────────────────────────────────────
  ...tseslint.configs.recommended.map((c) => ({
    ...c,
    files: ['**/*.{ts,mts,tsx}'],
  })),
  {
    files: ['**/*.{ts,mts,tsx}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      // Warn (not error) while the JS→TS migration is mid-flight; the goal
      // is to stop NEW `any` creep without blocking on the existing debt.
      '@typescript-eslint/no-explicit-any': 'warn',
      // Same `_`-prefix convention as the JS block (compile-time type-probe
      // helpers in apiClient.test-types.ts are `_assert*`).
      '@typescript-eslint/no-unused-vars': ['error', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
      }],
    },
  },

  // ── Vue SFCs: error-prevention rules ───────────────────────────────────
  ...pluginVue.configs['flat/essential'],

  // ── Accessibility gate (unchanged) ─────────────────────────────────────
  ...vueA11y.configs['flat/recommended'],

  {
    files: ['**/*.vue'],
    languageOptions: {
      globals: { ...globals.browser },
      // vue-eslint-parser (set by the flat configs above) handles the SFC;
      // it needs a TS parser for `<script setup lang="ts">` blocks or it
      // chokes on `interface`/type syntax.
      parserOptions: {
        parser: tsParser,
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
    rules: {
      // The settings form is decomposed into per-section child components
      // that receive the shared `form` object and write fields on it. Deep
      // writes through an object prop are how that pattern works;
      // `shallowOnly` keeps the rule's real protection (reassigning or
      // replacing the prop itself is still an error).
      'vue/no-mutating-props': ['error', { shallowOnly: true }],

      // `label-has-for` is deprecated/over-strict: it flags a <label> unless
      // it also *wraps* the control, even when correctly tied via for/id.
      'vuejs-accessibility/label-has-for': 'off',

      // The rules below stay enabled but at WARN (advisory), not error, so the
      // gate isn't blocked by either false positives or intentional patterns:
      //
      // - form-control-has-label: the runtime axe gate (CI) already enforces
      //   labelling authoritatively and, unlike this static rule, correctly
      //   credits dynamic `:aria-label` / sibling `for`/`id`. Kept as a warn
      //   hint; axe is the source of truth.
      // - no-static-element-interactions / click-events-have-key-events: fire
      //   almost entirely on the modal/drawer backdrop pattern (`@click.self`
      //   to dismiss), which is already keyboard-accessible via Esc (the
      //   v-dialog-a11y directive) + a focusable close button.
      // - mouse-events-have-key-events: the hold-to-reveal-password buttons —
      //   a progressive enhancement; the field works without the mouse-hold.
      //
      // Everything else in the recommended set stays at error and is clean,
      // so this lint catches a NEW hard a11y violation while these advisories
      // are worked down over time.
      'vuejs-accessibility/form-control-has-label': 'warn',
      'vuejs-accessibility/no-static-element-interactions': 'warn',
      'vuejs-accessibility/click-events-have-key-events': 'warn',
      'vuejs-accessibility/mouse-events-have-key-events': 'warn',
      'vuejs-accessibility/interactive-supports-focus': 'warn',
    },
  },

  // ── Vitest specs: test-runner globals (`globals: true` in vitest.config) ─
  {
    files: ['**/*.spec.{js,ts}', '**/__tests__/**'],
    languageOptions: {
      globals: {
        describe: 'readonly',
        it: 'readonly',
        test: 'readonly',
        expect: 'readonly',
        beforeEach: 'readonly',
        afterEach: 'readonly',
        beforeAll: 'readonly',
        afterAll: 'readonly',
        vi: 'readonly',
      },
    },
  },
]
