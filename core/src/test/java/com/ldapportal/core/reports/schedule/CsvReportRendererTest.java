// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.core.reports.ReportData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvReportRendererTest {

    private final CsvReportRenderer renderer = new CsvReportRenderer();

    @Test
    void supports_csv_only() {
        assertThat(renderer.supports(ReportOutputFormat.CSV)).isTrue();
        assertThat(renderer.supports(ReportOutputFormat.PDF)).isFalse();
    }

    @Test
    void render_writes_csv_with_slugged_filename() {
        ReportData data = new ReportData(
                List.of("DN", "Name"),
                List.of(Map.of("DN", "cn=a", "Name", "Alice")));

        RenderedReport out = renderer.render(data, new RenderContext("Disabled Accounts", Instant.EPOCH));

        assertThat(out.contentType()).isEqualTo("text/csv");
        assertThat(out.filename()).isEqualTo("disabled-accounts.csv");
        String csv = new String(out.bytes(), StandardCharsets.UTF_8);
        assertThat(csv).contains("DN").contains("Name").contains("cn=a").contains("Alice");
    }

    @Test
    void filename_falls_back_when_label_has_no_alphanumerics() {
        RenderedReport out = renderer.render(
                new ReportData(List.of("c"), List.of()), new RenderContext("***", Instant.EPOCH));
        assertThat(out.filename()).isEqualTo("report.csv");
    }
}
