// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFileNamesTest {

    @Test
    void label_titleCasesTheIdsWords() {
        assertThat(ReportFileNames.label("USERS_IN_GROUP")).isEqualTo("Users In Group");
        assertThat(ReportFileNames.label("ivia.orphaned_accounts")).isEqualTo("Ivia Orphaned Accounts");
    }

    @Test
    void forReportId_matchesForLabelOfTheSameReportsLabel() {
        // The on-demand download names by id; a scheduled file names by the
        // descriptor's label, which for operational reports is label(id).
        assertThat(ReportFileNames.forReportId("DISABLED_ACCOUNTS", "csv"))
                .isEqualTo(ReportFileNames.forLabel("Disabled Accounts", "csv"))
                .isEqualTo("disabled-accounts.csv");
        assertThat(ReportFileNames.forReportId("ORPHANED_IVIA_ACCOUNTS", "csv"))
                .isEqualTo("orphaned-ivia-accounts.csv");
    }

    @Test
    void forLabel_fallsBackToReport_whenNothingIsLeft() {
        assertThat(ReportFileNames.forLabel("  --  ", "csv")).isEqualTo("report.csv");
    }
}
