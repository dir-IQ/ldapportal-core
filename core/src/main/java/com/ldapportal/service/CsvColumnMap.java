// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.CsvColumnMappingDto;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves CSV column headers to LDAP attribute names for bulk user and group
 * imports.
 *
 * <p>Each column is either <em>mapped</em> (to an attribute), <em>ignored</em>
 * (listed with {@code ignored = true}), or <em>unmapped</em> (not listed at
 * all). What happens to an unmapped column depends on {@code passthroughUnmapped}:
 * <ul>
 *   <li>{@code true} — the header is used as the attribute name. This is the
 *       template-less mode, so an exported CSV can be edited and re-imported
 *       without any mapping.</li>
 *   <li>{@code false} — the column is dropped. Used whenever a template is in
 *       play: the template is the contract for the file, so a column it doesn't
 *       mention (e.g. one added to an HR export later) is never written to the
 *       directory by accident.</li>
 * </ul>
 *
 * <p>Header lookup is exact first, then case-insensitive, so a template entry
 * for {@code email} still matches an {@code Email} header.</p>
 */
final class CsvColumnMap {

    /** csvColumn → ldapAttribute; a {@code null} value means "ignored". */
    private final Map<String, String> exact;
    private final Map<String, String> byLowerName;
    private final Set<String> knownLower;
    private final boolean passthroughUnmapped;

    private CsvColumnMap(Map<String, String> exact, boolean passthroughUnmapped) {
        this.exact = exact;
        this.passthroughUnmapped = passthroughUnmapped;
        this.byLowerName = new HashMap<>();
        this.knownLower = new java.util.HashSet<>();
        for (Map.Entry<String, String> e : exact.entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            knownLower.add(lower);
            byLowerName.putIfAbsent(lower, e.getValue());
        }
    }

    static CsvColumnMap of(List<CsvColumnMappingDto> mappings, boolean passthroughUnmapped) {
        Map<String, String> result = new LinkedHashMap<>();
        if (mappings != null) {
            for (CsvColumnMappingDto m : mappings) {
                if (m.ignored()) {
                    result.put(m.csvColumn(), null);
                } else {
                    result.put(m.csvColumn(),
                            m.ldapAttribute() != null ? m.ldapAttribute() : m.csvColumn());
                }
            }
        }
        return new CsvColumnMap(result, passthroughUnmapped);
    }

    /** Whether the mappings mention this column at all (mapped or ignored). */
    boolean isKnown(String csvColumn) {
        return exact.containsKey(csvColumn) || knownLower.contains(csvColumn.toLowerCase(Locale.ROOT));
    }

    /**
     * The LDAP attribute this column's values go to, or {@code null} when the
     * column is skipped (ignored, or unmapped without passthrough).
     */
    String attributeFor(String csvColumn) {
        if (exact.containsKey(csvColumn)) return exact.get(csvColumn);
        String lower = csvColumn.toLowerCase(Locale.ROOT);
        if (knownLower.contains(lower)) return byLowerName.get(lower);
        return passthroughUnmapped ? csvColumn : null;
    }

    /**
     * Columns that will be dropped because the mappings don't mention them
     * (empty in passthrough mode), in first-seen order.
     */
    List<String> unmappedColumns(Collection<? extends Map<String, String>> rows) {
        if (passthroughUnmapped) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            for (String col : row.keySet()) {
                if (!isKnown(col)) out.add(col);
            }
        }
        return List.copyOf(out);
    }
}
