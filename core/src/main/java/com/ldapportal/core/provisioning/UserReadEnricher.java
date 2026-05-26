// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.model.LdapUser;

import java.util.List;

/**
 * SPI for adding attributes to {@link LdapUser} entries returned
 * from {@code LdapUserService} reads. Symmetric with
 * {@link ProvisioningInterceptor} (which customises the write
 * path); this one customises the read path.
 *
 * <p>The driving use case is the ISVA linked-mode topology where
 * one logical user is represented by two LDAP entries —
 * demographic + secUser — in different DITs. The UI wants to
 * show that as a single user with attributes from both entries
 * merged together. The ISVA addon implements this interface and
 * does the secUser lookup; the read services don't need to know
 * anything ISVA-specific.</p>
 *
 * <p>Implementers should override {@link #enrichBatch} (the
 * efficient path that lets implementations issue one LDAP search
 * per page rather than one per row). {@link #enrich} has a
 * default implementation that delegates to a batch of one.</p>
 *
 * <p>Contracts:
 * <ul>
 *   <li>The returned list must have the same size and order as
 *       the input. Implementations augment in-place (per-user)
 *       but never reorder or filter.</li>
 *   <li>Augmentation is by creating new {@link LdapUser}
 *       instances with extra map keys, not by mutating the
 *       input (instances are nominally immutable via the
 *       unmodifiable-map wrapper).</li>
 *   <li>Returning the input unchanged is a valid no-op for
 *       directories the enricher doesn't apply to.</li>
 * </ul></p>
 */
public interface UserReadEnricher {

    /**
     * Augment a batch of users with extra attributes. Called by
     * {@code LdapUserService.searchUsers} once per result page;
     * implementations that need an LDAP roundtrip should issue
     * one search per call (not one per row), using OR'd filters
     * across the page's DNs.
     *
     * @return a list the same size + order as {@code users},
     *         possibly with augmented entries.
     */
    List<LdapUser> enrichBatch(DirectoryConnection dir, List<LdapUser> users);

    /**
     * Augment a single user. Default delegates to a batch of
     * size 1; implementations may override for a single-entry
     * fast path.
     */
    default LdapUser enrich(DirectoryConnection dir, LdapUser user) {
        List<LdapUser> result = enrichBatch(dir, List.of(user));
        return result.isEmpty() ? user : result.get(0);
    }
}
