// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import java.util.List;
import java.util.Map;

/**
 * Structured report result: ordered column keys plus a row per
 * record (column key → cell value). Shared by core operational
 * reports and ee compliance reports so the rendering pipeline (CSV
 * writer, JSON serialiser, PDF table builder) sees one shape.
 *
 * <p>{@code labels} optionally maps a column key to a human-readable
 * heading for on-screen display. Reports over directory entries key their
 * columns by the LDAP attribute name (so a CSV export carries the real
 * names) and put the friendly names here; a column with no label is shown
 * by its key.</p>
 */
public record ReportData(List<String> columns, List<Map<String, String>> rows, Map<String, String> labels) {

    public ReportData {
        labels = labels == null ? Map.of() : labels;
    }

    /** A report whose column keys are also its headings. */
    public ReportData(List<String> columns, List<Map<String, String>> rows) {
        this(columns, rows, Map.of());
    }

    /** The heading for {@code column}: its label, else the key itself. */
    public String label(String column) {
        return labels.getOrDefault(column, column);
    }

    /** Convert to list-of-lists for PDF rendering and similar grid output. */
    public List<List<String>> toRowLists() {
        return rows.stream()
                .map(row -> columns.stream().map(c -> row.getOrDefault(c, "")).toList())
                .toList();
    }
}
