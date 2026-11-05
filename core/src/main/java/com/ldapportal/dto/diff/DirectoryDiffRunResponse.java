// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.diff;

import com.ldapportal.entity.enums.DirectoryDiffPairing;
import com.ldapportal.entity.enums.DirectoryDiffPhase;
import com.ldapportal.entity.enums.DirectoryDiffStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A directory diff run: what it compared, how far it has got, and how many
 * entries landed in each group.
 *
 * @param processed       entries read (reading phases) or accounted for (comparing)
 * @param total           entries to account for, once both sides are read
 * @param attributeCounts for the Different group, each attribute and how many entries it differs in, most first
 */
public record DirectoryDiffRunResponse(UUID id, DirectoryDiffStatus status, DirectoryDiffPhase phase,
                                       int processed, int total, String currentDn, String error,
                                       String requestedBy, OffsetDateTime startedAt, OffsetDateTime heartbeatAt,
                                       OffsetDateTime finishedAt, Side a, Side b, DirectoryDiffPairing pairBy,
                                       String pairAttribute, List<String> attributes, List<String> ignore,
                                       Counts counts, List<AttributeCount> attributeCounts) {

    /** One side as compared; the directory's name as it was then. */
    public record Side(UUID directoryId, String directoryName, String baseDn, String scope, String filter) {
    }

    public record Counts(int same, int different, int onlyA, int onlyB) {
    }

    public record AttributeCount(String name, int count) {
    }
}
