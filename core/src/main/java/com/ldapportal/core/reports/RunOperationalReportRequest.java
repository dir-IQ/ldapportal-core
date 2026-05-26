// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request body for on-demand operational-report execution. Mirrors
 * the shape of {@code ee.governance.dto.RunReportRequest} but binds
 * the report type to {@link OperationalReportType} so a compliance
 * type can't be smuggled into the core endpoint.
 */
public record RunOperationalReportRequest(
        @NotNull OperationalReportType reportType,
        Map<String, Object> reportParams) {}
