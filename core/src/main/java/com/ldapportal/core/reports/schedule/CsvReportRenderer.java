// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.core.reports.ReportData;
import com.ldapportal.core.reports.ReportFileNames;
import com.ldapportal.util.CsvUtils;
import org.springframework.stereotype.Component;

/**
 * Core {@link ReportRenderer} for {@link ReportOutputFormat#CSV}, wrapping
 * {@link CsvUtils}. CSV is the community output format; the PDF renderer ships in
 * {@code ee/governance} (OpenPDF stays a commercial-only dependency), so a
 * community build has no PDF renderer bean and PDF is structurally impossible.
 */
@Component
public class CsvReportRenderer implements ReportRenderer {

    private static final String CONTENT_TYPE = "text/csv";

    @Override
    public boolean supports(ReportOutputFormat fmt) {
        return fmt == ReportOutputFormat.CSV;
    }

    @Override
    public RenderedReport render(ReportData data, RenderContext ctx) {
        byte[] bytes = CsvUtils.write(data.columns(), data.rows());
        return new RenderedReport(bytes, CONTENT_TYPE, ReportFileNames.forLabel(ctx.reportLabel(), "csv"));
    }
}
