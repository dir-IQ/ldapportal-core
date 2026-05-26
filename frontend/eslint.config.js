// Accessibility-only ESLint config. We deliberately scope this to the
// eslint-plugin-vuejs-accessibility rules (static a11y checks on .vue
// templates) rather than full Vue/JS linting — the project has no general
// ESLint setup, and the goal here is a focused a11y regression gate that
// complements the runtime axe-core check (tests/e2e/spec/a11y.spec.ts).
//
// Run: npm run lint:a11y
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
      'src/api/openapi.d.ts', // generated
    ],
  },
  ...vueA11y.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      // vue-eslint-parser (set by the recommended config) handles the SFC;
      // it needs a TS parser for `<script setup lang="ts">` blocks or it
      // chokes on `interface`/type syntax.
      parserOptions: {
        parser: tsParser,
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
    rules: {
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
]
