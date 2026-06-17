// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/**
 * Belts-and-suspenders verification of a sync set: an <em>independent</em>
 * comparison of the source and target directories that does not consult the
 * membership index. The source scope (object-scope base DN + applicability
 * filter) is enumerated and projected exactly as the engine would, then matched
 * by target DN against the entries actually present under the target base DN
 * (same applicability filter). Anything that disagrees is flagged.
 *
 * <p>Unlike {@code SyncReconcilePreview}, which plans against the membership
 * index, this re-reads both directories and compares their live contents — so it
 * surfaces drift the index believes is already converged.
 *
 * @param sourceMembers     in-scope, in-filter source entries (the set's members)
 * @param targetEntries     entries found under the target base matching the filter
 * @param inSync            members present on the target with matching content
 * @param missingOnTarget   members with no entry at their expected target DN
 * @param orphanOnTarget    target entries with no corresponding source member
 * @param contentMismatches members present on both sides whose attributes differ
 * @param sampleMissing     up to a handful of expected target DNs that are absent
 * @param sampleOrphans     up to a handful of target DNs with no source member
 * @param sampleMismatches  up to a handful of target DNs whose content has drifted
 * @param sourceComplete    false when source enumeration failed (counts partial)
 * @param targetComplete    false when target enumeration failed (counts partial)
 * @param note              human-readable explanation when the set can't be verified
 */
public record SyncVerifyResult(
        int sourceMembers,
        int targetEntries,
        int inSync,
        int missingOnTarget,
        int orphanOnTarget,
        int contentMismatches,
        List<String> sampleMissing,
        List<String> sampleOrphans,
        List<String> sampleMismatches,
        boolean sourceComplete,
        boolean targetComplete,
        String note) {
}
