// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvUtilsTest {

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parse_defaultsToComma() throws IOException {
        List<Map<String, String>> rows = CsvUtils.parse(in("a,b\n1,2\n"), true);
        assertThat(rows).containsExactly(Map.of("a", "1", "b", "2"));
    }

    @Test
    void parse_semicolon_keepsCommasInsideValues() throws IOException {
        List<Map<String, String>> rows = CsvUtils.parse(
                in("dn;cn\nuid=a,dc=x;Alice\n\"uid=b;c,dc=x\";\"Bob \"\"B\"\"\"\n"), true, ';');

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("dn", "uid=a,dc=x").containsEntry("cn", "Alice");
        // Quoted fields may contain the delimiter; doubled quotes unescape.
        assertThat(rows.get(1)).containsEntry("dn", "uid=b;c,dc=x").containsEntry("cn", "Bob \"B\"");
    }

    @Test
    void parse_tab_withoutHeaderRow() throws IOException {
        List<Map<String, String>> rows = CsvUtils.parse(in("x\ty\tz\n"), false, '\t');
        assertThat(rows).containsExactly(Map.of("Column 1", "x", "Column 2", "y", "Column 3", "z"));
    }

    @Test
    void parse_quotedMultilineValue_withPipe() throws IOException {
        List<Map<String, String>> rows = CsvUtils.parse(
                in("uid|description\njdoe|\"line one\nline|two\"\n"), true, '|');
        assertThat(rows).containsExactly(Map.of("uid", "jdoe", "description", "line one\nline|two"));
    }

    @Test
    void parseRow_hasNoPhantomTrailingField() {
        // Regression: every row used to gain an extra empty last field.
        assertThat(CsvUtils.parseRow("a,b")).containsExactly("a", "b");
        assertThat(CsvUtils.parseRow("\"a\",\"b\"")).containsExactly("a", "b");
        assertThat(CsvUtils.parseRow("a")).containsExactly("a");
    }

    @Test
    void parseRow_keepsEmptyFieldsFromTrailingAndAdjacentDelimiters() {
        assertThat(CsvUtils.parseRow("a,")).containsExactly("a", "");
        assertThat(CsvUtils.parseRow("a,,b")).containsExactly("a", "", "b");
        assertThat(CsvUtils.parseRow(",")).containsExactly("", "");
        assertThat(CsvUtils.parseRow("\"a\",")).containsExactly("a", "");
    }

    @Test
    void parse_withoutHeaderRow_synthesizesOnlyRealColumns() throws IOException {
        List<Map<String, String>> rows = CsvUtils.parse(in("x,y\n"), false);
        assertThat(rows).containsExactly(Map.of("Column 1", "x", "Column 2", "y"));
    }

    @Test
    void toDelimiter_acceptsSingleCharacter_defaultsBlankToComma() {
        assertThat(CsvUtils.toDelimiter(null)).isEqualTo(',');
        assertThat(CsvUtils.toDelimiter("")).isEqualTo(',');
        assertThat(CsvUtils.toDelimiter(";")).isEqualTo(';');
        assertThat(CsvUtils.toDelimiter("\t")).isEqualTo('\t');
        assertThat(CsvUtils.toDelimiter(" ")).isEqualTo(' ');
    }

    @Test
    void toDelimiter_rejectsMultiCharQuoteAndLineBreaks() {
        for (String bad : List.of(";;", "\"", "\n", "\r")) {
            assertThatThrownBy(() -> CsvUtils.toDelimiter(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
