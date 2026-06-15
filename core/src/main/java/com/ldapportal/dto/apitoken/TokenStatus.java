// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.apitoken;

/**
 * Derived token state shown in API responses. Precedence: REVOKED > EXPIRED > ACTIVE.
 */
public enum TokenStatus { ACTIVE, EXPIRED, REVOKED }
