// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Request body for on-demand operational-report execution. {@code reportType}
 * is a string so it can name either a built-in {@link OperationalReportType} or
 * an addon-contributed {@link OperationalReportProvider#reportId()}. The service
 * rejects any value matching neither (400). Compliance report types still can't
 * run here — they live behind {@code ee/governance}'s own endpoints and aren't
 * registered as providers in core.
 */
public record RunOperationalReportRequest(
        @NotBlank String reportType,
        Map<String, Object> reportParams) {}
