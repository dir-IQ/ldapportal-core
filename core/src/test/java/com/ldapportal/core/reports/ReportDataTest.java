// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportDataTest {

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private final ReportData data = new ReportData(
            List.of("dn", "cn", "mail", "uid", "missingAttributes"),
            List.of(row("dn", "uid=a", "cn", "A", "mail", "a@x", "uid", "a", "missingAttributes", "sn")),
            Map.of("missingAttributes", "Missing Attributes"));

    @Test
    void project_keepsTheSelectedColumnsInTheirOrder_withDnFirst() {
        ReportData p = data.project(List.of("uid", "cn"));

        assertThat(p.columns()).containsExactly("dn", "uid", "cn");
        assertThat(p.rows()).singleElement()
                .isEqualTo(row("dn", "uid=a", "uid", "a", "cn", "A"));
        assertThat(p.labels()).isEmpty();
    }

    @Test
    void project_takesTheCallersSpelling_soTheCsvHeaderMatchesTheTable() {
        ReportData p = data.project(List.of("CN", " Mail "));

        assertThat(p.columns()).containsExactly("dn", "CN", "Mail");
        assertThat(p.rows()).singleElement().isEqualTo(row("dn", "uid=a", "CN", "A", "Mail", "a@x"));
    }

    @Test
    void project_skipsUnknownNames_andKeepsLabelsOfKeptColumns() {
        ReportData p = data.project(List.of("missingAttributes", "nosuchattr", "mail"));

        assertThat(p.columns()).containsExactly("dn", "missingAttributes", "mail");
        assertThat(p.labels()).containsExactly(Map.entry("missingAttributes", "Missing Attributes"));
    }

    @Test
    void project_withNoUsableSelection_returnsTheWholeReport() {
        assertThat(data.project(null)).isSameAs(data);
        assertThat(data.project(List.of())).isSameAs(data);
        assertThat(data.project(List.of("nosuchattr"))).isSameAs(data);
    }

    @Test
    void project_dnAlone_isHonouredWhenAskedFor() {
        assertThat(data.project(List.of("dn")).columns()).containsExactly("dn");
    }

    @Test
    void project_onAReportWithoutDn_usesOnlyTheSelection() {
        ReportData audit = new ReportData(List.of("When", "Actor", "Action"),
                List.of(row("When", "t", "Actor", "bob", "Action", "x")));

        ReportData p = audit.project(List.of("Action", "When"));

        assertThat(p.columns()).containsExactly("Action", "When");
        assertThat(p.rows()).singleElement().isEqualTo(row("Action", "x", "When", "t"));
    }
}
