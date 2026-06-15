// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import com.ldapportal.entity.DirectoryConnection;

import java.util.Map;

/**
 * SPI for addon-contributed operational reports. Core ships a fixed set of
 * vendor-agnostic reports ({@link OperationalReportType}); vendor-specific
 * reports (e.g. an IVIA orphaned-account scan) live in their addon and plug in
 * here, so core never imports addon code and community builds — which have no
 * providers on the classpath — simply expose the built-in set.
 *
 * <p>{@link OperationalReportService} injects {@code List<OperationalReportProvider>}
 * (empty when no addon contributes one) and consults it only after the built-in
 * {@link OperationalReportType} names fail to match the requested report type.
 * Provider {@link #reportId() ids} must therefore not collide with any enum
 * constant name.</p>
 *
 * <p>Mirrors the optional dashboard-provider SPIs (e.g.
 * {@code ReportJobHealthProvider}) — the established pattern for letting an
 * addon contribute behaviour into core without a reverse dependency.</p>
 */
public interface OperationalReportProvider {

    /** Stable, unique report id sent by the client as the report type. */
    String reportId();

    /**
     * Whether this report can run against the given directory. Used both to
     * validate an incoming request and to gate availability. Return
     * {@code false} for directories the report doesn't apply to (e.g. a
     * non-IVIA or inline-mode directory) rather than throwing.
     */
    boolean appliesTo(DirectoryConnection dir);

    /**
     * Executes the report. {@code scopeBaseDn} is the optional admin-view scope
     * override carried in report params (may be {@code null}).
     */
    ReportData run(DirectoryConnection dir, Map<String, Object> params, String scopeBaseDn);
}
