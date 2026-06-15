// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.core.provisioning.UserReadEnricher;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.model.LdapUser;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the paired secUser entry for each user in a result set
 * and augments the returned {@link LdapUser} with {@code isva.*}-
 * prefixed attributes from the secUser. The UI joins demographic
 * + secUser into a single user view via these prefixed keys.
 *
 * <p>No-op for:
 * <ul>
 *   <li>Directories without an active ISVA config row.</li>
 *   <li>Directories configured for inline topology (the secUser
 *       overlay already lives on the user entry; nothing to join).</li>
 * </ul></p>
 *
 * <p>For directories configured for linked topology, this
 * enricher issues <b>one LDAP search per result page</b>
 * (filter = {@code (&(objectClass=secUser)(|(secDN=dn1)(secDN=dn2)…))})
 * and joins in-memory. Worst-case extra cost on a 100-row page
 * is one search + one round-trip; per-row enrichment would have
 * cost 100. The cap is the page-size configured on the directory
 * connection.</p>
 *
 * <p>Users whose secUser lookup returns nothing are tagged
 * {@code isva.orphaned=true} so the UI can surface a "needs
 * repair" indicator without doing its own lookup.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsvaUserReadEnricher implements UserReadEnricher {

    /** Attributes pulled off the secUser entry and re-prefixed onto
     * the demographic-side LdapUser. Keep this list small — anything
     * the UI doesn't need is wasted bytes. */
    private static final String[] SEC_ATTRS = {
            "secLogin", "secAuthority", "secAcctValid", "secPwdValid",
            "secValidUntil", "secPwdLastChanged", "secDN"
    };

    private final VendorIntegrationIsvaConfigRepository configRepo;
    private final LdapConnectionFactory connectionFactory;

    @Override
    public List<LdapUser> enrichBatch(DirectoryConnection dir, List<LdapUser> users) {
        if (users.isEmpty()) {
            return users;
        }
        VendorIntegrationIsvaConfig cfg = activeLinkedConfigOrNull(dir);
        if (cfg == null) {
            // No-op for non-ISVA directories AND for inline-mode ISVA
            // (where the sec* attrs already live on the user entry).
            return users;
        }

        // One LDAP search per page: filter is an OR over every
        // demographic DN in the batch. Build the DN→secUser map
        // up-front so the per-user merge step is in-memory only.
        Map<String, SearchResultEntry> secUserByDemographicDn =
                fetchSecUsersForBatch(dir, cfg, users);

        List<LdapUser> result = new ArrayList<>(users.size());
        for (LdapUser user : users) {
            SearchResultEntry secUser = lookupCaseInsensitive(
                    secUserByDemographicDn, user.getDn());
            result.add(secUser == null ? markOrphaned(user) : merge(user, secUser));
        }
        return result;
    }

    // ── batch lookup ────────────────────────────────────────────────

    private Map<String, SearchResultEntry> fetchSecUsersForBatch(
            DirectoryConnection dir,
            VendorIntegrationIsvaConfig cfg,
            List<LdapUser> users) {

        // Build the OR'd filter clauses: (&(objectClass=secUser)
        // (|(secDN=dn1)(secDN=dn2)…(secDN=dnN))).
        Filter[] orClauses = users.stream()
                .map(LdapUser::getDn)
                .map(dn -> Filter.createEqualityFilter("secDN", dn))
                .toArray(Filter[]::new);
        Filter filter = Filter.createANDFilter(
                Filter.createEqualityFilter("objectClass", "secUser"),
                Filter.createORFilter(orClauses));

        // Request exactly the attributes we'll surface — keeps the
        // wire payload tight even when a batch is big.
        SearchRequest req = new SearchRequest(
                cfg.getManagementDitBaseDn(),
                SearchScope.SUB,
                filter,
                SEC_ATTRS);
        // Size limit = batch size + headroom for the duplicate-secUser
        // case (which the lookup helper warns about elsewhere).
        req.setSizeLimit(users.size() * 2);

        return connectionFactory.withConnection(dir, conn -> {
            try {
                SearchResult result = conn.search(req);
                Map<String, SearchResultEntry> byDemographicDn = new HashMap<>();
                for (SearchResultEntry entry : result.getSearchEntries()) {
                    String secDn = entry.getAttributeValue("secDN");
                    if (secDn == null) continue;
                    // Case-insensitive normalisation so the lookup
                    // tolerates the LDAP convention of mixed-case DNs.
                    byDemographicDn.put(secDn.toLowerCase(), entry);
                }
                return byDemographicDn;
            } catch (LDAPException e) {
                throw new LdapOperationException(
                        "Joined-read failed: secUser batch lookup against "
                                + cfg.getManagementDitBaseDn() + " errored", e);
            }
        });
    }

    // ── per-user merge ──────────────────────────────────────────────

    private LdapUser merge(LdapUser demographic, SearchResultEntry secUser) {
        Map<String, List<String>> attrs = new HashMap<>(demographic.getAttributes());
        for (String attrName : SEC_ATTRS) {
            String value = secUser.getAttributeValue(attrName);
            if (value != null) {
                attrs.put("isva." + attrName.toLowerCase(), List.of(value));
            }
        }
        attrs.put("isva.orphaned", List.of("false"));
        attrs.put("isva.secuserdn", List.of(secUser.getDN()));
        return new LdapUser(demographic.getDn(), attrs);
    }

    private LdapUser markOrphaned(LdapUser demographic) {
        Map<String, List<String>> attrs = new HashMap<>(demographic.getAttributes());
        attrs.put("isva.orphaned", List.of("true"));
        log.debug("Orphaned demographic (no paired secUser): {}", demographic.getDn());
        return new LdapUser(demographic.getDn(), attrs);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private VendorIntegrationIsvaConfig activeLinkedConfigOrNull(DirectoryConnection dir) {
        if (dir == null || dir.getId() == null) {
            return null;
        }
        Optional<VendorIntegrationIsvaConfig> maybe = configRepo.findById(dir.getId());
        if (maybe.isEmpty() || !maybe.get().isEnabled()) {
            return null;
        }
        VendorIntegrationIsvaConfig cfg = maybe.get();
        // Only linked mode needs read-side enrichment — inline mode's
        // sec* attrs already live on the user entry, so the standard
        // read path returns them without help.
        if (cfg.getTopologyMode() != IsvaTopologyMode.LINKED) {
            return null;
        }
        if (cfg.getManagementDitBaseDn() == null || cfg.getManagementDitBaseDn().isBlank()) {
            // Defensive: DB CHECK constraint should prevent this, but
            // null-tolerant here avoids a confusing NullPointerException
            // deep in the filter builder.
            log.warn("Linked-mode ISVA config for directory {} has null "
                    + "management_dit_base_dn — skipping joined-read.",
                    dir.getDisplayName());
            return null;
        }
        return cfg;
    }

    private SearchResultEntry lookupCaseInsensitive(
            Map<String, SearchResultEntry> map, String dn) {
        return map.get(dn == null ? null : dn.toLowerCase());
    }
}
