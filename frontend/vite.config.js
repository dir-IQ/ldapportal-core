import { fileURLToPath, URL } from 'node:url'
import { resolve } from 'node:path'
import { execSync } from 'node:child_process'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

/**
 * Resolve the bundle's git short-SHA at build time. Bakes into the JS as
 * a literal string via the `define` block below, so the runtime can read
 * it for the deployment-skew banner (see `composables/useVersionCheck.ts`).
 * Non-git checkouts (release tarballs, CI without .git, Docker COPYs that
 * exclude .git) fall back to "dev" so the build never fails.
 */
function buildSha() {
  try {
    return execSync('git rev-parse --short=7 HEAD', { stdio: ['ignore', 'pipe', 'ignore'] })
      .toString().trim() || 'dev'
  } catch {
    return 'dev'
  }
}

// Edition selector. `commercial` (default) builds the full app; `community`
// rewrites every `@/ee` / `@/ee/*` import to the inert `src/ee-shim.js`, so no
// commercial source under `src/ee/` reaches the bundle. Set via the
// VITE_EDITION env var (see the build:community / build:commercial npm scripts).
const EDITION = process.env.VITE_EDITION || 'community'

// Two-repo (Phase 5b) commercial build: when src/ee lives in a separate
// ldapportal-ee checkout, point `@/ee` at it via EE_SRC_DIR. Unset in the
// monorepo (src/ee is local, reached through `@`) and in community builds
// (which use the shim), so this is a no-op for every build that exists today.
const EE_SRC_DIR = process.env.EE_SRC_DIR

const SRC = fileURLToPath(new URL('./src', import.meta.url))
const EE_SHIM = fileURLToPath(new URL('./src/ee-shim.js', import.meta.url))

// Alias order matters: the `@/ee` rule (community shim, or external ee source)
// must precede the general `@` rule so `@/ee*` is caught before `@` expands it.
const alias = [
  ...(EDITION === 'community'
    ? [{ find: /^@\/ee(\/.*)?$/, replacement: EE_SHIM }]
    : EE_SRC_DIR
      ? [{ find: /^@\/ee(\/.*)?$/, replacement: resolve(EE_SRC_DIR) + '$1' }]
      : []),
  { find: '@', replacement: SRC },
]

export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
  ],
  define: {
    __BUILD_SHA__: JSON.stringify(buildSha()),
  },
  resolve: {
    alias,
  },
  server: {
    port: 5173,
    proxy: {
      '/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,  // allow self-signed certs in local dev
      }
    }
  }
})
