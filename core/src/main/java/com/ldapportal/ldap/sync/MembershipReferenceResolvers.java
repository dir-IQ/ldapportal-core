// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the {@link ReferenceResolver} the engine hands to
 * {@link MembershipFunction} for one link: source DN → target DN through the
 * {@link Membership} index.
 *
 * <p>Resolution looks <em>across links</em>, not only within the link being
 * recomputed. A referenced entry is often synced by a different link than the
 * referrer: an IVIA {@code secUser} under {@code c=admin} carries a {@code secDN}
 * pointing into {@code c=us}, and OUD exposes each of those suffixes as its own
 * directory connection, so they are mirrored by two separate links. Order of
 * preference:
 * <ol>
 *   <li>a row in one of <em>this</em> link's sets (the referrer's own link);</li>
 *   <li>otherwise the rows of every other link — accepted only when they all
 *       agree on one target DN. Disagreement (the same source DN mirrored to
 *       different targets by different links) is ambiguous; the value is
 *       dropped, exactly as an unsynced referent is, and logged.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipReferenceResolvers {

    private final SyncSetRepository syncSetRepo;
    private final MembershipRepository membershipRepo;

    public ReferenceResolver forLink(SyncLink link) {
        Set<UUID> ownSets = syncSetRepo.findAllByLinkId(link.getId()).stream()
                .map(SyncSet::getId)
                .collect(Collectors.toSet());
        return srcDn -> resolve(ownSets, srcDn);
    }

    private Optional<String> resolve(Set<UUID> ownSets, String srcDn) {
        String norm = SyncDnUtil.normalize(srcDn);
        List<Membership> rows = membershipRepo.findAllBySourceDn(norm);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        // Same-link rows win: they are the referrer's own projection space.
        for (Membership m : rows) {
            if (m.getTargetDn() != null && ownSets.contains(m.getSyncSetId())) {
                return Optional.of(m.getTargetDn());
            }
        }
        // Cross-link: every other link must agree on the target DN.
        Map<String, String> distinct = new LinkedHashMap<>();
        for (Membership m : rows) {
            if (m.getTargetDn() != null) {
                distinct.putIfAbsent(SyncDnUtil.normalize(m.getTargetDn()), m.getTargetDn());
            }
        }
        if (distinct.size() == 1) {
            return Optional.of(distinct.values().iterator().next());
        }
        if (distinct.size() > 1) {
            log.warn("Reference {} is mirrored to {} different target DNs by other sync links; "
                    + "dropping the value as ambiguous", srcDn, distinct.size());
        }
        return Optional.empty();
    }
}
