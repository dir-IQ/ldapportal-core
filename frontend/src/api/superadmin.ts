// SPDX-License-Identifier: Apache-2.0
import { apiGet, apiPost, apiDelete } from './apiClient';
import type { components } from './openapi';
import type { AxiosResponse } from 'axios';

type SuperadminResponse = components['schemas']['SuperadminResponse'];
type CreateSuperadminRequest = components['schemas']['CreateSuperadminRequest'];

export const listSuperadmins = (): Promise<AxiosResponse<SuperadminResponse[]>> =>
  apiGet('/api/v1/superadmin/superadmins');

export const createSuperadmin = (data: CreateSuperadminRequest): Promise<AxiosResponse<SuperadminResponse>> =>
  apiPost('/api/v1/superadmin/superadmins', data);

export const deleteSuperadmin = (id: string): Promise<AxiosResponse<void>> =>
  apiDelete(`/api/v1/superadmin/superadmins/${id}` as '/api/v1/superadmin/superadmins/{id}');
