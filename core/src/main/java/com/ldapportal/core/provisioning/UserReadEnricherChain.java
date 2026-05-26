// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.model.LdapUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The single entry point read services use to apply registered
 * {@link UserReadEnricher} beans to entries before returning them
 * to controllers. Wraps the Spring-injected list of enrichers.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>No enrichers registered → passes the input through
 *       unchanged. This is what the community distribution does
 *       (no addons → no enrichers), and what the commercial
 *       distribution does on directories that don't have the
 *       ISVA addon's read-side conditions met (e.g. inline mode
 *       or non-ISVA directories).</li>
 *   <li>One or more enrichers → applies them in order. The output
 *       of one enricher is the input to the next, so they
 *       compose. The fold form lets future addons add their own
 *       enrichment without coordinating with each other.</li>
 * </ul></p>
 *
 * <p>The chain itself is a fold: {@code enrichers.foldLeft(input,
 * (acc, e) -> e.enrichBatch(dir, acc))}. Output size always equals
 * input size — enrichers may not filter or reorder, only augment.</p>
 */
@Component
@Slf4j
public class UserReadEnricherChain {

    private final List<UserReadEnricher> enrichers;

    @Autowired
    public UserReadEnricherChain(List<UserReadEnricher> enrichers) {
        this.enrichers = enrichers == null ? List.of() : List.copyOf(enrichers);
        if (this.enrichers.isEmpty()) {
            log.debug("No UserReadEnricher beans registered — read path "
                    + "returns LdapUser entries as-is.");
        } else {
            log.info("UserReadEnricher chain has {} enricher(s) registered: {}",
                    this.enrichers.size(),
                    this.enrichers.stream()
                            .map(e -> e.getClass().getSimpleName())
                            .toList());
        }
    }

    /**
     * Enrich one user. Equivalent to chaining
     * {@code enricher.enrich(dir, user)} across registered
     * enrichers in order.
     */
    public LdapUser enrich(DirectoryConnection dir, LdapUser user) {
        if (enrichers.isEmpty()) {
            return user;
        }
        LdapUser current = user;
        for (UserReadEnricher enricher : enrichers) {
            current = enricher.enrich(dir, current);
        }
        return current;
    }

    /**
     * Enrich a batch of users. Each registered enricher gets the
     * batch as a whole — important for enrichers that issue a
     * single LDAP search per page (the ISVA linked-mode joined-
     * read does this).
     */
    public List<LdapUser> enrichBatch(DirectoryConnection dir, List<LdapUser> users) {
        if (enrichers.isEmpty() || users.isEmpty()) {
            return users;
        }
        List<LdapUser> current = users;
        for (UserReadEnricher enricher : enrichers) {
            current = enricher.enrichBatch(dir, current);
        }
        return current;
    }

    /** True iff at least one enricher is registered. */
    public boolean hasEnrichers() {
        return !enrichers.isEmpty();
    }
}
