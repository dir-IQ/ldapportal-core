// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.directory;

public record TestConnectionResult(
        boolean success,
        String message,
        long elapsedMs) {
}
