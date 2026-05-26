// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.discovery;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Full discovery proposal returned by the scan endpoint.
 */
public record DiscoveryProposalResponse(
        UUID directoryId,
        List<ProposedProfile> profiles,
        List<DiscoveredGroupOU> groupOUs,
        List<DiscoveredGroup> groups,
        List<String> warnings
) {

    // ── Proposed profile (one per discovered user OU) ────────────────────

    public record ProposedProfile(
            String name,
            String targetOuDn,
            List<String> objectClasses,
            String rdnAttribute,
            List<InferredAttributeConfig> attributeConfigs,
            List<DiscoveredGroupLink> groupCandidates,
            int estimatedUserCount,
            boolean alreadyConfigured
    ) {}

    // ── Inferred attribute config ────────────────────────────────────────

    public record InferredAttributeConfig(
            String attributeName,
            String suggestedLabel,
            String inputType,
            boolean requiredOnCreate,
            boolean hidden,
            boolean multiValued,
            String syntaxOid
    ) {}

    // ── Group-related records ────────────────────────────────────────────

    public record DiscoveredGroupOU(
            String dn,
            String name,
            int groupCount
    ) {}

    public record DiscoveredGroup(
            String dn,
            String cn,
            String memberAttribute,
            int memberCount
    ) {}

    public record DiscoveredGroupLink(
            String groupDn,
            String groupCn,
            String memberAttribute,
            int overlapCount,
            double overlapPercent
    ) {}
}
