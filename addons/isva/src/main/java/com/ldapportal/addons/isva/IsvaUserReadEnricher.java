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
import com.unboundid.ldap.sdk.DN;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the paired secUser entry for each user in a result set
 * and augments the returned {@link LdapUser} with {@code isva.*}-
 * prefixed attributes from the secUser. The UI joins demographic
 * + secUser into a single user view via these prefixed keys.
 *
 * <p>No-op for directories without an active ISVA config row. In
 * inline topology there's nothing to join (the secUser overlay already
 * lives on the user entry); the enricher only adds the
 * {@value #HAS_ACCOUNT} flag there.</p>
 *
 * <p>For directories configured for linked topology, this
 * enricher issues <b>one LDAP search per result page</b>
 * (filter = {@code (&(objectClass=secUser)(|(secDN=dn1)(secDN=dn2)…))})
 * and joins in-memory. Worst-case extra cost on a 100-row page
 * is one search + one round-trip; per-row enrichment would have
 * cost 100. The cap is the page-size configured on the directory
 * connection.</p>
 *
 * <p>Every user it can classify is tagged {@value #HAS_ACCOUNT}
 * ({@code "true"} / {@code "false"}): whether the user has an IVIA
 * account. Linked mode: a paired secUser was found. Inline mode: the
 * entry carries the {@code secUser} objectClass, judged only when the
 * entry was read with its {@code objectClass} (otherwise the flag is left
 * off rather than guessed). Same rule as {@code IsvaAccountStatusProbe}.
 * The UI's "No IVIA account" chip and the users-without-an-account
 * report read it.</p>
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

    /** Whether the user has an IVIA account: {@code "true"} or {@code "false"}. */
    public static final String HAS_ACCOUNT = "isva.hasaccount";

    private final VendorIntegrationIsvaConfigRepository configRepo;
    private final LdapConnectionFactory connectionFactory;

    @Override
    public List<LdapUser> enrichBatch(DirectoryConnection dir, List<LdapUser> users) {
        if (users.isEmpty()) {
            return users;
        }
        VendorIntegrationIsvaConfig active = activeConfigOrNull(dir);
        if (active != null && active.getTopologyMode() == IsvaTopologyMode.INLINE) {
            return flagInline(users);
        }
        VendorIntegrationIsvaConfig cfg = activeLinkedConfigOrNull(dir);
        if (cfg == null) {
            // Non-ISVA directory (or a linked config without a container).
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
                cfg.effectiveUserContainerDn(),
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
                    // Keyed by the normalized DN: the server matched
                    // (secDN=...) by DN equality, so a secDN written with
                    // other spacing, case or escaping than the user's DN
                    // was found and must be paired, not flagged orphaned.
                    byDemographicDn.put(normalizeDn(secDn), entry);
                }
                return byDemographicDn;
            } catch (LDAPException e) {
                throw new LdapOperationException(
                        "Joined-read failed: secUser batch lookup against "
                                + cfg.effectiveUserContainerDn() + " errored", e);
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
        attrs.put(HAS_ACCOUNT, List.of("true"));
        attrs.put("isva.secuserdn", List.of(secUser.getDN()));
        return new LdapUser(demographic.getDn(), attrs);
    }

    private LdapUser markOrphaned(LdapUser demographic) {
        Map<String, List<String>> attrs = new HashMap<>(demographic.getAttributes());
        attrs.put(HAS_ACCOUNT, List.of("false"));
        log.debug("Demographic without a paired secUser: {}", demographic.getDn());
        return new LdapUser(demographic.getDn(), attrs);
    }

    /**
     * Inline mode: flag each user by whether its entry carries the
     * {@code secUser} objectClass. A user read without {@code objectClass}
     * can't be judged and is left as is.
     */
    private static List<LdapUser> flagInline(List<LdapUser> users) {
        List<LdapUser> result = new ArrayList<>(users.size());
        for (LdapUser user : users) {
            if (!user.hasAttribute("objectclass")) {
                result.add(user);
                continue;
            }
            boolean hasAccount = user.getValues("objectclass").stream()
                    .anyMatch(oc -> oc.equalsIgnoreCase("secUser"));
            Map<String, List<String>> attrs = new HashMap<>(user.getAttributes());
            attrs.put(HAS_ACCOUNT, List.of(Boolean.toString(hasAccount)));
            result.add(new LdapUser(user.getDn(), attrs));
        }
        return result;
    }

    // ── helpers ─────────────────────────────────────────────────────

    /**
     * Whether this directory has an active IVIA configuration this enricher
     * can classify users for (it sets {@value #HAS_ACCOUNT}): inline, or
     * linked with a secUser container.
     */
    public boolean flagsAccounts(DirectoryConnection dir) {
        VendorIntegrationIsvaConfig cfg = activeConfigOrNull(dir);
        if (cfg == null) return false;
        return cfg.getTopologyMode() == IsvaTopologyMode.INLINE || activeLinkedConfigOrNull(dir) != null;
    }

    private VendorIntegrationIsvaConfig activeConfigOrNull(DirectoryConnection dir) {
        if (dir == null || dir.getId() == null) {
            return null;
        }
        Optional<VendorIntegrationIsvaConfig> maybe = configRepo.findById(dir.getId());
        return maybe.isPresent() && maybe.get().isEnabled() ? maybe.get() : null;
    }

    private VendorIntegrationIsvaConfig activeLinkedConfigOrNull(DirectoryConnection dir) {
        VendorIntegrationIsvaConfig cfg = activeConfigOrNull(dir);
        if (cfg == null) {
            return null;
        }
        // Only linked mode needs read-side enrichment — inline mode's
        // sec* attrs already live on the user entry, so the standard
        // read path returns them without help.
        if (cfg.getTopologyMode() != IsvaTopologyMode.LINKED) {
            return null;
        }
        if (cfg.effectiveUserContainerDn() == null || cfg.effectiveUserContainerDn().isBlank()) {
            // Defensive: DB CHECK constraint should prevent this, but
            // null-tolerant here avoids a confusing NullPointerException
            // deep in the filter builder.
            log.warn("Linked-mode ISVA config for directory {} has null "
                    + "management DIT / user container — skipping joined-read.",
                    dir.getDisplayName());
            return null;
        }
        return cfg;
    }

    private SearchResultEntry lookupCaseInsensitive(
            Map<String, SearchResultEntry> map, String dn) {
        return map.get(dn == null ? null : normalizeDn(dn));
    }

    /**
     * The DN in normalized form (attribute names and values lower-cased,
     * insignificant spaces dropped, escaping canonical), so two spellings
     * of one DN compare equal. An unparseable value falls back to trimmed
     * lower case.
     */
    static String normalizeDn(String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException e) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }
}
