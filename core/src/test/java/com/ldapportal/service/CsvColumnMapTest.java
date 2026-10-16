// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.CsvColumnMappingDto;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvColumnMapTest {

    private static final List<CsvColumnMappingDto> MAPPINGS = List.of(
            new CsvColumnMappingDto("username", "uid", false),
            new CsvColumnMappingDto("email", "mail", false),
            new CsvColumnMappingDto("CostCenter", null, true));

    @Test
    void mappedAndIgnoredColumnsResolveTheSameInBothModes() {
        for (boolean passthrough : new boolean[] {true, false}) {
            CsvColumnMap map = CsvColumnMap.of(MAPPINGS, passthrough);
            assertThat(map.attributeFor("username")).isEqualTo("uid");
            assertThat(map.attributeFor("CostCenter")).isNull();
        }
    }

    @Test
    void unmappedColumnPassesThroughOnlyWithoutATemplate() {
        assertThat(CsvColumnMap.of(MAPPINGS, true).attributeFor("telephoneNumber")).isEqualTo("telephoneNumber");
        assertThat(CsvColumnMap.of(MAPPINGS, false).attributeFor("telephoneNumber")).isNull();
    }

    @Test
    void headerLookupFallsBackToCaseInsensitive() {
        CsvColumnMap map = CsvColumnMap.of(MAPPINGS, false);
        assertThat(map.attributeFor("Email")).isEqualTo("mail");
        assertThat(map.attributeFor("COSTCENTER")).isNull();
        assertThat(map.isKnown("COSTCENTER")).isTrue();
    }

    @Test
    void entryWithoutAttributeMapsToItsOwnName() {
        CsvColumnMap map = CsvColumnMap.of(List.of(new CsvColumnMappingDto("cn", null, false)), false);
        assertThat(map.attributeFor("cn")).isEqualTo("cn");
    }

    @Test
    void reportsUnmappedColumnsInFileOrderOnlyWhenDropping() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("username", "a");
        row.put("Dept", "7");
        row.put("email", "a@x");
        row.put("Phone", "1");
        assertThat(CsvColumnMap.of(MAPPINGS, false).unmappedColumns(List.of(row)))
                .containsExactly("Dept", "Phone");
        assertThat(CsvColumnMap.of(MAPPINGS, true).unmappedColumns(List.of(row))).isEmpty();
    }

    @Test
    void emptyMappingsUnderATemplateDropEverything() {
        CsvColumnMap map = CsvColumnMap.of(List.of(), false);
        assertThat(map.attributeFor("uid")).isNull();
    }
}
