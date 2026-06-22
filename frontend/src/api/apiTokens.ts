// SPDX-License-Identifier: Apache-2.0
import { apiGet, apiPost, apiDelete } from './apiClient';
import type { components } from './openapi';
import type { AxiosResponse } from 'axios';

type ApiTokenResponse = components['schemas']['ApiTokenResponse'];
type ApiTokenCreateResponse = components['schemas']['ApiTokenCreateResponse'];
type CreateApiTokenRequest = components['schemas']['CreateApiTokenRequest'];

export type { ApiTokenResponse, ApiTokenCreateResponse, CreateApiTokenRequest };

// List tokens. Active-only by default; pass includeRevoked to also return
// revoked rows (the backend derives status server-side).
export const listApiTokens = (
  includeRevoked = false,
): Promise<AxiosResponse<ApiTokenResponse[]>> =>
  apiGet('/api/v1/superadmin/api-tokens', { params: { includeRevoked } });

// Create a token. The plaintext secret is returned exactly once on the
// response and is never retrievable again.
export const createApiToken = (
  data: CreateApiTokenRequest,
): Promise<AxiosResponse<ApiTokenCreateResponse>> =>
  apiPost('/api/v1/superadmin/api-tokens', data);

// Rotate a token's secret in place (path-only; no request body). Returns the
// new plaintext once, same as create.
export const rotateApiToken = (
  id: string,
): Promise<AxiosResponse<ApiTokenCreateResponse>> =>
  apiPost(`/api/v1/superadmin/api-tokens/${id}/rotate` as '/api/v1/superadmin/api-tokens/{id}/rotate');

// Revoke a token. Idempotent — revoking an already-revoked token is a no-op.
export const revokeApiToken = (id: string): Promise<AxiosResponse<void>> =>
  apiDelete(`/api/v1/superadmin/api-tokens/${id}` as '/api/v1/superadmin/api-tokens/{id}');
