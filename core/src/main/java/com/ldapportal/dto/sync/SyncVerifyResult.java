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
 * @param orphansAnchored   true when only target entries carrying the set's source
 *                          anchor count as orphans (the set writes an anchor), so
 *                          entries it doesn't manage aren't counted; false when
 *                          every entry under the target base that matches the
 *                          filter is a candidate
 * @param heldReferences    members with a reference value the sync holds back (it
 *                          names an entry the sync hasn't placed), or null when not
 *                          counted; the sync's checks don't count these as drift
 * @param items             every drift item (not only the samples), with each
 *                          content drift's differing attributes. Stored for the
 *                          Drift tab ({@code SyncDriftStore}) rather than sent
 *                          with this result, which can be large.
 * @param leavingSet        anchored target entries no source member accounts for
 *                          that this set's own index still places there: entries
 *                          leaving the set (a delete held for review, or one the
 *                          next reconcile will make), so not counted as orphans
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
        String note,
        boolean orphansAnchored,
        HeldReferences heldReferences,
        @com.fasterxml.jackson.annotation.JsonIgnore List<SyncDriftItem> items,
        int leavingSet) {

    public SyncVerifyResult {
        items = items == null ? List.of() : items;
    }

    /** As the canonical constructor, with no entries counted as leaving the set. */
    public SyncVerifyResult(int sourceMembers, int targetEntries, int inSync, int missingOnTarget,
                            int orphanOnTarget, int contentMismatches, List<String> sampleMissing,
                            List<String> sampleOrphans, List<String> sampleMismatches,
                            boolean sourceComplete, boolean targetComplete, String note, boolean orphansAnchored,
                            HeldReferences heldReferences, List<SyncDriftItem> items) {
        this(sourceMembers, targetEntries, inSync, missingOnTarget, orphanOnTarget, contentMismatches,
                sampleMissing, sampleOrphans, sampleMismatches, sourceComplete, targetComplete, note,
                orphansAnchored, heldReferences, items, 0);
    }

    /** As the canonical constructor, without drift items. */
    public SyncVerifyResult(int sourceMembers, int targetEntries, int inSync, int missingOnTarget,
                            int orphanOnTarget, int contentMismatches, List<String> sampleMissing,
                            List<String> sampleOrphans, List<String> sampleMismatches,
                            boolean sourceComplete, boolean targetComplete, String note, boolean orphansAnchored,
                            HeldReferences heldReferences) {
        this(sourceMembers, targetEntries, inSync, missingOnTarget, orphanOnTarget, contentMismatches,
                sampleMissing, sampleOrphans, sampleMismatches, sourceComplete, targetComplete, note,
                orphansAnchored, heldReferences, List.of());
    }

    /**
     * Members whose reference values (a {@code secDN}, a group {@code member}) the
     * sync holds back because the entry they name isn't synced. {@code dangling}
     * of them name an entry that doesn't exist on the source at all: a source
     * data problem, not something a reconcile can fix. Up to
     * {@code SyncContentVerifier.HELD_CHECK_LIMIT} referenced DNs are looked up;
     * {@code unchecked} says some weren't.
     */
    public record HeldReferences(int entries, int dangling, boolean unchecked, List<HeldSample> samples) {
    }

    /** One held value: the member's target DN, the attribute, the source DN it names, and whether that's missing. */
    public record HeldSample(String entryDn, String attribute, String reference, boolean dangling) {
    }

    /** As the canonical constructor, with held references not counted. */
    public SyncVerifyResult(int sourceMembers, int targetEntries, int inSync, int missingOnTarget,
                            int orphanOnTarget, int contentMismatches, List<String> sampleMissing,
                            List<String> sampleOrphans, List<String> sampleMismatches,
                            boolean sourceComplete, boolean targetComplete, String note, boolean orphansAnchored) {
        this(sourceMembers, targetEntries, inSync, missingOnTarget, orphanOnTarget, contentMismatches,
                sampleMissing, sampleOrphans, sampleMismatches, sourceComplete, targetComplete, note,
                orphansAnchored, null);
    }

    /** This result with {@code held} counted. */
    public SyncVerifyResult withHeldReferences(HeldReferences held) {
        return new SyncVerifyResult(sourceMembers, targetEntries, inSync, missingOnTarget, orphanOnTarget,
                contentMismatches, sampleMissing, sampleOrphans, sampleMismatches, sourceComplete, targetComplete,
                note, orphansAnchored, held, items, leavingSet);
    }

    /** A result whose orphans weren't limited to anchored entries. */
    public SyncVerifyResult(int sourceMembers, int targetEntries, int inSync, int missingOnTarget,
                            int orphanOnTarget, int contentMismatches, List<String> sampleMissing,
                            List<String> sampleOrphans, List<String> sampleMismatches,
                            boolean sourceComplete, boolean targetComplete, String note) {
        this(sourceMembers, targetEntries, inSync, missingOnTarget, orphanOnTarget, contentMismatches,
                sampleMissing, sampleOrphans, sampleMismatches, sourceComplete, targetComplete, note, false, null);
    }
}
