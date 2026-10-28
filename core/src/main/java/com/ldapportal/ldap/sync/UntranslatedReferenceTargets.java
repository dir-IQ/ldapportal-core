// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * For a reference value that doesn't translate to a target DN (see
 * {@link MembershipFunction}), the target DN a referrer may keep it as — when
 * the target already holds exactly that value:
 *
 * <ol>
 *   <li><b>The DN the sync last recorded for the referent</b>, in any state.
 *       A user whose last write failed, or one placed under a rewritten target
 *       base, keeps its group memberships at the DN the target knows it by.</li>
 *   <li><b>Otherwise, a referent inside a source scope a set on this target
 *       syncs</b> is the sync's to place: kept at its planned target DN only
 *       while an entry exists there. One the sync deleted (it left the set's
 *       filter) is gone, so its memberships go too, rather than dangle.</li>
 *   <li><b>Otherwise</b> the sync knows nothing about it (an entry outside every
 *       set's scope, or a value that names no entry at all): kept as the DN it
 *       is written as.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class UntranslatedReferenceTargets {

    private final SyncLinkRepository linkRepo;
    private final SyncSetRepository setRepo;
    private final MembershipRepository membershipRepo;

    /**
     * The lookup for referrers in {@code set}: rows of the set's own link are
     * preferred, then rows of every set writing to the same target directory.
     */
    public Lookup forSet(SyncSet set) {
        SyncLink link = linkRepo.findById(set.getLinkId()).orElse(null);
        Set<UUID> ownSets = new HashSet<>();
        List<SyncSet> sameTarget = new ArrayList<>();
        if (link != null) {
            for (SyncLink l : linkRepo.findAllByTargetDirId(link.getTargetDirId())) {
                for (SyncSet s : setRepo.findAllByLinkId(l.getId())) {
                    sameTarget.add(s);
                    if (l.getId().equals(link.getId())) {
                        ownSets.add(s.getId());
                    }
                }
            }
        } else {
            sameTarget.add(set);
            ownSets.add(set.getId());
        }
        return new Lookup(ownSets, sameTarget, membershipRepo);
    }

    /** Resolves untranslated source DNs for one referrer set; holds its sets, not its results. */
    public static final class Lookup {
        private final Set<UUID> ownSets;
        private final List<SyncSet> sameTarget;
        private final Set<UUID> sameTargetIds = new HashSet<>();
        private final MembershipRepository membershipRepo;

        Lookup(Set<UUID> ownSets, List<SyncSet> sameTarget, MembershipRepository membershipRepo) {
            this.ownSets = ownSets;
            this.sameTarget = sameTarget;
            this.membershipRepo = membershipRepo;
            for (SyncSet s : sameTarget) {
                sameTargetIds.add(s.getId());
            }
        }

        /**
         * Per attribute, each untranslated source DN mapped to the target DN to
         * keep it as (source DNs with nowhere to be kept are left out).
         *
         * @param targetExists whether an entry exists at a target DN
         */
        public Map<String, Map<String, String>> candidates(Map<String, List<String>> untranslated,
                                                           Predicate<String> targetExists) {
            return candidates(untranslated, targetExists, dn -> false);
        }

        /**
         * As {@link #candidates(Map, Predicate)}; a referent for which
         * {@code leaving} holds (a preview's plan deletes its target entry) has
         * nowhere to be kept, as once the delete has run.
         */
        public Map<String, Map<String, String>> candidates(Map<String, List<String>> untranslated,
                                                           Predicate<String> targetExists,
                                                           Predicate<String> leaving) {
            if (untranslated.isEmpty()) {
                return Map.of();
            }
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            untranslated.forEach((attr, dns) -> {
                Map<String, String> bySource = new LinkedHashMap<>();
                for (String dn : new LinkedHashSet<>(dns)) {
                    if (!leaving.test(dn)) {
                        candidate(dn, targetExists).ifPresent(t -> bySource.put(dn, t));
                    }
                }
                if (!bySource.isEmpty()) {
                    out.put(attr, bySource);
                }
            });
            return out;
        }

        Optional<String> candidate(String sourceDn, Predicate<String> targetExists) {
            Optional<String> recorded = lastRecordedTargetDn(sourceDn);
            if (recorded.isPresent()) {
                return recorded;
            }
            for (SyncSet s : sameTarget) {
                if (SyncScopes.inScope(s, sourceDn)) {
                    String placed = SyncPlacement.targetDn(s, sourceDn);
                    return placed != null && targetExists.test(placed) ? Optional.of(placed) : Optional.empty();
                }
            }
            return Optional.of(sourceDn);
        }

        /** The target DN a row for this source DN names: the own link's first, else one all sets agree on. */
        private Optional<String> lastRecordedTargetDn(String sourceDn) {
            List<Membership> rows = membershipRepo.findAllBySourceDn(SyncDnUtil.normalize(sourceDn));
            Map<String, String> others = new LinkedHashMap<>();
            for (Membership m : rows) {
                if (m.getTargetDn() == null || !sameTargetIds.contains(m.getSyncSetId())) {
                    continue;
                }
                if (ownSets.contains(m.getSyncSetId())) {
                    return Optional.of(m.getTargetDn());
                }
                others.putIfAbsent(SyncDnUtil.normalize(m.getTargetDn()), m.getTargetDn());
            }
            return others.size() == 1 ? Optional.of(others.values().iterator().next()) : Optional.empty();
        }
    }
}
