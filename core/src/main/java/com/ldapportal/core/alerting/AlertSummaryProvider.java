// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.alerting;

/**
 * SPI for producing the alert-summary counts rendered by the dashboard.
 * Implemented in {@code ee.alerting.service.AlertService}; core ships a
 * {@link NoopAlertSummaryProvider} default that returns all zeroes when
 * no alerting module is present.
 */
public interface AlertSummaryProvider {
    AlertSummary summary();
}
