// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The app-intercept adapter: turns a successful portal-initiated write into
 * {@code recompute(dn)} requests for every sync set that sources the written
 * directory and whose object scope contains the DN. The engine re-reads the
 * source entry, so a bare DN is all the signal that's needed (ADD post-images
 * could be passed to skip the read — a later optimization).
 *
 * <p>Invoked after the LDAP write has already succeeded, so it must never throw
 * back into the caller's operation: any failure here is swallowed and the
 * reconcile sweep is the backstop.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncWriteCaptor {

    private final SyncLinkRepository syncLinkRepo;
    private final SyncSetRepository syncSetRepo;
    private final RecomputeEnqueuer enqueuer;
    private final EntitlementService entitlementService;

    public void onWrite(UUID sourceDirectoryId, String dn) {
        if (!entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
            return; // engine inert without the entitlement
        }
        try {
            for (SyncLink link : syncLinkRepo.findAllBySourceDirIdAndEnabledTrue(sourceDirectoryId)) {
                for (SyncSet set : syncSetRepo.findAllByLinkId(link.getId())) {
                    if (set.isEnabled() && inScope(set, dn)) {
                        enqueuer.enqueue(set.getId(), dn, null);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Sync capture enqueue failed for {} on directory {}: {}",
                    dn, sourceDirectoryId, ex.toString());
        }
    }

    private static boolean inScope(SyncSet set, String dn) {
        String base = set.getObjectScopeBaseDn();
        if (base == null) {
            return true;
        }
        try {
            return new DN(dn).isDescendantOf(new DN(base), true);
        } catch (LDAPException ex) {
            return false;
        }
    }
}
