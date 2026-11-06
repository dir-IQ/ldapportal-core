// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.reports.OperationalReportProvider;
import com.ldapportal.core.reports.OperationalReportService;
import com.ldapportal.core.reports.OperationalReportType;
import com.ldapportal.core.reports.ReportData;
import com.ldapportal.core.reports.ReportFileNames;
import com.ldapportal.entity.DirectoryConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core {@link ScheduledReportContentProvider} for the operational report types.
 *
 * <p>A thin adapter over {@link OperationalReportService}: it contributes the
 * built-in {@link OperationalReportType}s — plus any addon-contributed
 * {@link OperationalReportProvider} ids (e.g. the IVIA orphaned-account scan) —
 * as schedulable descriptors and delegates execution straight back to that
 * service, so scheduled operational reports ride the exact same resolution path
 * as the on-demand {@code /reports/run} endpoints and we add no parallel
 * operational logic.</p>
 *
 * <p>All descriptors are ungated ({@code requiredEntitlement == null}): operational
 * reports are part of the core, edition-agnostic surface. Commercial compliance
 * content is contributed separately by {@code ee/governance}'s
 * {@code ComplianceScheduledReportContentProvider}.</p>
 */
@Component
@RequiredArgsConstructor
public class OperationalScheduledReportContentProvider implements ScheduledReportContentProvider {

    private final OperationalReportService operationalReportService;
    /** Addon-contributed operational reports; empty in community. */
    private final List<OperationalReportProvider> operationalProviders;

    @Override
    public List<ScheduledReportType> supportedTypes() {
        List<ScheduledReportType> types = new ArrayList<>();
        for (OperationalReportType t : OperationalReportType.values()) {
            types.add(ScheduledReportType.core(t.name(), friendlyLabel(t.name())));
        }
        for (OperationalReportProvider p : operationalProviders) {
            types.add(ScheduledReportType.core(p.reportId(), friendlyLabel(p.reportId())));
        }
        return List.copyOf(types);
    }

    @Override
    public boolean appliesTo(DirectoryConnection dir, String reportType) {
        if (builtin(reportType) != null) {
            return true;
        }
        return operationalProviders.stream()
                .anyMatch(p -> p.reportId().equals(reportType) && p.appliesTo(dir));
    }

    @Override
    public ReportData run(DirectoryConnection dir, String reportType,
                          Map<String, Object> params, String scopeBaseDn) {
        return run(dir, reportType, params, scopeBaseDn, null);
    }

    /**
     * With a {@code runAs} principal the report goes through the service's
     * interactive entry point, so a job an admin created is clamped to their
     * authorized OUs (search bases, groups column, audit-row DN filter) exactly
     * as when they run it on demand. Without one it is the system entry point:
     * unbounded apart from the stored {@code scopeBaseDn}.
     */
    @Override
    public ReportData run(DirectoryConnection dir, String reportType,
                          Map<String, Object> params, String scopeBaseDn, AuthPrincipal runAs) {
        Map<String, Object> scoped = withScope(params, scopeBaseDn);
        return runAs == null
                ? operationalReportService.run(dir, reportType, scoped, dir.getId())
                : operationalReportService.run(dir, reportType, scoped, dir.getId(), runAs);
    }

    /**
     * {@link OperationalReportService} reads the admin-view scope override from
     * {@code params["scopeBaseDn"]}; fold the SPI's explicit {@code scopeBaseDn}
     * back into the param map (without mutating the caller's) so the delegate
     * sees it.
     */
    private static Map<String, Object> withScope(Map<String, Object> params, String scopeBaseDn) {
        if (scopeBaseDn == null || scopeBaseDn.isBlank()) {
            return params != null ? params : Map.of();
        }
        Map<String, Object> merged = new HashMap<>(params != null ? params : Map.of());
        merged.put("scopeBaseDn", scopeBaseDn);
        return merged;
    }

    private static OperationalReportType builtin(String reportType) {
        if (reportType == null) {
            return null;
        }
        try {
            return OperationalReportType.valueOf(reportType);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** "USERS_IN_GROUP" / "ivia.orphaned_accounts" → "Users In Group" / "Ivia Orphaned Accounts". */
    static String friendlyLabel(String id) {
        return ReportFileNames.label(id);
    }
}
