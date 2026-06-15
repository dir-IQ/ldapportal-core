// SPDX-License-Identifier: Apache-2.0
// Typed wrapper around the existing axios `client`.
//
// Rationale: the axios `client` from ./client carries 401-redirect and 402-upgrade-modal
// interceptors that must apply to every API call. Rather than reimplement those in an
// openapi-fetch middleware, apiClient delegates to `client` and adds generic type
// inference on top using `paths` from openapi.d.ts.
//
// Consumers of existing .js api files continue to receive `AxiosResponse<T>` unchanged
// (same envelope shape as before — consumers that do `const { data } = await fn()`
// keep working).
//
// Path prefix handling: the backend's OpenAPI spec may or may not include '/api/v1/'
// in its paths. See Task 2 Step 2.6 in the SP1 plan. apiClient accepts the raw
// spec-path shape; if '/api/v1/' is present, it's stripped before passing to axios
// (whose baseURL already adds it).

import client from './client';
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import type { paths } from './openapi';

// ---------------- Generic helpers ----------------

// All paths that have a given HTTP method defined in the spec.
type PathsWith<M extends string> = {
  [P in keyof paths]: M extends keyof paths[P] ? P : never;
}[keyof paths];

// Operation for (path, method).
type Op<P extends keyof paths, M extends string> = M extends keyof paths[P] ? paths[P][M] : never;

// Success body: tries application/json first, then '*/*', for both 200 and 201.
// Falls back to void for 204 (no content) or 200 with no content body.
//
// The backend's OpenAPI spec uses '*/*' for ~70% of responses (file downloads,
// CSV exports, and most CRUD endpoints where content negotiation is permissive).
// Probing both media types lets the typed wrapper return the real schema for
// either annotation style without forcing call sites to use `as unknown as`
// casts. Order matters: application/json wins when both are present, since
// it's the more specific declaration. The "200 with content?: never" branch
// is for endpoints (DELETE, evict-pool) that return 200 with an empty body —
// openapi-typescript emits `content?: never` for those, not 204.
type Success<Opn> = Opn extends { responses: infer R }
  ? R extends { 200: { content: { 'application/json': infer J } } }
    ? J
    : R extends { 200: { content: { '*/*': infer J } } }
      ? J
      : R extends { 201: { content: { 'application/json': infer J } } }
        ? J
        : R extends { 201: { content: { '*/*': infer J } } }
          ? J
          : R extends { 204: any }
            ? void
            : R extends { 200: { content?: never } }
              ? void
              : unknown
  : unknown;

// Request JSON body, if the operation has one.
type Body<Opn> = Opn extends { requestBody: { content: { 'application/json': infer B } } } ? B : never;

// Strip '/api/v1' prefix from a path string, since the axios client already sets baseURL='/api/v1'.
// This is a runtime helper, not a type transform — type-side paths keep the spec shape.
function stripPrefix(path: string): string {
  return path.replace(/^\/api\/v1/, '');
}

// ---------------- Public wrappers ----------------

export function apiGet<P extends PathsWith<'get'>>(
  path: P,
  config?: AxiosRequestConfig,
): Promise<AxiosResponse<Success<Op<P, 'get'>>>> {
  return client.get(stripPrefix(path as string), config);
}

export function apiPost<P extends PathsWith<'post'>>(
  path: P,
  data?: Body<Op<P, 'post'>>,
  config?: AxiosRequestConfig,
): Promise<AxiosResponse<Success<Op<P, 'post'>>>> {
  return client.post(stripPrefix(path as string), data, config);
}

export function apiPut<P extends PathsWith<'put'>>(
  path: P,
  data?: Body<Op<P, 'put'>>,
  config?: AxiosRequestConfig,
): Promise<AxiosResponse<Success<Op<P, 'put'>>>> {
  return client.put(stripPrefix(path as string), data, config);
}

export function apiDelete<P extends PathsWith<'delete'>>(
  path: P,
  config?: AxiosRequestConfig,
): Promise<AxiosResponse<Success<Op<P, 'delete'>>>> {
  return client.delete(stripPrefix(path as string), config);
}
