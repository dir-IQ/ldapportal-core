import { fileURLToPath, URL } from 'node:url'
import { mergeConfig, defineConfig } from 'vitest/config'
import viteConfig from './vite.config.js'

// Vitest config extends vite.config.js — same plugins, same alias, same resolve.
// We only override the `test` block (vite has no `test` block).
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      // happy-dom is faster than jsdom and sufficient for Vue component tests.
      // If a test needs a real fetch / streams API, switch to jsdom for that file
      // via `// @vitest-environment jsdom`.
      environment: 'happy-dom',

      // Jest-style globals (`describe`, `it`, `expect`, `vi`) without imports.
      // Matches conventions across the JS ecosystem and reduces boilerplate.
      globals: true,

      // Colocated *.spec.{js,ts} convention. No tests/ subdirectory.
      include: ['src/**/*.spec.{js,ts}'],

      // Defensive exclude: when SP4 lands, Playwright tests live under
      // tests/e2e/ (or similar) and ALSO use the *.spec.ts pattern. The
      // include glob above already restricts to src/, so this is belt-and-
      // suspenders — but explicit makes the intent obvious to readers.
      exclude: ['**/node_modules/**', '**/dist/**', '**/e2e/**', '**/playwright/**'],

      // Coverage opt-in: `npm run test:coverage`. No threshold enforcement —
      // gate adoption later when the team wants it.
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html'],
        include: ['src/**/*.{js,ts,vue}'],
        exclude: [
          'src/**/*.spec.{js,ts}',
          'src/api/openapi.d.ts',           // generated
          'src/api/apiClient.test-types.ts', // compile-time only
          'src/main.js',                    // app entry; no logic
        ],
      },
    },
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
  }),
)
