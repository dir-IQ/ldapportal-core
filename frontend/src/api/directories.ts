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
type DirectoryRequest = components['schemas']['DirectoryConnectionRequest'];
// The /test endpoint uses its own request/response schemas — not DirectoryConnectionRequest.
type TestConnectionRequest = components['schemas']['TestConnectionRequest'];
type TestConnectionResult = components['schemas']['TestConnectionResult'];

export const listDirectories = (): Promise<AxiosResponse<Directory[]>> =>
  apiGet('/api/v1/superadmin/directories');

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
