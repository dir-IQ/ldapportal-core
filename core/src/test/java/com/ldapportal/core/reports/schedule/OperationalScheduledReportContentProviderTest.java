// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.core.reports.OperationalReportProvider;
import com.ldapportal.core.reports.OperationalReportService;
import com.ldapportal.core.reports.OperationalReportType;
import com.ldapportal.core.reports.ReportData;
import com.ldapportal.entity.DirectoryConnection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationalScheduledReportContentProviderTest {

    private final OperationalReportService service = mock(OperationalReportService.class);

    private OperationalScheduledReportContentProvider provider(OperationalReportProvider... addons) {
        return new OperationalScheduledReportContentProvider(service, List.of(addons));
    }

    @Test
    void supportedTypes_lists_all_builtins_ungated() {
        List<ScheduledReportType> types = provider().supportedTypes();

        assertThat(types).extracting(ScheduledReportType::id)
                .contains(OperationalReportType.DISABLED_ACCOUNTS.name(),
                          OperationalReportType.AUDIT_ENTRIES.name())
                .hasSize(OperationalReportType.values().length);
        assertThat(types).allSatisfy(t -> assertThat(t.requiredEntitlement()).isNull());
        assertThat(types).extracting(ScheduledReportType::label)
                .contains("Disabled Accounts", "Audit Entries");
    }

    @Test
    void supportedTypes_includes_addon_provider_ids() {
        OperationalReportProvider addon = mock(OperationalReportProvider.class);
        when(addon.reportId()).thenReturn("ivia.orphaned_accounts");

        List<ScheduledReportType> types = provider(addon).supportedTypes();

        assertThat(types).extracting(ScheduledReportType::id).contains("ivia.orphaned_accounts");
        assertThat(types).filteredOn(t -> t.id().equals("ivia.orphaned_accounts"))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.label()).isEqualTo("Ivia Orphaned Accounts");
                    assertThat(t.requiredEntitlement()).isNull();
                });
    }

    @Test
    void appliesTo_true_for_builtin_false_for_unknown() {
        DirectoryConnection dir = mock(DirectoryConnection.class);
        OperationalScheduledReportContentProvider p = provider();
        assertThat(p.appliesTo(dir, "DISABLED_ACCOUNTS")).isTrue();
        assertThat(p.appliesTo(dir, "NOT_A_TYPE")).isFalse();
    }

    @Test
    void appliesTo_addon_respects_provider_applicability() {
        DirectoryConnection dir = mock(DirectoryConnection.class);
        OperationalReportProvider addon = mock(OperationalReportProvider.class);
        when(addon.reportId()).thenReturn("ivia.orphaned_accounts");
        when(addon.appliesTo(dir)).thenReturn(false);

        assertThat(provider(addon).appliesTo(dir, "ivia.orphaned_accounts")).isFalse();

        when(addon.appliesTo(dir)).thenReturn(true);
        assertThat(provider(addon).appliesTo(dir, "ivia.orphaned_accounts")).isTrue();
    }

    @Test
    void run_delegates_to_service_with_directory_id_and_merged_scope() {
        DirectoryConnection dir = mock(DirectoryConnection.class);
        UUID dirId = UUID.randomUUID();
        when(dir.getId()).thenReturn(dirId);
        ReportData expected = new ReportData(List.of("c"), List.of());
        when(service.run(eq(dir), eq("DISABLED_ACCOUNTS"), any(), eq(dirId))).thenReturn(expected);

        ReportData out = provider().run(dir, "DISABLED_ACCOUNTS",
                new java.util.HashMap<>(Map.of("k", "v")), "ou=scope");

        assertThat(out).isSameAs(expected);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(service).run(eq(dir), eq("DISABLED_ACCOUNTS"), params.capture(), eq(dirId));
        assertThat(params.getValue()).containsEntry("scopeBaseDn", "ou=scope").containsEntry("k", "v");
    }

    @Test
    void run_without_scope_passes_params_through() {
        DirectoryConnection dir = mock(DirectoryConnection.class);
        UUID dirId = UUID.randomUUID();
        when(dir.getId()).thenReturn(dirId);
        when(service.run(any(), any(), any(), any())).thenReturn(new ReportData(List.of(), List.of()));

        provider().run(dir, "AUDIT_ENTRIES", Map.of("lookbackHours", 6), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(service).run(eq(dir), eq("AUDIT_ENTRIES"), params.capture(), eq(dirId));
        assertThat(params.getValue()).containsEntry("lookbackHours", 6).doesNotContainKey("scopeBaseDn");
    }
}
