// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.entity.RecomputeRequestId;
import com.ldapportal.repository.RecomputeRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes recompute requests onto the coalescing queue. Every change feed
 * (app-intercept, changelog, reconcile) and the closure resolver funnel through
 * here. The composite PK {@code (sync_set_id, request_key)} dedups bursts for
 * free; an upsert keeps the maximum source cursor so behind-cursor triggers drop.
 *
 * <p>Each enqueue runs in its own {@code REQUIRES_NEW} transaction so it can be
 * called from the (non-transactional) app-intercept hot path and from within the
 * engine's processing without coupling to a caller transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecomputeEnqueuer {

    private final RecomputeRequestRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(UUID syncSetId, String key, Long srcCursor) {
        RecomputeRequestId id = new RecomputeRequestId(syncSetId, key);
        var existing = repo.findById(id);
        if (existing.isPresent()) {
            RecomputeRequest r = existing.get();
            boolean dirty = false;
            if (srcCursor != null && (r.getSrcCursor() == null || srcCursor > r.getSrcCursor())) {
                r.setSrcCursor(srcCursor);
                dirty = true;
            }
            // Re-trigger of a key that may be mid-processing: null the claim so the
            // worker's "delete-if-still-claimed" misses and the request is
            // reprocessed against the newer source state (no lost update).
            if (r.getClaimedAt() != null) {
                r.setClaimedAt(null);
                dirty = true;
            }
            if (dirty) {
                repo.save(r);
            }
            return;
        }
        RecomputeRequest r = new RecomputeRequest();
        r.setSyncSetId(syncSetId);
        r.setRequestKey(key);
        r.setSrcCursor(srcCursor);
        try {
            repo.save(r);
        } catch (DataIntegrityViolationException dup) {
            // A concurrent enqueue inserted the same key first — already coalesced.
            log.trace("Recompute request ({}, {}) already enqueued concurrently", syncSetId, key);
        }
    }
}
