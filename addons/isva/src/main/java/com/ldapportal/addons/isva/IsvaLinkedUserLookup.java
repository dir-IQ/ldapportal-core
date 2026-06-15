// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the {@code secUser} entry DN paired with a given
 * demographic DN, by searching the ISVA management DIT for
 * {@code (&(objectClass=secUser)(secDN=<demographic-dn>))}.
 *
 * <p>Used by {@link IsvaProvisioningInterceptor} in linked-mode
 * plans that need to write against the secUser side of the
 * identity — {@code planUserDelete}, {@code planPasswordSet}, and
 * {@code planGroupMembership} when
 * {@code group_member_target = SECUSER_DN}. The lookup is one
 * extra LDAP search per linked-mode write; the cost is acceptable
 * compared to alternatives (storing the secUser DN as a custom
 * attribute on the demographic entry would couple LDAPPortal to
 * an out-of-band write path that ISVA's own UI doesn't maintain).</p>
 *
 * <p>Search uses {@code "1.1"} as the requested attributes —
 * an LDAP convention meaning "return no attributes, only the DN".
 * Saves bandwidth; the only piece of the secUser entry the
 * interceptor needs is the DN itself.</p>
 *
 * <p>Returns {@link Optional#empty()} when zero matches (orphaned
 * demographic — caller decides whether to throw or treat
 * gracefully). Logs a warning + returns the first match when
 * multiple secUsers point at the same demographic DN (shouldn't
 * happen with a healthy DIT, but ISVA's policy administration
 * doesn't enforce uniqueness, so it can happen in practice).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsvaLinkedUserLookup {

    private final LdapConnectionFactory connectionFactory;

    /**
     * @param dir                  the directory connection. The
     *                             management DIT is reached via the
     *                             same connection as the demographic
     *                             entries (ISVA's full-mode design
     *                             keeps both DITs on the same LDAP
     *                             server even when topologically
     *                             separated).
     * @param managementDitBaseDn  search base, from
     *                             {@code vendor_integration_isva_config}.
     * @param demographicDn        the demographic-entry DN whose
     *                             paired secUser is being looked up.
     */
    public Optional<String> findSecUserDn(DirectoryConnection dir,
                                           String managementDitBaseDn,
                                           String demographicDn) {
        if (managementDitBaseDn == null || managementDitBaseDn.isBlank()) {
            throw new IllegalArgumentException(
                    "managementDitBaseDn is required for linked-mode lookups");
        }

        return connectionFactory.withConnection(dir, conn -> {
            try {
                SearchRequest req = new SearchRequest(
                        managementDitBaseDn,
                        SearchScope.SUB,
                        Filter.createANDFilter(
                                Filter.createEqualityFilter("objectClass", "secUser"),
                                Filter.createEqualityFilter("secDN", demographicDn)),
                        // "1.1" = no attributes, just the DN.
                        "1.1");
                // Cap at 2 so we can detect (and warn about) duplicates without
                // pulling the full result set.
                req.setSizeLimit(2);

                SearchResult result = conn.search(req);
                int count = result.getEntryCount();

                if (count == 0) {
                    return Optional.<String>empty();
                }
                if (count > 1) {
                    log.warn("Linked secUser lookup found {} entries for demographic "
                            + "DN {} under {} — using the first. The duplicate is a "
                            + "directory-side data problem; pdadmin user delete + "
                            + "user import is the usual repair.",
                            count, demographicDn, managementDitBaseDn);
                }
                return Optional.of(result.getSearchEntries().get(0).getDN());
            } catch (LDAPException e) {
                throw new LdapOperationException(
                        "Failed to look up secUser entry paired with " + demographicDn
                                + " under " + managementDitBaseDn, e);
            }
        });
    }
}
