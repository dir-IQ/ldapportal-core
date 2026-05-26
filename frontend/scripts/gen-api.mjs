#!/usr/bin/env node
// Cross-platform wrapper around openapi-typescript.
//
// Reads the backend URL from $API_URL (default http://localhost:8080),
// generates TS types, writes them to src/api/openapi.d.ts.
//
// Why a Node script instead of inline shell substitution:
// - npm script shell defaults to cmd.exe on Windows, sh on POSIX.
// - cmd.exe does not understand ${VAR:-default}.
// - cross-env / cross-env-shell only set env vars; they don't translate
//   POSIX expansion into Windows syntax.
// A Node script is the simplest cross-platform path.

import { spawnSync } from 'node:child_process';

const apiUrl = process.env.API_URL ?? 'http://localhost:8080';
const specUrl = `${apiUrl}/api/v1/openapi`;
const outputPath = 'src/api/openapi.d.ts';

console.log(`Generating types from ${specUrl} -> ${outputPath}`);

const result = spawnSync(
  'npx',
  ['openapi-typescript', specUrl, '-o', outputPath],
  { stdio: 'inherit', shell: true },
);

if (result.status !== 0) {
  console.error('gen-api: openapi-typescript failed');
  process.exit(result.status ?? 1);
}
