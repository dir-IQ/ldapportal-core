// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.alerting;

/**
 * Counts of alerts by state and severity. Produced by an
 * {@link AlertSummaryProvider} implementation, consumed by the
 * dashboard. Shape matches the pre-refactor wire contract — renaming
 * any field is a breaking change for the frontend.
 *
 * <p>The core owns this type so the dashboard (core) doesn't import
 * ee.alerting to render its alert tile. Community edition receives a
 * zeroed instance from {@link NoopAlertSummaryProvider}.</p>
 */
public record AlertSummary(
        long openCount,
        long acknowledgedCount,
        long criticalCount,
        long highCount,
        long mediumCount,
        long lowCount) {

    public static AlertSummary empty() {
        return new AlertSummary(0, 0, 0, 0, 0, 0);
    }
}
