// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import java.util.List;
import java.util.Map;

/**
 * Structured report result: ordered column labels plus a row per
 * record (column label → cell value). Shared by core operational
 * reports and ee compliance reports so the rendering pipeline (CSV
 * writer, JSON serialiser, PDF table builder) sees one shape.
 */
public record ReportData(List<String> columns, List<Map<String, String>> rows) {

    /** Convert to list-of-lists for PDF rendering and similar grid output. */
    public List<List<String>> toRowLists() {
        return rows.stream()
                .map(row -> columns.stream().map(c -> row.getOrDefault(c, "")).toList())
                .toList();
    }
}
