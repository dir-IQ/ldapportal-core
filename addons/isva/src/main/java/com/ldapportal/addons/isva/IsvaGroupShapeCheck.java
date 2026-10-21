// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchResultEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads a group entry's shape for the {@code require_sec_group} gate:
 * does the target group carry a given objectClass? One extra LDAP read
 * per gated membership write — the same cost profile as
 * {@link IsvaLinkedUserLookup}'s per-write secUser resolution.
 *
 * <p>Returns {@link Optional#empty()} when the entry can't be read
 * (missing, or an LDAP error). The caller treats "can't verify" as
 * "proceed": a missing group will fail the membership MODIFY with the
 * server's own NO_SUCH_OBJECT — clearer than a refusal guessing at the
 * cause — and a transient read error must not turn into a hard refusal
 * of an otherwise-valid write.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsvaGroupShapeCheck {

    private final LdapConnectionFactory connectionFactory;
    private final IsvaGroupRegistry registry;

    /**
     * Whether IVIA knows the group: a secGroup registration entry in the
     * group container points at it (what {@code pdadmin group import} and
     * LDAPPortal's "Import into IVIA" write), or the group itself carries
     * {@code objectClass: secGroup}. Empty when that can't be verified (the
     * group can't be read and the registration lookup failed), which the
     * caller treats as "proceed".
     */
    public Optional<Boolean> isImportedIntoIvia(DirectoryConnection dir,
                                                VendorIntegrationIsvaConfig cfg,
                                                String groupDn) {
        boolean lookedUp = false;
        String container = cfg.effectiveGroupContainerDn();
        if (container != null) {
            try {
                if (registry.findRegistrationDn(dir, container, groupDn).isPresent()) {
                    return Optional.of(true);
                }
                lookedUp = true;
            } catch (RuntimeException e) {
                log.debug("Could not look up the IVIA registration of {} under {}: {}",
                        groupDn, container, e.getMessage());
            }
        }
        Optional<Boolean> overlay = hasObjectClass(dir, groupDn, "secGroup");
        if (overlay.isPresent() && overlay.get()) {
            return overlay;
        }
        // Not registered for certain only when the registration lookup ran
        // (or there's no container to look in) and the group was readable.
        if (overlay.isPresent() && (lookedUp || container == null)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    public Optional<Boolean> hasObjectClass(DirectoryConnection dir,
                                            String groupDn,
                                            String objectClass) {
        return connectionFactory.withConnection(dir, conn -> {
            try {
                SearchResultEntry entry = conn.getEntry(groupDn, "objectClass");
                if (entry == null) {
                    return Optional.empty();
                }
                String[] values = entry.getAttributeValues("objectClass");
                if (values == null) {
                    return Optional.of(false);
                }
                for (String value : values) {
                    if (value.equalsIgnoreCase(objectClass)) {
                        return Optional.of(true);
                    }
                }
                return Optional.of(false);
            } catch (LDAPException e) {
                log.debug("Could not read objectClasses of group {}: {}",
                        groupDn, e.getMessage());
                return Optional.empty();
            }
        });
    }
}
