// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * columns by the LDAP attribute name and show that name as is, in the UI
 * and the CSV alike; only derived columns (e.g. "Missing Attributes") carry
 * a label. A column with no label is shown by its key.</p>
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

    /**
     * This report cut down to {@code selected} columns, in that order — what a
     * CSV export of an on-screen table with some columns hidden or reordered
     * asks for. Names match case-insensitively (LDAP attribute names are), and a
     * kept column takes the caller's spelling, so a CSV asked for
     * {@code givenName} is headed {@code givenName} even though the report keys
     * it {@code givenname}. Unknown names are skipped; {@code dn}, when the
     * report has it, always stays first. A null or empty selection, or one
     * naming no column of this report but {@code dn}, returns the report
     * unchanged.
     */
    public ReportData project(List<String> selected) {
        if (selected == null || selected.isEmpty()) return this;
        Map<String, String> byLower = new LinkedHashMap<>();
        for (String c : columns) byLower.putIfAbsent(c.toLowerCase(), c);
        // source column key -> output column name, in output order
        Map<String, String> picked = new LinkedHashMap<>();
        String dn = byLower.get("dn");
        if (dn != null) picked.put(dn, dn);
        boolean askedForDn = false;
        for (String want : selected) {
            if (want == null || want.isBlank()) continue;
            String name = want.trim();
            if (name.equalsIgnoreCase("dn")) askedForDn = true;
            String col = byLower.get(name.toLowerCase());
            if (col != null && !picked.containsKey(col)) picked.put(col, name);
        }
        if (picked.isEmpty() || (dn != null && picked.size() == 1 && !askedForDn)) {
            return this;
        }
        List<Map<String, String>> projected = rows.stream().map(row -> {
            Map<String, String> out = new LinkedHashMap<>();
            picked.forEach((src, name) -> out.put(name, row.getOrDefault(src, "")));
            return out;
        }).toList();
        Map<String, String> keptLabels = new LinkedHashMap<>();
        picked.forEach((src, name) -> {
            String l = labels.get(src);
            if (l != null) keptLabels.put(name, l);
        });
        return new ReportData(new ArrayList<>(picked.values()), projected, keptLabels);
    }

    /** Convert to list-of-lists for PDF rendering and similar grid output. */
    public List<List<String>> toRowLists() {
        return rows.stream()
                .map(row -> columns.stream().map(c -> row.getOrDefault(c, "")).toList())
                .toList();
    }
}
