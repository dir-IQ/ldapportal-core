// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.dto;

public record DestinationConfigResponse(
    String url,
    WebhookAuthResponse auth
) {}
