// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import java.util.List;

/**
 * Result of a bulk membership commit. {@code failed} counts REFUSED,
 * BLOCKED and ERROR rows together.
 */
public record BulkMembershipResult(
        int totalPairs,
        long applied,
        long queued,
        long skipped,
        long failed,
        List<BulkMembershipRowResult> rows) {
}
