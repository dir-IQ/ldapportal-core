// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.core.directory.event.DirectoryConnectionSavedEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.model.DirectoryCapabilities;
import com.ldapportal.repository.DirectoryConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Post-commit handler that runs the root-DSE capability probe and
 * persists the snapshot on the directory connection. Wired this way
 * rather than inline in {@code DirectoryConnectionService} so that:
 *
 * <ol>
 *   <li>The LDAP network round-trip never sits inside the save's
 *       {@code @Transactional} boundary — saves return in DB time,
 *       not LDAP time, so a misconfigured / unreachable host doesn't
 *       hold row locks for up to {@code poolConnectTimeoutSeconds +
 *       poolResponseTimeoutSeconds} (default 40s) and can't exhaust
 *       the HikariCP pool under concurrent admin activity.</li>
 *   <li>The probe-time {@link LdapConnectionFactory} pool creation
 *       cannot leak under a rolled-back UUID. The
 *       {@link TransactionPhase#AFTER_COMMIT} phase guarantees the
 *       row exists in the DB before the probe runs; if the create
 *       transaction rolled back, the saved event never fires.</li>
 * </ol>
 *
 * <p>The probe persists in a separate {@code REQUIRES_NEW} transaction
 * because the originating one has already committed. Failures here are
 * logged and swallowed — capabilities being null is a recognised state
 * the UI handles by hiding the vendor chip (or rendering the
 * type-label fallback when probed-with-no-vendor returns a snapshot).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DirectoryCapabilityRefresher {

    private final DirectoryConnectionRepository dirRepo;
    private final LdapCapabilityProbeService    probeService;

    /**
     * Async so the calling thread (the HTTP request handler that just
     * committed the directory save) returns immediately. Annotation
     * picks up Spring's default async executor; if the deployment
     * disables {@code @EnableAsync}, listeners fall back to running
     * inline on the publisher thread — still after commit, just not
     * off the request thread.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDirectorySaved(DirectoryConnectionSavedEvent event) {
        Optional<DirectoryConnection> opt = dirRepo.findById(event.directoryId());
        if (opt.isEmpty()) {
            log.debug("Directory {} no longer exists at refresh time — skipping probe", event.directoryId());
            return;
        }
        DirectoryConnection dc = opt.get();
        DirectoryCapabilities caps = probeService.probe(dc);
        // Null caps means: probe was skipped (Entra) or failed (network /
        // RBAC / server hides root DSE). Either way, leave the row's
        // capabilities column as set by DirectoryConnectionService —
        // which clears it on type/host change so a stale snapshot from a
        // previous vendor can't survive the rename.
        if (caps != null) {
            dc.setCapabilities(caps);
            dirRepo.save(dc);
        }
    }
}
