// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.repository.DirectoryConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically syncs Entra ID directories into the local cache.
 * Uses delta queries for incremental sync after the initial full pull.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntraSyncScheduler {

    private final DirectoryConnectionRepository dirRepo;
    private final EntraSyncService syncService;

    /** Prevent concurrent syncs for the same directory. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${ldapportal.entra.poll-interval-ms:300000}",
               initialDelayString = "${ldapportal.entra.poll-initial-delay-ms:60000}")
    public void poll() {
        for (DirectoryConnection dc : dirRepo.findAll()) {
            if (dc.getDirectoryType() != DirectoryType.ENTRA_ID || !dc.isEnabled()) continue;
            if (!running.add(dc.getId())) {
                log.debug("Skipping Entra sync for {} — already running", dc.getDisplayName());
                continue;
            }

            try {
                syncService.deltaSync(dc);
            } catch (Exception e) {
                log.warn("Entra sync failed for {}: {}", dc.getDisplayName(), e.getMessage());
            } finally {
                running.remove(dc.getId());
            }
        }
    }
}
