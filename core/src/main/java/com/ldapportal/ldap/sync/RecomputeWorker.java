// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.repository.RecomputeRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Scheduled drainer for the recompute queue. Each tick pulls a batch of unclaimed
 * requests (oldest first) and recomputes each via {@link RecomputeEngine}.
 *
 * <p>Per-identity processing, not per-link FIFO: there is no ordering constraint
 * because the engine is convergent — a request is just "this key may have
 * changed." Each request is processed under a claim/settle lease: claim it,
 * recompute, then remove it only if no re-trigger landed meanwhile
 * ({@code deleteIfClaimed}); on an unexpected fault the claim is released so the
 * request retries. A stale-claim sweep reclaims rows orphaned by a crash.
 *
 * <p>Gated on the {@code DIRECTORY_SYNC} entitlement so the engine is inert in
 * editions without it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecomputeWorker {

    private static final int BATCH_SIZE = 200;
    private static final Duration STALE_CLAIM_THRESHOLD = Duration.ofMinutes(10);

    private final RecomputeRequestRepository requestRepo;
    private final RecomputeEngine engine;
    private final EntitlementService entitlementService;

    @Scheduled(fixedDelayString = "${ldapportal.sync.worker.fixed-delay-ms:10000}")
    public void drainQueue() {
        if (!entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
            return;
        }
        try {
            List<RecomputeRequest> batch = requestRepo.findBatchUnclaimed(PageRequest.of(0, BATCH_SIZE));
            OffsetDateTime now = OffsetDateTime.now();
            for (RecomputeRequest req : batch) {
                if (requestRepo.claim(req.getSyncSetId(), req.getRequestKey(), now) == 0) {
                    continue; // another worker claimed it
                }
                try {
                    engine.process(req.getSyncSetId(), req.getRequestKey());
                    requestRepo.deleteIfClaimed(req.getSyncSetId(), req.getRequestKey());
                } catch (Exception ex) {
                    // Engine handles its own apply failures (marking the identity
                    // FAILED); reaching here is an unexpected/transient fault —
                    // release the claim so the request retries next tick.
                    log.error("Recompute of ({}, {}) failed unexpectedly: {}",
                            req.getSyncSetId(), req.getRequestKey(), ex.toString());
                    requestRepo.releaseClaim(req.getSyncSetId(), req.getRequestKey());
                }
            }
        } catch (Exception ex) {
            // A @Scheduled method that throws stops being scheduled — guard the loop.
            log.error("Sync recompute drain pass failed: {}", ex.toString());
        }
    }

    /** Reclaim requests left claimed by a worker that crashed mid-process. */
    @Scheduled(fixedDelayString = "${ldapportal.sync.worker.stale-reset-ms:60000}")
    public void releaseStaleClaims() {
        if (!entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
            return;
        }
        try {
            int released = requestRepo.releaseStaleClaims(
                    OffsetDateTime.now().minus(STALE_CLAIM_THRESHOLD));
            if (released > 0) {
                log.warn("Released {} stale recompute claim(s) — worker likely crashed mid-process", released);
            }
        } catch (Exception ex) {
            log.error("Sync recompute stale-claim sweep failed: {}", ex.toString());
        }
    }
}
