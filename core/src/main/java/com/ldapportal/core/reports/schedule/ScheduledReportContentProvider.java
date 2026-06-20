// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.core.reports.ReportData;
import com.ldapportal.entity.DirectoryConnection;

import java.util.List;
import java.util.Map;

/**
 * SPI for contributing schedulable report <em>content</em> (the report types and
 * the logic that produces their data) into core's single scheduler.
 *
 * <p>This is deliberately <b>distinct</b> from {@link com.ldapportal.core.reports.OperationalReportProvider}:
 * that SPI feeds the on-demand {@code /reports/run} endpoints and gates by
 * directory applicability, whereas this SPI feeds the scheduler and gates by
 * edition (each {@link ScheduledReportType} carries its own
 * {@code requiredEntitlement}). Registering compliance content as an
 * {@code OperationalReportProvider} would let it run through the on-demand
 * endpoints unguarded — a license leak — so the two SPIs stay separate.</p>
 *
 * <p>Core ships {@code OperationalScheduledReportContentProvider} (a thin wrapper
 * over {@code OperationalReportService}, all types ungated);
 * {@code ee/governance} ships the compliance provider (7 types,
 * {@link com.ldapportal.core.entitlement.Entitlement#GOVERNANCE}). The scheduler
 * injects {@code List<ScheduledReportContentProvider>} and resolves a type via
 * {@link #appliesTo(DirectoryConnection, String)}. A community build simply has
 * no compliance provider on the classpath.</p>
 */
public interface ScheduledReportContentProvider {

    /**
     * The report-type descriptors this provider serves, including their edition
     * gating. The exposed schedulable catalogue is the union across all providers.
     */
    List<ScheduledReportType> supportedTypes();

    /**
     * Whether this provider can produce {@code reportType} for {@code dir}.
     * Return {@code false} (rather than throwing) for types/directories it does
     * not serve.
     */
    boolean appliesTo(DirectoryConnection dir, String reportType);

    /**
     * Produces the report data. {@code scopeBaseDn} is the optional admin-view
     * scope override carried in {@code params} (may be {@code null}).
     */
    ReportData run(DirectoryConnection dir, String reportType,
                   Map<String, Object> params, String scopeBaseDn);
}
