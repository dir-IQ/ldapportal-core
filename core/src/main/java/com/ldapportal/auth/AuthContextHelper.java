// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Static helpers for reading context off {@link SecurityContextHolder}.
 */
public final class AuthContextHelper {

    private AuthContextHelper() {}

    /**
     * If the current request was authenticated via an API token, returns the
     * token's id + name. Returns empty for direct-login JWT requests.
     */
    public static Optional<ApiTokenAuthenticationDetails> currentApiToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Optional.empty();
        Object details = auth.getDetails();
        return details instanceof ApiTokenAuthenticationDetails d
                ? Optional.of(d) : Optional.empty();
    }
}
