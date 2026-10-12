// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import java.util.List;

/**
 * Dry-run result for a bulk membership upload: every user-group pair
 * resolved and classified. {@code actionable} counts the pairs that would be
 * written on commit (WILL_ADD + WILL_REMOVE).
 */
public record BulkMembershipPreviewResult(
        int totalPairs,
        int actionable,
        List<BulkMembershipPreviewRow> rows) {
}
