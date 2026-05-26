// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.dashboard;

/**
 * SPI for producing scheduled-report job health counts. Implemented by
 * {@code ee.governance.service.ScheduledReportJobService}; core ships
 * {@link NoopReportJobHealthProvider} as a zero-valued default so the
 * dashboard renders cleanly when no scheduled-reports module is loaded.
 */
public interface ReportJobHealthProvider {
    ReportJobHealth health();
}
