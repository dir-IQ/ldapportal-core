// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.core.reports.OperationalReportProvider;
import com.ldapportal.core.reports.ReportData;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.FullLDAPInterface;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
 * <p>Implementation does one secUser search, then one existence check per
 * secUser ({@code getEntry} with no attributes). That's N round-trips for N
 * secUsers — acceptable for an on-demand, size-capped report; a batched
 * demographic existence scan would be the optimisation if it ever matters.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsvaOrphanedAccountsReportProvider implements OperationalReportProvider {

    /** Report id the client sends as the report type. Must not collide with
     *  any {@code OperationalReportType} enum name. */
    public static final String REPORT_ID = "ORPHANED_IVIA_ACCOUNTS";

    /** Cap to protect against an unbounded management-DIT scan. */
    private static final int MAX_RESULTS = 10_000;

    private static final String[] SEC_ATTRS = {
            "secLogin", "secAuthority", "secDN", "secAcctValid", "secValidUntil"
    };

    private final VendorIntegrationIsvaConfigRepository configRepo;
    private final LdapConnectionFactory connectionFactory;

    @Override
    public String reportId() {
        return REPORT_ID;
    }

    @Override
    public boolean appliesTo(DirectoryConnection dir) {
        return activeLinkedConfigOrNull(dir) != null;
    }

    @Override
    public ReportData run(DirectoryConnection dir, Map<String, Object> params, String scopeBaseDn) {
        VendorIntegrationIsvaConfig cfg = activeLinkedConfigOrNull(dir);
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

        connectionFactory.withConnection(dir, conn -> {
            SearchRequest req = new SearchRequest(
                    cfg.getManagementDitBaseDn(),
                    SearchScope.SUB,
                    Filter.createEqualityFilter("objectClass", "secUser"),
                    SEC_ATTRS);
            req.setSizeLimit(MAX_RESULTS);

            SearchResult result;
            try {
                result = conn.search(req);
            } catch (LDAPException e) {
                throw new LdapOperationException(
                        "Orphaned-IVIA scan failed: secUser search under "
                                + cfg.getManagementDitBaseDn() + " errored", e);
            }

            for (SearchResultEntry secUser : result.getSearchEntries()) {
                String secDn = secUser.getAttributeValue("secDN");
                String reason = orphanReason(conn, secDn);
                if (reason == null) {
                    continue; // demographic exists — not an orphan
                }
                // Admin-view scope: when set, only surface orphans whose target
                // demographic DN falls within the scope. "No secDN" orphans
                // can't be scoped, so they're always included.
                if (scopeBaseDn != null && secDn != null
                        && !secDn.toLowerCase(Locale.ROOT).endsWith(scopeBaseDn.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("secUser DN", secUser.getDN());
                row.put("secLogin", nullToEmpty(secUser.getAttributeValue("secLogin")));
                row.put("Demographic DN (secDN)", nullToEmpty(secDn));
                row.put("Account Valid", nullToEmpty(secUser.getAttributeValue("secAcctValid")));
                row.put("Valid Until", nullToEmpty(secUser.getAttributeValue("secValidUntil")));
                row.put("Reason", reason);
                rows.add(row);
            }
            return null;
        });

        log.info("Orphaned IVIA accounts report on directory {}: {} orphan(s) found",
                dir.getDisplayName(), rows.size());
        return new ReportData(columns, rows);
    }

    /**
     * Returns a human-readable orphan reason, or {@code null} when the secUser's
     * {@code secDN} resolves to an existing demographic entry (i.e. not an
     * orphan). {@code getEntry} with the "1.1" no-attributes OID is an
     * existence check; it returns {@code null} for a missing entry.
     */
    private static String orphanReason(FullLDAPInterface conn, String secDn) {
        if (secDn == null || secDn.isBlank()) {
            return "secDN attribute missing";
        }
        try {
            return conn.getEntry(secDn, "1.1") == null ? "Demographic entry not found" : null;
        } catch (LDAPException e) {
            throw new LdapOperationException(
                    "Orphaned-IVIA scan failed: demographic existence check for "
                            + secDn + " errored", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * The active linked-mode IVIA config for this directory, or null when the
     * report doesn't apply (no config, disabled, inline mode, or no management
     * DIT base). Mirrors {@code IsvaUserReadEnricher.activeLinkedConfigOrNull}.
     */
    private VendorIntegrationIsvaConfig activeLinkedConfigOrNull(DirectoryConnection dir) {
        if (dir == null || dir.getId() == null) {
            return null;
        }
        Optional<VendorIntegrationIsvaConfig> maybe = configRepo.findById(dir.getId());
        if (maybe.isEmpty() || !maybe.get().isEnabled()) {
            return null;
        }
        VendorIntegrationIsvaConfig cfg = maybe.get();
        if (cfg.getTopologyMode() != IsvaTopologyMode.LINKED) {
            return null;
        }
        if (cfg.getManagementDitBaseDn() == null || cfg.getManagementDitBaseDn().isBlank()) {
            return null;
        }
        return cfg;
    }
}
