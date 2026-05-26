// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;

/**
 * WEBHOOK destination: target URL + optional auth. For other channel types
 * (future), add additional fields or subclass; v1 is webhook-shaped.
 */
public record DestinationConfigRequest(
    @NotBlank @URL String url,
    @Valid WebhookAuthRequest auth    // null = unauthenticated webhook (dev only)
) {}
