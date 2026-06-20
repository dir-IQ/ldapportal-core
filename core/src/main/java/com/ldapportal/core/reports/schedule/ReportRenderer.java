// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.core.reports.ReportData;

/**
 * SPI for rendering {@link ReportData} into a concrete output format.
 *
 * <p>Core ships the CSV renderer (wrapping {@code CsvUtils}); {@code ee/governance}
 * ships the PDF renderer (OpenPDF stays a commercial-only dependency). The
 * scheduler injects {@code List<ReportRenderer>} and resolves a job's
 * {@link ReportOutputFormat} via {@link #supports(ReportOutputFormat)}. A
 * community build has no PDF renderer bean, so PDF is structurally impossible
 * there — the {@link ReportOutputFormat} entitlement gate is belt-and-suspenders.</p>
 */
public interface ReportRenderer {

    /** Whether this renderer produces {@code fmt}. */
    boolean supports(ReportOutputFormat fmt);

    /**
     * Renders {@code data} to bytes plus delivery metadata. {@code ctx} carries
     * the resolved report label and run timestamp.
     */
    RenderedReport render(ReportData data, RenderContext ctx);
}
