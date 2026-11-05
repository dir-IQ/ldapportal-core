// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * Request body for on-demand operational-report execution. {@code reportType}
 * is a string so it can name either a built-in {@link OperationalReportType} or
 * an addon-contributed {@link OperationalReportProvider#reportId()}. The service
 * rejects any value matching neither (400). Compliance report types still can't
 * run here — they live behind {@code ee/governance}'s own endpoints and aren't
 * registered as providers in core.
 *
 * <p>{@code columns} optionally names the columns a CSV export carries, in
 * order — the on-screen table's visible columns — so the file matches what the
 * operator sees (see {@link ReportData#project}). Omitted, the export carries
 * every column. The inline {@code run-data} endpoint ignores it: the table needs
 * every column to offer them in its Columns menu.</p>
 */
public record RunOperationalReportRequest(
        @NotBlank String reportType,
        Map<String, Object> reportParams,
        List<String> columns) {

    /** Every column — the shape callers used before {@code columns} existed. */
    public RunOperationalReportRequest(String reportType, Map<String, Object> reportParams) {
        this(reportType, reportParams, null);
    }
}
