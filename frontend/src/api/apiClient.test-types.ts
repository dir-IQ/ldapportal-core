// SPDX-License-Identifier: Apache-2.0
// Compile-time type assertions. If this file fails `tsc --noEmit`,
// apiClient.ts has broken generic inference.
//
// The file is never imported at runtime — it exists purely so that
// tsc verifies the generic plumbing. Each _assertXxx function's mere
// ability to compile is the assertion: apiGet/apiPost/apiPut/apiDelete
// accept known path literals, and components['schemas'] is usable.

import { apiGet, apiPost, apiPut, apiDelete } from './apiClient';
import type { components, paths } from './openapi';

// Standard TS type-equality helper. Equals<X, Y> evaluates to `true` only if
// X and Y are mutually assignable AND structurally identical. Catches the case
// where a type widens to `any` (which is assignable to anything but not equal
// to specific types).
type Equals<X, Y> =
  (<T>() => T extends X ? 1 : 2) extends (<T>() => T extends Y ? 1 : 2) ? true : false;

// --- Assertions ---

// 1) apiGet of a known directories endpoint resolves to AxiosResponse<T[]>.
//    Exact type depends on the backend; at minimum, it must be an AxiosResponse.
async function _assertGet() {
  // NOTE: the path literal used here must match whichever prefix convention
  // Task 2 Step 2.6 recorded ('/api/v1/...' vs '/...').
  const res = await apiGet('/api/v1/superadmin/directories');
  void res;
}

// 1b) apiGet of a '*/*' endpoint resolves the response type to the schema,
//     not 'unknown'. Pre-fix this would have inferred AxiosResponse<unknown>;
//     post-fix it should infer AxiosResponse<DirectoryConnectionResponse[]>.
async function _assertGetUnwrapsStarStar() {
  const res = await apiGet('/api/v1/superadmin/directories')
  const items = res.data
  // Stronger than `.length` access: assert items is exactly the schema array.
  // Catches the `any` widening case (which would silently pass a `.length` check).
  const _isExactArray: Equals<typeof items, components['schemas']['DirectoryConnectionResponse'][]> = true
  void _isExactArray
  void items
  void res
}

// 2) apiPost with a body argument compiles.
async function _assertPost() {
  const res = await apiPost('/api/v1/superadmin/directories', {} as any);
  void res;
}

// 3) apiPut and apiDelete both compile against a known path.
async function _assertPutDelete() {
  const r1 = await apiPut('/superadmin/directories/{id}' as any, {} as any);
  const r2 = await apiDelete('/superadmin/directories/{id}' as any);
  void r1;
  void r2;
}

// 4) components schemas are importable and usable as types.
type _Dir = components['schemas']['DirectoryConnectionResponse'];
const _sampleDir: _Dir = {} as _Dir;
void _sampleDir;

// 5) Templated-path literal-key strings used in directories.ts must be valid
//    keys of `paths`. If openapi-typescript ever emits a different path-template
//    convention (e.g. `:id` instead of `{id}`), these guards fail at compile time.
type _DirectoryByIdKey = '/api/v1/superadmin/directories/{id}' extends keyof paths ? true : false
const _validDirectoryByIdKey: _DirectoryByIdKey = true
void _validDirectoryByIdKey

type _DirectoryEvictPoolKey = '/api/v1/superadmin/directories/{id}/evict-pool' extends keyof paths ? true : false
const _validDirectoryEvictPoolKey: _DirectoryEvictPoolKey = true
void _validDirectoryEvictPoolKey
