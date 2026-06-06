// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.repository.RecomputeRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled drainer for the recompute queue. Each tick pulls a batch of pending
 * requests (oldest first) and recomputes each via {@link RecomputeEngine}.
 *
 * <p>Per-identity processing, not per-link FIFO: there is no ordering constraint
 * because the engine is convergent — a request is just "this key may have
 * changed." A request is removed once processed (the engine has applied the
 * transition or recorded the identity as FAILED); transient/unexpected faults
 * leave it for the next tick. Closure enqueues new requests that drain on
 * subsequent ticks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecomputeWorker {

    private static final int BATCH_SIZE = 200;

    private final RecomputeRequestRepository requestRepo;
    private final RecomputeEngine engine;

    @Scheduled(fixedDelayString = "${ldapportal.sync.worker.fixed-delay-ms:10000}")
    public void drainQueue() {
        try {
            List<RecomputeRequest> batch = requestRepo.findBatch(PageRequest.of(0, BATCH_SIZE));
            for (RecomputeRequest req : batch) {
                try {
                    engine.process(req.getSyncSetId(), req.getRequestKey());
                    requestRepo.deleteByKey(req.getSyncSetId(), req.getRequestKey());
                } catch (Exception ex) {
                    // Engine handles its own apply failures (marking the identity
                    // FAILED); reaching here means an unexpected/transient fault.
                    // Leave the request for the next tick to retry.
                    log.error("Recompute of ({}, {}) failed unexpectedly: {}",
                            req.getSyncSetId(), req.getRequestKey(), ex.toString());
                }
            }
        } catch (Exception ex) {
            // A @Scheduled method that throws stops being scheduled — guard the loop.
            log.error("Sync recompute drain pass failed: {}", ex.toString());
        }
    }
}
