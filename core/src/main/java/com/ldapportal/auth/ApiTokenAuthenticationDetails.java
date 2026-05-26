// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import java.util.UUID;

/**
 * Marker / metadata placed in {@link org.springframework.security.core.Authentication#getDetails()}
 * when a request authenticated via an API token. Absent for direct-login JWT requests.
 *
 * <p>Read via {@link AuthContextHelper#currentApiToken()}.</p>
 */
public record ApiTokenAuthenticationDetails(UUID tokenId, String tokenName) {}
