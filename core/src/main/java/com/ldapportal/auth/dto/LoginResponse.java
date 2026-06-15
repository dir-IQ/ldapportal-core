// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Successful login response.
 *
 * <p>The JWT {@code token} is also written as an {@code HttpOnly} cookie by
 * {@link com.ldapportal.controller.AuthController} and is excluded from the
 * JSON body so it is never accessible to JavaScript.</p>
 */
public record LoginResponse(
        @JsonIgnore String token,
        String username,
        String accountType,
        String id) {
}
