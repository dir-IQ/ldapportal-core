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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
        /** Source DNs per query for the rows the sync recorded. */
        static final int ROW_BATCH = 500;

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
            return candidatesInBulk(untranslated, dns -> {
                Set<String> found = new HashSet<>();
                for (String dn : dns) {
                    if (targetExists.test(dn)) {
                        found.add(dn);
                    }
                }
                return found;
            }, leaving);
        }

        /**
         * As {@link #candidates(Map, Predicate, Predicate)}, with every lookup
         * made in bulk: one query (per {@link #ROW_BATCH} DNs) for the rows the
         * sync recorded, and one call to {@code existing} for the target DNs
         * whose existence decides, which returns those of them an entry exists at.
         */
        public Map<String, Map<String, String>> candidatesInBulk(Map<String, List<String>> untranslated,
                                                                 Function<Set<String>, Set<String>> existing,
                                                                 Predicate<String> leaving) {
            if (untranslated.isEmpty()) {
                return Map.of();
            }
            Set<String> referents = new LinkedHashSet<>();
            untranslated.values().forEach(dns -> dns.stream().filter(dn -> !leaving.test(dn)).forEach(referents::add));
            Map<String, List<Membership>> rowsBySource = rowsBySource(referents);

            // A DN with nowhere to be kept maps to null; one placed by a set
            // maps to its placement, kept only if the entry exists there.
            Map<String, String> keptAs = new HashMap<>();
            Set<String> placed = new LinkedHashSet<>();
            Set<String> mustExist = new HashSet<>();
            for (String dn : referents) {
                Optional<String> recorded = lastRecordedTargetDn(
                        rowsBySource.getOrDefault(SyncDnUtil.normalize(dn), List.of()));
                if (recorded.isPresent()) {
                    keptAs.put(dn, recorded.get());
                    continue;
                }
                SyncSet owner = sameTarget.stream().filter(s -> SyncScopes.inScope(s, dn)).findFirst().orElse(null);
                if (owner == null) {
                    keptAs.put(dn, dn);
                    continue;
                }
                String target = SyncPlacement.targetDn(owner, dn);
                keptAs.put(dn, target);
                if (target != null) {
                    placed.add(target);
                    mustExist.add(dn);
                }
            }
            Set<String> found = placed.isEmpty() ? Set.of() : existing.apply(placed);

            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            untranslated.forEach((attr, dns) -> {
                Map<String, String> bySource = new LinkedHashMap<>();
                for (String dn : new LinkedHashSet<>(dns)) {
                    String target = keptAs.get(dn);
                    if (target != null && (!mustExist.contains(dn) || found.contains(target))) {
                        bySource.put(dn, target);
                    }
                }
                if (!bySource.isEmpty()) {
                    out.put(attr, bySource);
                }
            });
            return out;
        }

        /** The sync's rows for each (normalized) source DN among {@code dns}. */
        private Map<String, List<Membership>> rowsBySource(Set<String> dns) {
            List<String> normalized = dns.stream().map(SyncDnUtil::normalize).distinct().toList();
            Map<String, List<Membership>> out = new HashMap<>();
            for (int from = 0; from < normalized.size(); from += ROW_BATCH) {
                for (Membership m : membershipRepo.findAllBySourceDnIn(
                        normalized.subList(from, Math.min(normalized.size(), from + ROW_BATCH)))) {
                    out.computeIfAbsent(m.getSourceDn(), k -> new ArrayList<>()).add(m);
                }
            }
            return out;
        }

        /** The target DN one of a source DN's {@code rows} names: the own link's first, else one all sets agree on. */
        private Optional<String> lastRecordedTargetDn(List<Membership> rows) {
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
