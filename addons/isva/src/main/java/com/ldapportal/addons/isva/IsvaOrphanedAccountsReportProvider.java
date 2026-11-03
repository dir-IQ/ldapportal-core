// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.core.reports.OperationalReportProvider;
import com.ldapportal.core.reports.ReportData;
import com.ldapportal.entity.DirectoryConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Operational report: <b>orphaned IVIA accounts</b> — secUser entries in the
 * management DIT whose {@code secDN} points at a demographic entry that no
 * longer exists (or is missing entirely). This is the reverse of the read-time
 * {@code isva.orphaned} flag, which marks <em>demographic</em> entries lacking a
 * paired secUser.
 *
 * <p>Plugs into core's operational-report framework via
 * {@link OperationalReportProvider}; core never imports this class. Linked
 * topology only — inline mode has no separate secUser to orphan.</p>
 *
 * <p>The scan itself is {@link IsvaOrphanedSecUsers}, shared with the
 * integrity checker's {@code secDN} check.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsvaOrphanedAccountsReportProvider implements OperationalReportProvider {

    /** Report id the client sends as the report type. Must not collide with
     *  any {@code OperationalReportType} enum name. */
    public static final String REPORT_ID = "ORPHANED_IVIA_ACCOUNTS";

    private final IsvaOrphanedSecUsers orphanedSecUsers;

    @Override
    public String reportId() {
        return REPORT_ID;
    }

    @Override
    public boolean appliesTo(DirectoryConnection dir) {
        return orphanedSecUsers.activeLinkedConfigOrNull(dir) != null;
    }

    @Override
    public ReportData run(DirectoryConnection dir, Map<String, Object> params, String scopeBaseDn) {
        VendorIntegrationIsvaConfig cfg = orphanedSecUsers.activeLinkedConfigOrNull(dir);
        if (cfg == null) {
            // appliesTo() gates this in the normal flow; defensive for direct calls.
            throw new IllegalArgumentException(
                    "Orphaned IVIA accounts report requires an active linked-mode IVIA "
                            + "configuration on directory " + dir.getDisplayName() + ".");
        }

        List<String> columns = List.of(
                "secUser DN", "secLogin", "Demographic DN (secDN)",
                "Account Valid", "Valid Until", "Reason");
        List<Map<String, String>> rows = new ArrayList<>();

        for (IsvaOrphanedSecUsers.Orphan o : orphanedSecUsers.find(dir, cfg)) {
            String secDn = o.secDn();
            // Admin-view scope: when set, only surface orphans whose target
            // demographic DN falls within the scope. "No secDN" orphans
            // can't be scoped, so they're always included.
            if (scopeBaseDn != null && secDn != null
                    && !secDn.toLowerCase(Locale.ROOT).endsWith(scopeBaseDn.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("secUser DN", o.secUserDn());
            row.put("secLogin", nullToEmpty(o.secLogin()));
            row.put("Demographic DN (secDN)", nullToEmpty(secDn));
            row.put("Account Valid", nullToEmpty(o.accountValid()));
            row.put("Valid Until", nullToEmpty(o.validUntil()));
            row.put("Reason", o.reason());
            rows.add(row);
        }

        log.info("Orphaned IVIA accounts report on directory {}: {} orphan(s) found",
                dir.getDisplayName(), rows.size());
        return new ReportData(columns, rows);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
