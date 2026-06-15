// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.dto;

public record TestDeliveryResult(
    boolean success,
    Integer httpStatus,
    String message,
    long elapsedMs
) {}
