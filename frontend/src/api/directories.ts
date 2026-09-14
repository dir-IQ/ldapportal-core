// SPDX-License-Identifier: Apache-2.0
// Typed directories API.
//
// First api file migrated to apiClient.ts as part of SP1 of the
// comprehensive test infrastructure plan. Preserves axios-envelope
// return shape (AxiosResponse<T>) so the 20 consumer files that
// destructure 'const { data } = await listDirectories()' work unchanged.
//
// Migration pattern for future files:
//   1. Replace 'import client from \'./client\'' with imports from './apiClient'.
//   2. Replace 'client.get(path)' with 'apiGet(path)' (etc.).
//   3. Add type annotations using 'components[\'schemas\'][...]' where useful.
//   4. Keep the return shape as AxiosResponse<T> — avoids touching consumers.

import { apiGet, apiPost, apiPut, apiDelete } from './apiClient';
import type { components } from './openapi';
import type { AxiosResponse } from 'axios';

type Directory = components['schemas']['DirectoryConnectionResponse'];
export type DirectorySummary = components['schemas']['DirectorySummaryResponse'];
type DirectoryRequest = components['schemas']['DirectoryConnectionRequest'];
// The /test endpoint uses its own request/response schemas — not DirectoryConnectionRequest.
type TestConnectionRequest = components['schemas']['TestConnectionRequest'];
type TestConnectionResult = components['schemas']['TestConnectionResult'];

// Directory lists are shown in many pickers/panels across the app; sort here
// — the single chokepoint every `listDirectories()` consumer (and the
// useDirectoryPicker composable) funnels through — so they all render in
// case-insensitive alphabetical order by display name.
const byDisplayName = (a: { displayName?: string }, b: { displayName?: string }): number =>
  (a.displayName ?? '').localeCompare(b.displayName ?? '', undefined, { sensitivity: 'base' });

// Picker listing: identities only (id, slug, type, name, enabled). Served to
// every superadmin, so pages outside the Directory Connections area keep
// working for a scoped superadmin without VIEW_DIRECTORIES.
export const listDirectories = async (): Promise<AxiosResponse<DirectorySummary[]>> => {
  const res = await apiGet('/api/v1/superadmin/directories/summary');
  res.data = [...res.data].sort(byDisplayName);
  return res;
};

// Full connection listing (host, bind DN, pool settings, …) for the Directory
// Connections page itself; requires VIEW_DIRECTORIES.
export const listDirectoryConnections = async (): Promise<AxiosResponse<Directory[]>> => {
  const res = await apiGet('/api/v1/superadmin/directories');
  res.data = [...res.data].sort(byDisplayName);
  return res;
};

export const getDirectory = (id: string): Promise<AxiosResponse<Directory>> =>
  apiGet(`/api/v1/superadmin/directories/${id}` as '/api/v1/superadmin/directories/{id}');

export const createDirectory = (data: DirectoryRequest): Promise<AxiosResponse<Directory>> =>
  apiPost('/api/v1/superadmin/directories', data);

export const updateDirectory = (id: string, data: DirectoryRequest): Promise<AxiosResponse<Directory>> =>
  apiPut(`/api/v1/superadmin/directories/${id}` as '/api/v1/superadmin/directories/{id}', data);

export const deleteDirectory = (id: string): Promise<AxiosResponse<void>> =>
  apiDelete(`/api/v1/superadmin/directories/${id}` as '/api/v1/superadmin/directories/{id}');

export const testDirectory = (data: TestConnectionRequest): Promise<AxiosResponse<TestConnectionResult>> =>
  apiPost('/api/v1/superadmin/directories/test', data);

export const evictPool = (id: string): Promise<AxiosResponse<void>> =>
  apiPost(`/api/v1/superadmin/directories/${id}/evict-pool` as '/api/v1/superadmin/directories/{id}/evict-pool');

// Live reachability probe for a stored directory (uses its saved
// credentials). success=true → reachable. Drives the per-row status dot.
export const getDirectoryStatus = (id: string): Promise<AxiosResponse<TestConnectionResult>> =>
  apiGet(`/api/v1/superadmin/directories/${id}/status` as '/api/v1/superadmin/directories/{id}/status');
