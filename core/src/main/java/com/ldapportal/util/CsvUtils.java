// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 4180 CSV parser and writer — no third-party dependencies required.
 *
 * <h3>Parse</h3>
 * <ul>
 *   <li>First row is treated as a header row; subsequent rows become ordered
 *       maps of {@code header → value}.</li>
 *   <li>Fields may be optionally enclosed in double-quotes.</li>
 *   <li>Embedded double-quotes inside a quoted field are doubled ({@code ""}).</li>
 *   <li>Multi-line quoted values spanning physical newlines are NOT supported;
 *       newlines within a field must be represented by the caller.</li>
 * </ul>
 *
 * <h3>Write</h3>
 * <ul>
 *   <li>All cell values are double-quoted for portability.</li>
 *   <li>Embedded double-quotes are escaped as {@code ""}.</li>
 *   <li>Lines are terminated with {@code \r\n} per RFC 4180.</li>
 * </ul>
 */
public final class CsvUtils {

    private CsvUtils() {}

    // ── Parse ─────────────────────────────────────────────────────────────────

    /**
     * Parses a UTF-8 CSV {@link InputStream} into a list of row maps.
     * The first row is consumed as column headers.
     *
     * <p>Fully RFC 4180-compliant: quoted fields may span multiple physical
     * lines (newlines inside a quoted value are preserved), and embedded
     * double-quotes are represented by two consecutive double-quotes ({@code ""}).</p>
     *
     * @return ordered list of row maps; empty list if the stream has no data rows
     * @throws IOException on I/O errors
     */
    public static List<Map<String, String>> parse(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8));

        List<String[]> rawRows = new ArrayList<>();
        StringBuilder logical = new StringBuilder(); // accumulates a logical (possibly multi-line) row
        boolean inQuote = false;

        String physLine;
        while ((physLine = reader.readLine()) != null) {
            if (logical.length() > 0 || !physLine.isBlank()) {
                if (logical.length() > 0) {
                    // Re-insert the newline that readLine() stripped; the field spans lines.
                    logical.append('\n');
                }
                logical.append(physLine);

                // Scan the new physical line to track whether we are inside a quoted field.
                // Two consecutive quotes ("") are an escape sequence, not a boundary toggle.
                for (int i = 0; i < physLine.length(); i++) {
                    if (physLine.charAt(i) == '"') {
                        if (i + 1 < physLine.length() && physLine.charAt(i + 1) == '"') {
                            i++; // skip the escaped quote
                        } else {
                            inQuote = !inQuote;
                        }
                    }
                }

                if (!inQuote) {
                    // Logical row is complete — parse it and reset the buffer
                    String logicalRow = logical.toString();
                    logical.setLength(0);
                    if (!logicalRow.isBlank()) {
                        rawRows.add(parseRow(logicalRow));
                    }
                }
            }
        }
        // Lenient: handle unterminated quoted field at EOF
        if (logical.length() > 0) {
            rawRows.add(parseRow(logical.toString()));
        }

        if (rawRows.isEmpty()) {
            return Collections.emptyList();
        }

        return assembleRowMaps(rawRows, true);
    }

    /**
     * Parses a UTF-8 CSV stream, optionally treating the first row as headers.
     *
     * @param hasHeaderRow when {@code true} the first row supplies column names;
     *                     when {@code false} synthetic names ({@code Column 1}, {@code Column 2}, …)
     *                     are generated and all rows are treated as data
     */
    public static List<Map<String, String>> parse(InputStream input, boolean hasHeaderRow)
            throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8));

        List<String[]> rawRows = new ArrayList<>();
        StringBuilder logical = new StringBuilder();
        boolean inQuote = false;

        String physLine;
        while ((physLine = reader.readLine()) != null) {
            if (logical.length() > 0 || !physLine.isBlank()) {
                if (logical.length() > 0) {
                    logical.append('\n');
                }
                logical.append(physLine);

                for (int i = 0; i < physLine.length(); i++) {
                    if (physLine.charAt(i) == '"') {
                        if (i + 1 < physLine.length() && physLine.charAt(i + 1) == '"') {
                            i++;
                        } else {
                            inQuote = !inQuote;
                        }
                    }
                }

                if (!inQuote) {
                    String logicalRow = logical.toString();
                    logical.setLength(0);
                    if (!logicalRow.isBlank()) {
                        rawRows.add(parseRow(logicalRow));
                    }
                }
            }
        }
        if (logical.length() > 0) {
            rawRows.add(parseRow(logical.toString()));
        }

        return assembleRowMaps(rawRows, hasHeaderRow);
    }

    private static List<Map<String, String>> assembleRowMaps(List<String[]> rawRows,
                                                              boolean hasHeaderRow) {
        if (rawRows.isEmpty()) {
            return Collections.emptyList();
        }

        String[] headers;
        int dataStart;
        if (hasHeaderRow) {
            headers = rawRows.get(0);
            dataStart = 1;
        } else {
            int maxCols = rawRows.stream().mapToInt(r -> r.length).max().orElse(0);
            headers = new String[maxCols];
            for (int c = 0; c < maxCols; c++) {
                headers[c] = "Column " + (c + 1);
            }
            dataStart = 0;
        }

        List<Map<String, String>> result = new ArrayList<>();
        for (int r = dataStart; r < rawRows.size(); r++) {
            String[] row = rawRows.get(r);
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < headers.length; c++) {
                map.put(headers[c], c < row.length ? row[c] : "");
            }
            result.add(map);
        }
        return result;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Serialises a list of row maps into a UTF-8 CSV {@code byte[]}.
     * The {@code headers} list controls column order and produces the first row.
     *
     * @param headers column names in the desired order
     * @param rows    data rows; missing keys produce empty cells
     */
    public static byte[] write(List<String> headers, List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        for (Map<String, String> row : rows) {
            List<String> values = new ArrayList<>(headers.size());
            for (String h : headers) {
                values.add(row.getOrDefault(h, ""));
            }
            appendRow(sb, values);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes the CSV header row to {@code writer}.
     * Use together with {@link #writeRow} for streaming exports where rows
     * are produced incrementally rather than collected into a list first.
     *
     * @param writer  destination writer (caller manages flush/close)
     * @param headers column names in the desired order
     */
    public static void writeHeader(Writer writer, List<String> headers) throws IOException {
        writer.write(buildRow(headers));
    }

    /**
     * Writes a single data row to {@code writer}.
     *
     * @param writer  destination writer
     * @param headers column names that control which keys are written and in what order
     * @param row     data map; missing keys produce empty cells
     */
    public static void writeRow(Writer writer, List<String> headers,
                                Map<String, String> row) throws IOException {
        List<String> values = new ArrayList<>(headers.size());
        for (String h : headers) {
            values.add(row.getOrDefault(h, ""));
        }
        writer.write(buildRow(values));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Appends a single RFC 4180 CSV row (all fields quoted). */
    private static void appendRow(StringBuilder sb, List<String> values) {
        sb.append(buildRow(values));
    }

    /** Builds a single RFC 4180 CSV row string (all fields quoted, CRLF terminated). */
    private static String buildRow(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            String v = values.get(i) != null ? values.get(i) : "";
            sb.append('"');
            sb.append(v.replace("\"", "\"\""));
            sb.append('"');
        }
        sb.append("\r\n");
        return sb.toString();
    }

    /**
     * Parses a single CSV line into an array of field values.
     * Handles quoted fields with embedded commas and doubled double-quotes.
     */
    static String[] parseRow(String line) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        while (i <= line.length()) {
            if (i == line.length()) {
                // Trailing comma produced an empty last field
                fields.add("");
                break;
            }

            if (line.charAt(i) == '"') {
                // Quoted field
                i++; // skip opening quote
                StringBuilder field = new StringBuilder();
                while (i < line.length()) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            field.append('"'); // escaped double-quote
                            i += 2;
                        } else {
                            i++; // closing quote
                            break;
                        }
                    } else {
                        field.append(c);
                        i++;
                    }
                }
                fields.add(field.toString());
                if (i < line.length() && line.charAt(i) == ',') {
                    i++; // skip delimiter
                }

            } else {
                // Unquoted field — read until next comma or end
                int start = i;
                while (i < line.length() && line.charAt(i) != ',') {
                    i++;
                }
                fields.add(line.substring(start, i));
                if (i < line.length()) {
                    i++; // skip comma
                }
            }
        }
        return fields.toArray(new String[0]);
    }
}
