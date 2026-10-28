// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * A sync set's settings as a reconcile run saw them, kept with the run so its
 * behaviour can be explained after the set is edited.
 *
 * @param holdForReview            NONE, DELETES or ALL (a run saved before hold for review
 *                                 replaced the delete policy reads its {@code deletePolicy}:
 *                                 DELETE as NONE, REVIEW as DELETES)
 * @param objectScopeBaseDn        source scope base DN ({@code null} = the directory's base)
 * @param objectScope              source search scope ({@code null} = subtree)
 * @param applicabilityFilter      source filter selecting members
 * @param targetBaseDn             where members are placed on the target
 * @param identityKey              identity attribute override ({@code null} = the type's default)
 * @param sourceAnchorAttribute    brownfield adoption anchor, if any
 * @param reconcileCadenceSeconds  the set's cadence ({@code null} = global default)
 * @param guardMaxDeletes          delete guard: absolute cap on deletes per run
 * @param guardMaxDeletePercent    delete guard: percentage of the managed set…
 * @param guardMinPopulation       …applied once the managed set is at least this large
 */
public record SyncRunSettings(
        @JsonAlias("deletePolicy") String holdForReview,
        String objectScopeBaseDn,
        String objectScope,
        String applicabilityFilter,
        String targetBaseDn,
        String identityKey,
        String sourceAnchorAttribute,
        Long reconcileCadenceSeconds,
        int guardMaxDeletes,
        int guardMaxDeletePercent,
        int guardMinPopulation) {

    public SyncRunSettings {
        if ("DELETE".equals(holdForReview)) {
            holdForReview = "NONE";
        } else if ("REVIEW".equals(holdForReview)) {
            holdForReview = "DELETES";
        }
    }
}
