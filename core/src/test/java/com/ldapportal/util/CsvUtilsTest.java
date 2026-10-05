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
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("a", "1").containsEntry("b", "2");
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
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Column 1", "x").containsEntry("Column 2", "y")
                .containsEntry("Column 3", "z");
    }

    @Test
    void parse_quotedMultilineValue_withPipe() throws IOException {
        List<Map<String, String>> rows = CsvUtils.parse(
                in("uid|description\njdoe|\"line one\nline|two\"\n"), true, '|');
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("uid", "jdoe")
                .containsEntry("description", "line one\nline|two");
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
