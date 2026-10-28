// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds <b>orphaned IVIA accounts</b>: secUser entries in the management DIT
 * whose {@code secDN} points at a demographic entry that doesn't exist, or
 * that have no {@code secDN} at all. Shared by the Orphaned IVIA accounts
 * report ({@link IsvaOrphanedAccountsReportProvider}) and the integrity
 * checker's reference check ({@link IsvaIntegrityCheckContributor}).
 *
 * <p>Linked topology only — inline mode has no separate secUser to orphan.
 * One secUser search, then one existence check per secUser ({@code getEntry}
 * with no attributes): N round-trips for N secUsers, capped at
 * {@link #MAX_RESULTS}.</p>
 */
@Component
@RequiredArgsConstructor
public class IsvaOrphanedSecUsers {

    /** Cap to protect against an unbounded management-DIT scan. */
    static final int MAX_RESULTS = 10_000;

    private static final String[] SEC_ATTRS = {
            "secLogin", "secAuthority", "secDN", "secAcctValid", "secValidUntil"
    };

    private final VendorIntegrationIsvaConfigRepository configRepo;
    private final LdapConnectionFactory connectionFactory;

    /**
     * One orphaned secUser.
     *
     * @param secDn  its {@code secDN}, or {@code null} when it has none
     * @param reason why it's an orphan
     */
    public record Orphan(String secUserDn, String secLogin, String secDn, String accountValid, String validUntil,
                         String reason) {
    }

    /**
     * The active linked-mode IVIA config for this directory, or null when the
     * scan doesn't apply (no config, disabled, inline mode, or no user
     * container under the management DIT).
     */
    public VendorIntegrationIsvaConfig activeLinkedConfigOrNull(DirectoryConnection dir) {
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
        if (cfg.effectiveUserContainerDn() == null || cfg.effectiveUserContainerDn().isBlank()) {
            return null;
        }
        return cfg;
    }

    /** The directory's orphaned secUsers, in search order; {@code cfg} from {@link #activeLinkedConfigOrNull}. */
    public List<Orphan> find(DirectoryConnection dir, VendorIntegrationIsvaConfig cfg) {
        List<Orphan> orphans = new ArrayList<>();
        connectionFactory.withConnection(dir, conn -> {
            SearchRequest req = new SearchRequest(
                    cfg.effectiveUserContainerDn(),
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
                                + cfg.effectiveUserContainerDn() + " errored", e);
            }

            for (SearchResultEntry secUser : result.getSearchEntries()) {
                String secDn = secUser.getAttributeValue("secDN");
                String reason = orphanReason(conn, secDn);
                if (reason != null) {
                    orphans.add(new Orphan(secUser.getDN(), secUser.getAttributeValue("secLogin"), secDn,
                            secUser.getAttributeValue("secAcctValid"), secUser.getAttributeValue("secValidUntil"),
                            reason));
                }
            }
            return null;
        });
        return orphans;
    }

    /**
     * A human-readable orphan reason, or {@code null} when the secUser's
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
}
