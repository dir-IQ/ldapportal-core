// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.SyncTransformRule;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.entity.enums.SyncLoggingLevel;
import com.ldapportal.entity.enums.SyncScope;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response view of a {@link SyncSet}. */
public record SyncSetResponse(
        UUID id,
        UUID linkId,
        String name,
        String objectScopeBaseDn,
        SyncScope objectScope,
        String identityKey,
        String targetBaseDn,
        String applicabilityFilter,
        String referenceAttributes,
        String sourceAnchorAttribute,
        SyncDeletePolicy deletePolicy,
        List<SyncTransformRule> transformRules,
        // Excluded-attribute override; null => engine defaults are in effect.
        List<String> excludedAttributes,
        Long reconcileCadenceSeconds,
        OffsetDateTime reconcileLastRunAt,
        boolean enabled,
        SyncLoggingLevel loggingLevel,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Long version,
        // Membership counts keyed by state name (APPLIED/PENDING/FAILED/REVIEW);
        // empty for single-set responses, populated by the list endpoint.
        Map<String, Long> stateCounts,
        // The last complete content verify's counts (the dashboard's drift snapshot).
        VerifySnapshot verify) {

    /**
     * The stored drift snapshot: the last complete content verify ({@code at}
     * null when never verified) and, when a newer verify didn't finish reading
     * both directories, when that was ({@code incompleteAt}).
     */
    public record VerifySnapshot(OffsetDateTime at, Integer missing, Integer orphan, Integer mismatch,
                                 OffsetDateTime incompleteAt) {
    }

    public static SyncSetResponse of(SyncSet s) {
        return of(s, Map.of());
    }

    public static SyncSetResponse of(SyncSet s, Map<String, Long> stateCounts) {
        return new SyncSetResponse(s.getId(), s.getLinkId(), s.getName(), s.getObjectScopeBaseDn(),
                s.getObjectScope(), s.getIdentityKey(), s.getTargetBaseDn(), s.getApplicabilityFilter(),
                s.getReferenceAttributes(), s.getSourceAnchorAttribute(), s.getDeletePolicy(),
                s.getTransformRules(), s.getExcludedAttributes(),
                s.getReconcileCadenceSeconds(), s.getReconcileLastRunAt(),
                s.isEnabled(), s.getLoggingLevel(), s.getCreatedAt(), s.getUpdatedAt(), s.getVersion(),
                stateCounts == null ? Map.of() : stateCounts,
                new VerifySnapshot(s.getLastVerifiedAt(), s.getVerifyMissingCount(), s.getVerifyOrphanCount(),
                        s.getVerifyMismatchCount(), s.getVerifyIncompleteAt()));
    }
}
