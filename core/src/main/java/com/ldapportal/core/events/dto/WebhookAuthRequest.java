// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Plaintext auth config on incoming request bodies. Service encrypts the
 * secret fields before persisting; ciphertext never transits this record.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookAuthRequest(
    @NotNull @Pattern(regexp = "bearer|hmac") String type,
    String token,    // bearer only — plaintext
    String secret    // hmac only   — plaintext
) {}
