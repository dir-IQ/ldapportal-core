// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Never contains full secrets. Preview fields are "abc…xyz" (first 3 +
 * ellipsis + last 3 chars of the plaintext the operator saved on
 * creation). Operators correlate saved plaintext against the preview;
 * they rotate rather than recover.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookAuthResponse(
    String type,
    String tokenPreview,
    String secretPreview
) {}
