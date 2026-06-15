#!/usr/bin/env node
// Fails if a community build leaked commercial (ee) source.
//
// Vite names each code-split chunk after the source module it was produced
// from, so a commercial module that slipped into the community graph shows up
// as a `<Basename>-<hash>.js` (or `.css`) chunk. We derive the set of
// commercial basenames straight from the `src/ee/` tree — so the check can't
// drift as ee modules are added — and assert none of them appear in
// dist-community/. This is the frontend counterpart to the backend's
// `verify-no-ee-bytecode` JAR scan.
//
// The real guard against leaks is the `@/ee` → `src/ee-shim.js` alias in
// vite.config.js, which stubs the whole commercial subtree; this scan is a
// defense-in-depth backstop that also catches commercial code mistakenly
// placed OUTSIDE `src/ee/` and imported from core.
//
// Run after `npm run build:community`.

import { readdirSync, existsSync, statSync } from 'node:fs';
import { join, basename, extname } from 'node:path';

const DIST = 'dist-community';
const ASSETS = join(DIST, 'assets');
const EE_SRC = join('src', 'ee');
const SRC = 'src';

const CODE_EXT = new Set(['.vue', '.js', '.ts', '.mjs', '.jsx', '.tsx']);

function walk(dir, fn) {
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    if (statSync(p).isDirectory()) walk(p, fn);
    else fn(p);
  }
}

// Module basename (no extension), skipping spec/test files.
function moduleName(path) {
  if (/\.(spec|test)\./.test(path)) return null;
  if (!CODE_EXT.has(extname(path))) return null;
  return basename(path, extname(path));
}

// Seam plumbing that never reaches the community bundle and whose names are too
// generic to key on.
const IGNORE = new Set(['index', 'routes']);

if (!existsSync(EE_SRC)) {
  // No src/ee in this checkout (the community-only ldapportal-core repo). There
  // is no commercial source that could leak into the bundle, so the scan has
  // nothing to check — skip cleanly rather than fail. (In the monorepo src/ee is
  // present and the scan runs normally.)
  console.log(`[scan] ${EE_SRC} not present — community-only checkout, no commercial source to leak; skipping.`);
  process.exit(0);
}
if (!existsSync(ASSETS)) {
  console.error(`[scan] ${ASSETS} not found — run \`npm run build:community\` first.`);
  process.exit(1);
}

// Commercial basenames, and core basenames (to drop collisions like
// `MetricCard`, which exists in both src/ee/views/auditor and core dashboard —
// a core chunk by that name must not trip the scan).
const eeNames = new Set();
walk(EE_SRC, (p) => {
  const n = moduleName(p);
  if (n && !IGNORE.has(n)) eeNames.add(n);
});

const coreNames = new Set();
walk(SRC, (p) => {
  if (p.startsWith(EE_SRC + '/') || p.startsWith(EE_SRC + '\\')) return;
  const n = moduleName(p);
  if (n) coreNames.add(n);
});

const eeOnly = [...eeNames].filter((n) => !coreNames.has(n));

const files = readdirSync(ASSETS);
const leaks = [];
for (const name of eeOnly) {
  // Vite emits `<Name>-<hash>.<ext>`; match the `<Name>-` prefix or bare name.
  const hit = files.find((f) => f.startsWith(`${name}-`) || basename(f, extname(f)) === name);
  if (hit) leaks.push(`${name} → assets/${hit}`);
}

if (leaks.length > 0) {
  console.error('[scan] FAIL — commercial (ee) source leaked into the community bundle:');
  for (const l of leaks) console.error(`  - ${l}`);
  console.error('\nCore code must reach commercial features only through the `@/ee` seam,');
  console.error('which the community build aliases to src/ee-shim.js. A leak usually means');
  console.error('commercial code was placed outside src/ee/, or imported by a non-stubbed path.');
  process.exit(1);
}

console.log(
  `[scan] OK — no commercial chunks in ${ASSETS} ` +
  `(${files.length} assets checked against ${eeOnly.length} ee basenames).`,
);
