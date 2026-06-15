// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.dashboard;

/**
 * Default {@link ReportJobHealthProvider} — zero counts. Registered via
 * {@link com.ldapportal.core.CoreNoopSpiAutoConfiguration} when no other
 * provider is present (community edition or governance module not loaded).
 */
public class NoopReportJobHealthProvider implements ReportJobHealthProvider {
    @Override
    public ReportJobHealth health() {
        return ReportJobHealth.empty();
    }
}
