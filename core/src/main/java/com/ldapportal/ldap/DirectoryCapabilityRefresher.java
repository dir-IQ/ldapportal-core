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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Post-commit handler that probes the directory's root DSE and writes
 * the snapshot back to the {@code capabilities} JSONB column.
 *
 * <p>Critical resource-ordering invariant: <b>no DB connection is held
 * across the LDAP probe</b>. The method is plain — no {@code @Transactional}
 * — so {@code findById} runs in its own auto-commit read, the probe
 * runs with no DB context at all, and the writer below opens a tiny
 * transaction only for the targeted UPDATE. Without this split, a
 * misconfigured / unreachable host would pin a HikariCP connection
 * for up to {@code poolConnectTimeoutSeconds + poolResponseTimeoutSeconds}
 * (default 40s) on every refresh, starving the pool under any burst
 * of saves.
 *
 * <p>All errors are caught and logged. The listener runs {@code @Async}
 * on Spring's default executor, and a bare {@code RuntimeException}
 * propagating out of an async listener is consumed by
 * {@code SimpleAsyncUncaughtExceptionHandler} with a single warn-level
 * line that operators don't watch — so the outer catch is there to
 * keep every failure visible at error level and tagged with the
 * directory id.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DirectoryCapabilityRefresher {

    private final DirectoryConnectionRepository dirRepo;
    private final LdapCapabilityProbeService    probeService;
    private final CapabilityWriter              writer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDirectorySaved(DirectoryConnectionSavedEvent event) {
        try {
            Optional<DirectoryConnection> opt = dirRepo.findById(event.directoryId());
            if (opt.isEmpty()) {
                log.debug("Directory {} no longer exists at refresh time — skipping probe",
                        event.directoryId());
                return;
            }
            DirectoryConnection dc = opt.get();
            DirectoryCapabilities caps = probeService.probe(dc);
            if (caps == null) {
                // Null caps means: probe was skipped (Entra) or failed
                // (network / RBAC / server hides root DSE). The save path
                // explicitly clears capabilities on update, so leaving it
                // alone here keeps a stale snapshot from outliving the
                // host/credential change that triggered this event.
                return;
            }
            int rows = writer.persistCapabilities(event.directoryId(), caps);
            if (rows == 0) {
                log.debug("Directory {} deleted between probe and persist — skipping",
                        event.directoryId());
            }
        } catch (Exception ex) {
            log.error("Capability refresh failed for directory {}: {}",
                    event.directoryId(), ex.toString());
        }
    }
}
