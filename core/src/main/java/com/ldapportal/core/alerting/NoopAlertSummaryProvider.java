// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.alerting;

/**
 * Default {@link AlertSummaryProvider} that returns all-zero counts.
 * Registered via {@link com.ldapportal.core.CoreNoopSpiAutoConfiguration}
 * when no other provider is present — i.e. when ee.alerting isn't on
 * the classpath. Community edition gets this and renders the alert
 * tile with zeroes; it's accurate because there's no alerting
 * infrastructure to count against.
 */
public class NoopAlertSummaryProvider implements AlertSummaryProvider {

    @Override
    public AlertSummary summary() {
        return AlertSummary.empty();
    }
}
