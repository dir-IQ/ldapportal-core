// SPDX-License-Identifier: Apache-2.0
import { apiGet, apiPost, apiPut, apiDelete } from './apiClient';
import client from './client';
import type { components } from './openapi';
import type { AxiosResponse } from 'axios';

type SuperadminResponse = components['schemas']['SuperadminResponse'];
type CreateSuperadminRequest = components['schemas']['CreateSuperadminRequest'];
type UpdateSuperadminRequest = components['schemas']['UpdateSuperadminRequest'];
type ResetPasswordRequest = components['schemas']['ResetPasswordRequest'];

export const listSuperadmins = (): Promise<AxiosResponse<SuperadminResponse[]>> =>
  apiGet('/api/v1/superadmin/superadmins');

export const createSuperadmin = (data: CreateSuperadminRequest): Promise<AxiosResponse<SuperadminResponse>> =>
  apiPost('/api/v1/superadmin/superadmins', data);

export const updateSuperadmin = (id: string, data: UpdateSuperadminRequest): Promise<AxiosResponse<SuperadminResponse>> =>
  apiPut(`/api/v1/superadmin/superadmins/${id}` as '/api/v1/superadmin/superadmins/{id}', data);

export const resetSuperadminPassword = (id: string, data: ResetPasswordRequest): Promise<AxiosResponse<void>> =>
  apiPost(`/api/v1/superadmin/superadmins/${id}/reset-password` as '/api/v1/superadmin/superadmins/{id}/reset-password', data);

export const deleteSuperadmin = (id: string): Promise<AxiosResponse<void>> =>
  apiDelete(`/api/v1/superadmin/superadmins/${id}` as '/api/v1/superadmin/superadmins/{id}');

// System-scoped superadmin permission grants. Hand-typed (not in the generated
// OpenAPI client yet); `client` already sets baseURL='/api/v1'.
export interface SuperadminPermissionsDto {
  /** Full catalogue of permission keys (dot-notation dbValues). */
  all: string[];
  /** Keys actually granted on this account (the editable set). */
  granted: string[];
  /** Effective keys — granted, expanded to `all` for owners. */
  effective: string[];
  /** True when the account holds MANAGE_SUPERADMINS. */
  owner: boolean;
}

export const getSuperadminPermissions = (id: string): Promise<AxiosResponse<SuperadminPermissionsDto>> =>
  client.get(`/superadmin/superadmins/${id}/permissions`);

export const updateSuperadminPermissions = (
  id: string, permissions: string[],
): Promise<AxiosResponse<SuperadminPermissionsDto>> =>
  client.put(`/superadmin/superadmins/${id}/permissions`, { permissions });
