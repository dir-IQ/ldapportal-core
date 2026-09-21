// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.repository.RecomputeRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Writes recompute requests onto the coalescing queue. Every change feed
 * (app-intercept, changelog, reconcile) and the closure resolver funnel through
 * here. The composite PK {@code (sync_set_id, request_key)} dedups bursts for
 * free; the highest source cursor seen is kept so behind-cursor triggers drop.
 *
 * <p>An enqueue must never lose a trigger to a race with the worker. It is
 * therefore built from row-count statements rather than entity writes:
 * <ol>
 *   <li>{@code reopen} — a conditional UPDATE that nulls the claim on an existing
 *       row. If the worker deletes the row concurrently this affects 0 rows and
 *       returns, instead of failing with a stale-state exception the way an
 *       entity merge did (which the app-intercept path then swallowed).</li>
 *   <li>Otherwise INSERT in its own transaction; a concurrent insert of the same
 *       key surfaces as a duplicate-key failure, in which case the winner's row is
 *       simply re-opened.</li>
 * </ol>
 * Each step is a short transaction of its own, so this is safe to call from the
 * (non-transactional) app-intercept hot path, from inside a caller's transaction,
 * and from within the engine's own processing.
 */
@Component
@Slf4j
public class RecomputeEnqueuer {

    private final RecomputeRequestRepository repo;
    private final TransactionTemplate insertTx;

    public RecomputeEnqueuer(RecomputeRequestRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.insertTx = new TransactionTemplate(txManager);
        // Independent of any caller transaction: a duplicate-key rollback here must
        // never mark an enclosing transaction rollback-only.
        this.insertTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void enqueue(UUID syncSetId, String key, Long srcCursor) {
        if (repo.reopen(syncSetId, key) == 0) {
            try {
                insertTx.executeWithoutResult(status -> {
                    RecomputeRequest r = new RecomputeRequest();
                    r.setSyncSetId(syncSetId);
                    r.setRequestKey(key);
                    r.setSrcCursor(srcCursor);
                    repo.saveAndFlush(r);
                });
            } catch (DataIntegrityViolationException dup) {
                // A concurrent enqueue inserted the same key first — already
                // coalesced; make sure it is unclaimed so the newest state is seen.
                log.trace("Recompute request ({}, {}) already enqueued concurrently", syncSetId, key);
                repo.reopen(syncSetId, key);
            }
        }
        if (srcCursor != null) {
            repo.bumpCursor(syncSetId, key, srcCursor);
        }
    }
}
