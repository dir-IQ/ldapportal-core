// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.core.entitlement.Entitlement;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shape + edition-gating contract for the scheduled-report SPI value types.
 * The behavioural edition-leak guard auto-discovers {@link ReportOutputFormat}
 * and {@link ReportDeliveryMethod} (both {@code EditionScoped} enums); this test
 * pins the specific gating those guards rely on.
 */
class ScheduledReportSpiContractsTest {

    @Test
    void outputFormat_csv_is_core_pdf_requires_governance() {
        assertThat(ReportOutputFormat.CSV.requiredEntitlement()).isNull();
        assertThat(ReportOutputFormat.PDF.requiredEntitlement()).isEqualTo(Entitlement.GOVERNANCE);
    }

    @Test
    void deliveryMethod_email_is_core_s3_requires_governance() {
        assertThat(ReportDeliveryMethod.EMAIL.requiredEntitlement()).isNull();
        assertThat(ReportDeliveryMethod.S3.requiredEntitlement()).isEqualTo(Entitlement.GOVERNANCE);
    }

    @Test
    void scheduledReportType_core_helper_is_ungated() {
        ScheduledReportType t = ScheduledReportType.core("DISABLED_ACCOUNTS", "Disabled Accounts");
        assertThat(t.id()).isEqualTo("DISABLED_ACCOUNTS");
        assertThat(t.label()).isEqualTo("Disabled Accounts");
        assertThat(t.requiredEntitlement()).isNull();
    }

    @Test
    void scheduledReportType_can_be_governance_gated() {
        ScheduledReportType t =
                new ScheduledReportType("SOD_VIOLATIONS", "SoD Violations", Entitlement.GOVERNANCE);
        assertThat(t.requiredEntitlement()).isEqualTo(Entitlement.GOVERNANCE);
    }

    @Test
    void scheduledReportType_rejects_blank_id_or_label() {
        assertThatThrownBy(() -> new ScheduledReportType("  ", "label", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledReportType("id", "", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renderedReport_validates_required_fields() {
        RenderedReport ok = new RenderedReport(new byte[]{1, 2}, "text/csv", "r.csv");
        assertThat(ok.bytes()).hasSize(2);
        assertThatThrownBy(() -> new RenderedReport(null, "text/csv", "r.csv"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenderedReport(new byte[0], " ", "r.csv"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenderedReport(new byte[0], "text/csv", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renderContext_validates_required_fields() {
        RenderContext ctx = new RenderContext("Disabled Accounts", Instant.EPOCH);
        assertThat(ctx.reportLabel()).isEqualTo("Disabled Accounts");
        assertThatThrownBy(() -> new RenderContext(" ", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenderContext("x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
