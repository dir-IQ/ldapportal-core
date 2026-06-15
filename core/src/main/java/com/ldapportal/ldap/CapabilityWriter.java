// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.ldap.model.DirectoryCapabilities;
import com.ldapportal.repository.DirectoryConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Thin transactional wrapper around the targeted-capabilities update.
 * Exists as a separate bean (not a private method on
 * {@link DirectoryCapabilityRefresher}) because Spring's
 * {@code @Transactional} only applies through the bean proxy — an
 * intra-class call would bypass it. Keeping this small isolates the
 * <em>only</em> code path that holds a DB connection: the
 * {@code @Modifying} UPDATE itself.
 */
@Component
@RequiredArgsConstructor
public class CapabilityWriter {

    private final DirectoryConnectionRepository dirRepo;

    /**
     * Run the targeted UPDATE in its own short-lived transaction
     * ({@link Propagation#REQUIRES_NEW}) so the refresher's listener
     * thread holds no DB connection during the preceding LDAP probe.
     * Returns the number of rows updated; 0 means the row was deleted
     * between the listener's read and this call.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int persistCapabilities(UUID id, DirectoryCapabilities caps) {
        return dirRepo.updateCapabilities(id, caps);
    }
}
