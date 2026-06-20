// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.reports;

import java.util.List;

/**
 * The edition-filtered schedule-form catalogue: the report types, output
 * formats, and delivery methods the current edition may schedule. Lets the UI
 * populate its dropdowns (8 operational types in community, all types on
 * commercial; CSV vs CSV+PDF; EMAIL vs EMAIL+S3) without hard-coding the
 * edition split.
 *
 * @param types      exposed report-type descriptors (id + label)
 * @param formats    exposed {@code ReportOutputFormat} names
 * @param deliveries exposed {@code ReportDeliveryMethod} names
 */
public record ReportCatalogueResponse(List<TypeOption> types, List<String> formats, List<String> deliveries) {

    public record TypeOption(String id, String label) {
    }
}
