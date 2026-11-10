// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncVerifyResult;
import com.ldapportal.repository.SyncSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Stores a complete content verify as the set's snapshot: its counts and held
 * reference counts on {@code sync_set}, its drift items in {@link SyncDriftStore}
 * and every held reference in {@link SyncHeldStore}, in one transaction, so the Drift tab never shows one verify's counts beside
 * another's items. Used by the verify endpoint and the post-reconcile verify.
 */
@Component
@RequiredArgsConstructor
public class SyncVerifySnapshot {

    private final SyncSetRepository setRepo;
    private final SyncDriftStore driftStore;
    private final SyncHeldStore heldStore;

    /** Record {@code result}, which must be complete (both sides read in full), as verified now. */
    @Transactional
    public void record(UUID syncSetId, SyncVerifyResult result) {
        setRepo.recordVerifyResult(syncSetId, OffsetDateTime.now(),
                result.missingOnTarget(), result.orphanOnTarget(), result.contentMismatches());
        driftStore.replace(syncSetId, result.items());
        if (result.heldReferences() != null) {
            setRepo.recordVerifyHeld(syncSetId, result.heldReferences().entries(),
                    result.heldReferences().dangling());
            heldStore.replace(syncSetId, result.heldReferences().items());
        }
    }
}
