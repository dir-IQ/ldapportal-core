// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.dashboard;

/**
 * Health counts for scheduled report jobs — rendered by the dashboard's
 * Report Jobs tile. Produced by a {@link ReportJobHealthProvider}
 * implementation, or zeroes when no scheduled-report module is loaded.
 */
public record ReportJobHealth(long enabled, long failed) {
    public static ReportJobHealth empty() {
        return new ReportJobHealth(0, 0);
    }
}
