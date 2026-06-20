// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import java.time.Instant;

/**
 * Render-time metadata handed to a {@link ReportRenderer} alongside the
 * {@code ReportData}.
 *
 * <p>The {@link #reportLabel()} is the resolved label from the job's
 * {@link ScheduledReportType} descriptor — the single source for both the PDF
 * document title and the generated email subject, so the two cannot drift. The
 * {@link #runAt()} timestamp lets renderers stamp a generation date.</p>
 *
 * @param reportLabel human-readable report name (from the type descriptor)
 * @param runAt       the run timestamp
 */
public record RenderContext(String reportLabel, Instant runAt) {

    public RenderContext {
        if (reportLabel == null || reportLabel.isBlank()) {
            throw new IllegalArgumentException("RenderContext reportLabel must not be blank");
        }
        if (runAt == null) {
            throw new IllegalArgumentException("RenderContext runAt must not be null");
        }
    }
}
