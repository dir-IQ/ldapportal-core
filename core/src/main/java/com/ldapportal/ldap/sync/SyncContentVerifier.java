// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncDriftItem;
import com.ldapportal.dto.sync.SyncVerifyResult;
import com.ldapportal.dto.sync.SyncHeldReference;
import com.ldapportal.entity.enums.SyncHeldStatus;
import com.ldapportal.entity.enums.SyncDriftCategory;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.SyncVerifyPhase;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.ldapportal.ldap.sync.identity.IdentityStrategyRegistry;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Independent content verification for a sync set — a belts-and-suspenders check
 * that re-reads both directories and compares them directly, without trusting the
 * membership index (unlike {@link MembershipReconciler#preview}, which plans
 * against the index).
 *
 * <p>The source scope (object-scope base DN + applicability filter) is enumerated
 * and projected through the same {@link MembershipFunction} the engine uses, so a
 * member's expected target DN and desired attributes are exactly what a real
 * apply would produce. Those expectations are then matched by target DN against
 * the entries actually present under the target base DN (same applicability
 * filter), and the comparison flags three kinds of disagreement:
 *
 * <ul>
 *   <li><b>missing</b> — a member with no entry at its expected target DN,</li>
 *   <li><b>orphan</b> — a target entry with no corresponding source member. When
 *       the set writes a source anchor, only target entries carrying it count,
 *       less those another set's index places at that DN under that anchor
 *       value: an entry the set doesn't manage (another set's, or one never
 *       synced) under the same target base isn't this set's drift,</li>
 *   <li><b>mismatch</b> — present on both sides, but the target's attributes
 *       differ from the projected desired state (the same diff the engine's
 *       MODIFY uses — see {@link TargetEntryDiffer}).</li>
 * </ul>
 *
 * <p>DN-valued reference attributes (e.g. {@code member}, {@code uniqueMember},
 * {@code secDN}) are remapped through the membership index during projection
 * exactly as the engine does — so the desired image matches what a real apply
 * produced and a correctly-synced entry verifies as in-sync rather than showing
 * spurious drift. Presence (missing/orphan) is still index-independent; only the
 * <em>value</em> of reference attributes consults the index mapping.
 *
 * <p>This never writes; it is purely a read-side consistency report.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncContentVerifier {

    /** Cap on how many sample DNs each mismatch category returns. */
    static final int SAMPLE_LIMIT = 25;

    /** Values kept per side of one differing attribute in a drift item (a big group's members). */
    static final int VALUE_LIMIT = 50;

    /** Attributes whose values a drift item never shows (password material). */
    static final Set<String> SECRET_ATTRIBUTES = com.ldapportal.ldap.diff.DirectoryDiffEngine.SECRET_ATTRIBUTES;

    private final SyncSetRepository syncSetRepo;
    private final SyncLinkRepository syncLinkRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final MembershipRepository membershipRepo;
    private final IdentityStrategyRegistry identityStrategies;
    private final LdapConnectionFactory connectionFactory;
    private final MembershipFunction membershipFunction;
    private final SyncTargetSchemas targetSchemas;
    private final MembershipReferenceResolvers referenceResolvers;
    private final UntranslatedReferenceTargets untranslatedTargets;
    private final SyncVerifyProgressTracker progress;

    /**
     * Verify the live source/target contents of a sync set and report
     * mismatches, recording its progress as it goes ({@link SyncVerifyProgressTracker}).
     */
    public SyncVerifyResult verify(UUID syncSetId) {
        Context ctx = loadContext(syncSetId);
        if (ctx == null) {
            return unavailable("Sync set, link, or source/target directory is unavailable or disabled.");
        }
        SyncVerifyProgressTracker.Handle tracking = progress.start(syncSetId);
        try {
            SyncVerifyResult result = verify(ctx, tracking);
            tracking.complete();
            return result;
        } catch (RuntimeException ex) {
            String msg = ex.getMessage();
            tracking.fail(msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg);
            throw ex;
        }
    }

    private SyncVerifyResult verify(Context ctx, SyncVerifyProgressTracker.Handle tracking) {
        UUID syncSetId = ctx.set.getId();

        // Source side: enumerate + project members, keyed by normalized target DN.
        // Reference attributes resolve through the index exactly as the engine does,
        // so the desired image matches what was actually applied (see class doc).
        ReferenceResolver resolver = referenceResolvers.snapshot(ctx.link);
        Map<String, MembershipDecision> members = new LinkedHashMap<>();
        Map<String, String> sourceDns = new HashMap<>();
        boolean sourceComplete = true;
        try {
            int read = 0;
            for (SearchResultEntry e : enumerateSource(ctx)) {
                var decision = membershipFunction.evaluate(ctx.set, ctx.strategy, e, resolver);
                if (decision.member() && decision.targetDn() != null) {
                    members.put(normDn(decision.targetDn()), decision);
                    sourceDns.put(normDn(decision.targetDn()), e.getDN());
                }
                tracking.processed(++read);
            }
        } catch (Exception ex) {
            log.warn("Sync set {}: source enumeration failed during verify ({})", syncSetId, ex.toString());
            sourceComplete = false;
        }

        // Target side: enumerate entries actually present, keyed by normalized DN.
        Map<String, Entry> actual = new LinkedHashMap<>();
        boolean targetComplete = true;
        tracking.phase(SyncVerifyPhase.TARGET);
        try {
            for (SearchResultEntry e : enumerateTarget(ctx)) {
                actual.put(normDn(e.getDN()), e);
            }
            tracking.processed(actual.size());
        } catch (Exception ex) {
            log.warn("Sync set {}: target enumeration failed during verify ({})", syncSetId, ex.toString());
            targetComplete = false;
        }

        // Untranslated references are kept as the engine keeps them, which can
        // depend on whether an entry exists on the target (see
        // UntranslatedReferenceTargets): check the enumerated entries first,
        // then read the target for one outside them.
        UntranslatedReferenceTargets.Lookup lookup = untranslatedTargets.forSet(ctx.set);
        Map<String, Boolean> exists = new HashMap<>();
        java.util.function.Predicate<String> targetExists = dn -> actual.containsKey(normDn(dn))
                || exists.computeIfAbsent(normDn(dn), k -> readExists(ctx, dn));
        Map<String, Expected> expected = new LinkedHashMap<>();
        members.forEach((key, d) -> expected.put(key, new Expected(d.targetDn(), d.desiredAttrs(),
                d.heldAttributeNames(), lookup.candidates(d.unresolvedReferences(), targetExists),
                sourceDns.get(key), d.identity())));

        Set<String> excluded = SyncExcludedAttributes.effectiveFor(ctx.set);
        tracking.phase(SyncVerifyPhase.COMPARING);
        tracking.total(expected.size());
        String anchorAttr = anchorAttribute(ctx.set);
        SyncVerifyResult result = compare(expected, actual, excluded, sourceComplete, targetComplete,
                targetSchemas.forDirectory(ctx.target), SyncReferenceAttributes.forSet(ctx.set), (n, dn) -> {
                    tracking.processed(n);
                    tracking.current(dn);
                }, anchorAttr, owners(syncSetId, expected, actual, anchorAttr));
        tracking.processed(expected.size());
        return result.withHeldReferences(heldReferencesOnSource(ctx, members, sourceDns));
    }

    /** What a live re-check of one source member found. */
    public enum RecheckState {
        /** The target entry matches the projected source. */
        IN_SYNC,
        /** No entry at the member's target DN. */
        MISSING,
        /** The target entry's attributes differ from the projected source. */
        MISMATCH,
        /** The source entry no longer belongs to the set (filter, scope or placement). */
        NOT_MEMBER,
        /** The source entry no longer exists. */
        SOURCE_GONE,
        /** The set, its link or a directory is unavailable, or a read failed. */
        UNAVAILABLE
    }

    /**
     * One source member re-checked live: its state, identity, target DN and,
     * for a MISMATCH, the attributes that differ ({@link #attributeDiffs}).
     */
    public record Recheck(String sourceDn, RecheckState state, String identity, String targetDn,
                          List<SyncDriftItem.AttributeDiff> attributes, String note) {
    }

    /**
     * Re-check the given source members against the target right now, exactly
     * as a full verify would compare them ({@link #compare}, one entry at a
     * time): each source entry is read and projected, and the entry at its
     * target DN read and diffed. The set and its directories are loaded once.
     * Never writes.
     *
     * @return one result per source DN, in the order given
     */
    public List<Recheck> recheck(UUID syncSetId, List<String> sourceDns) {
        Context ctx = loadContext(syncSetId);
        List<Recheck> out = new ArrayList<>(sourceDns.size());
        if (ctx == null) {
            for (String dn : sourceDns) {
                out.add(new Recheck(dn, RecheckState.UNAVAILABLE, null, null, List.of(),
                        "Sync set, link, or source/target directory is unavailable or disabled."));
            }
            return out;
        }
        ReferenceResolver resolver = referenceResolvers.snapshot(ctx.link);
        UntranslatedReferenceTargets.Lookup lookup = untranslatedTargets.forSet(ctx.set);
        Set<String> excluded = SyncExcludedAttributes.effectiveFor(ctx.set);
        var schema = targetSchemas.forDirectory(ctx.target);
        List<String> dnAttrs = SyncReferenceAttributes.forSet(ctx.set);
        String idAttr = SyncIdentity.attribute(ctx.set, ctx.strategy);
        String anchor = anchorAttribute(ctx.set);
        String[] targetAttrs = anchor == null ? new String[] {"*"} : new String[] {"*", anchor};
        for (String sourceDn : sourceDns) {
            try {
                SearchResultEntry src = connectionFactory.withConnectionUnreplicated(ctx.source,
                        conn -> conn.getEntry(sourceDn, "*", idAttr));
                if (src == null) {
                    out.add(new Recheck(sourceDn, RecheckState.SOURCE_GONE, null, null, List.of(),
                            "The source entry no longer exists."));
                    continue;
                }
                MembershipDecision d = membershipFunction.evaluate(ctx.set, ctx.strategy, src, resolver);
                if (!d.member() || d.targetDn() == null) {
                    out.add(new Recheck(sourceDn, RecheckState.NOT_MEMBER, d.identity(), null, List.of(),
                            "The source entry no longer belongs to this set."));
                    continue;
                }
                Entry target = connectionFactory.withConnectionUnreplicated(ctx.target,
                        conn -> conn.getEntry(d.targetDn(), targetAttrs));
                java.util.function.Predicate<String> targetExists = dn -> readExists(ctx, dn);
                Map<String, Expected> expected = new LinkedHashMap<>();
                String key = normDn(d.targetDn());
                expected.put(key, new Expected(d.targetDn(), d.desiredAttrs(), d.heldAttributeNames(),
                        lookup.candidates(d.unresolvedReferences(), targetExists), sourceDn, d.identity()));
                Map<String, Entry> actual = target == null ? Map.of() : Map.of(key, target);
                SyncVerifyResult r = compare(expected, actual, excluded, true, true, schema, dnAttrs,
                        CompareProgress.NONE, null, Owners.NONE);
                SyncDriftItem item = r.items().isEmpty() ? null : r.items().get(0);
                RecheckState state = item == null ? RecheckState.IN_SYNC
                        : item.category() == SyncDriftCategory.MISSING ? RecheckState.MISSING : RecheckState.MISMATCH;
                out.add(new Recheck(sourceDn, state, d.identity(), d.targetDn(),
                        item == null ? List.of() : item.attributes(), null));
            } catch (Exception ex) {
                log.warn("Sync set {}: re-check of [{}] failed ({})", syncSetId, sourceDn, ex.toString());
                String msg = ex.getMessage();
                out.add(new Recheck(sourceDn, RecheckState.UNAVAILABLE, null, null, List.of(),
                        "Couldn't read the entry: " + (msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg)));
            }
        }
        return out;
    }

    /**
     * {@link #heldReferences} with the referenced DNs looked up on the source over
     * one pooled connection rather than one borrow per DN. A DN whose read fails,
     * or every DN if the source can't be reached, is left unchecked rather than
     * guessed at.
     */
    private SyncVerifyResult.HeldReferences heldReferencesOnSource(Context ctx,
                                                                   Map<String, MembershipDecision> members,
                                                                   Map<String, String> sourceDns) {
        try {
            return connectionFactory.withConnectionUnreplicated(ctx.source, conn -> {
                Map<String, Boolean> onSource = new HashMap<>();
                return heldReferences(members, sourceDns, dn -> {
                    String key = normDn(dn);
                    if (onSource.containsKey(key)) {
                        return onSource.get(key);
                    }
                    Boolean found;
                    try {
                        found = conn.getEntry(dn, "1.1") != null;
                    } catch (Exception ex) {
                        found = null; // a failed read isn't evidence either way
                    }
                    onSource.put(key, found);
                    return found;
                });
            });
        } catch (Exception ex) {
            log.warn("Sync set {}: couldn't look up held references on the source ({})",
                    ctx.set.getId(), ex.toString());
            return heldReferences(members, sourceDns, dn -> null);
        }
    }

    /**
     * At most this many distinct referenced DNs are looked up on the source per
     * verify (one base-scope read each, over one connection); the rest are
     * stored as {@link SyncHeldStatus#UNCHECKED}.
     */
    static final int HELD_CHECK_LIMIT = 10_000;

    /**
     * Every reference value the sync holds back (it names an entry the sync
     * hasn't placed), with the member holding it and whether the named entry
     * exists on the source, plus the counts: members with one, and members with
     * one naming an entry that doesn't exist on the source at all.
     * {@code existsOnSource} answers true, false, or null when it couldn't tell.
     * Pure apart from {@code existsOnSource} (package-private for direct unit
     * testing).
     *
     * @param members   the set's members, keyed by normalized target DN
     * @param sourceDns each member's source DN, by the same key
     */
    static SyncVerifyResult.HeldReferences heldReferences(Map<String, MembershipDecision> members,
                                                         Map<String, String> sourceDns,
                                                         java.util.function.Function<String, Boolean> existsOnSource) {
        int entries = 0;
        int dangling = 0;
        boolean unchecked = false;
        Set<String> looked = new java.util.HashSet<>();
        List<SyncVerifyResult.HeldSample> samples = new ArrayList<>();
        List<SyncHeldReference> items = new ArrayList<>();
        for (Map.Entry<String, MembershipDecision> m : members.entrySet()) {
            MembershipDecision d = m.getValue();
            Map<String, List<String>> refs = new java.util.LinkedHashMap<>(d.heldReferences());
            d.unresolvedReferences().forEach((attr, dns) -> refs.merge(attr, dns,
                    (x, y) -> { List<String> all = new ArrayList<>(x); all.addAll(y); return all; }));
            if (refs.isEmpty()) {
                continue;
            }
            entries++;
            boolean entryDangling = false;
            String sourceDn = sourceDns.get(m.getKey());
            for (Map.Entry<String, List<String>> ref : refs.entrySet()) {
                for (String dn : ref.getValue()) {
                    Boolean exists = null;
                    if (looked.size() < HELD_CHECK_LIMIT || looked.contains(normDn(dn))) {
                        looked.add(normDn(dn));
                        exists = existsOnSource.apply(dn);
                    }
                    SyncHeldStatus status = exists == null ? SyncHeldStatus.UNCHECKED
                            : exists ? SyncHeldStatus.UNSYNCED : SyncHeldStatus.DANGLING;
                    unchecked |= status == SyncHeldStatus.UNCHECKED;
                    entryDangling |= status == SyncHeldStatus.DANGLING;
                    items.add(new SyncHeldReference(d.targetDn(), sourceDn, d.identity(), ref.getKey(), dn, status));
                    if (samples.size() < SAMPLE_LIMIT) {
                        samples.add(new SyncVerifyResult.HeldSample(d.targetDn(), ref.getKey(), dn,
                                status == SyncHeldStatus.DANGLING));
                    }
                }
            }
            if (entryDangling) {
                dangling++;
            }
        }
        return new SyncVerifyResult.HeldReferences(entries, dangling, unchecked, List.copyOf(samples),
                List.copyOf(items));
    }

    /** Told as the comparison reaches each expected entry: how many came before it, and its DN. */
    @FunctionalInterface
    interface CompareProgress {
        void at(int compared, String dn);

        CompareProgress NONE = (n, dn) -> { };
    }

    /**
     * Pure comparison of projected source members against live target entries
     * (package-private for direct unit testing — needs no LDAP).
     */
    static SyncVerifyResult compare(Map<String, Expected> expected, Map<String, Entry> actual,
                                    Set<String> excluded, boolean sourceComplete, boolean targetComplete) {
        return compare(expected, actual, excluded, sourceComplete, targetComplete, null, List.of());
    }

    /**
     * As above, comparing DN-valued attributes by canonical DN (see
     * {@link TargetEntryDiffer}) — so a reference's form alone isn't drift.
     */
    static SyncVerifyResult compare(Map<String, Expected> expected, Map<String, Entry> actual,
                                    Set<String> excluded, boolean sourceComplete, boolean targetComplete,
                                    com.unboundid.ldap.sdk.schema.Schema schema, List<String> dnAttrs) {
        return compare(expected, actual, excluded, sourceComplete, targetComplete, schema, dnAttrs,
                CompareProgress.NONE, null, Owners.NONE);
    }

    /** Says whether another set manages an anchored entry: (anchor value, entry DN) → true when it does. */
    @FunctionalInterface
    interface ManagedElsewhere {
        boolean test(String anchorValue, String entryDn);
    }

    static final ManagedElsewhere NOT_ELSEWHERE = (v, dn) -> false;

    /**
     * Who the index says placed an anchored target entry: another set
     * ({@code elsewhere}), so it's that set's, or this set ({@code here}), so
     * it's leaving this set (a held delete, or one the next reconcile makes).
     * Either way it isn't this set's orphan.
     */
    record Owners(ManagedElsewhere elsewhere, ManagedElsewhere here) {
        static final Owners NONE = new Owners(NOT_ELSEWHERE, NOT_ELSEWHERE);
    }

    /** Identities per query when looking up who manages the orphan candidates. */
    static final int IDENTITY_BATCH = 500;

    /**
     * For the anchored target entries this set can't account for: whether
     * another set's index places that anchor value at that very DN, so the entry
     * is that set's, not this one's orphan (several sets may write their anchor
     * into the same attribute under one target base). One this set's own index
     * still places there is leaving the set, not an orphan either.
     */
    private Owners owners(UUID syncSetId, Map<String, Expected> expected,
                          Map<String, Entry> actual, String anchorAttr) {
        if (anchorAttr == null) {
            return Owners.NONE;
        }
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Entry> en : actual.entrySet()) {
            if (!expected.containsKey(en.getKey())) {
                String[] v = en.getValue().getAttributeValues(anchorAttr);
                if (v != null) {
                    values.addAll(java.util.Arrays.asList(v));
                }
            }
        }
        if (values.isEmpty()) {
            return Owners.NONE;
        }
        // identity -> normalized target DNs other sets (elsewhere) or this set (here) place it at
        Map<String, Set<String>> elsewhere = new HashMap<>();
        Map<String, Set<String>> here = new HashMap<>();
        List<String> all = new ArrayList<>(values);
        for (int from = 0; from < all.size(); from += IDENTITY_BATCH) {
            for (com.ldapportal.entity.Membership m
                    : membershipRepo.findAllByIdentityIn(all.subList(from, Math.min(all.size(), from + IDENTITY_BATCH)))) {
                if (m.getTargetDn() != null) {
                    (syncSetId.equals(m.getSyncSetId()) ? here : elsewhere)
                            .computeIfAbsent(m.getIdentity(), k -> new java.util.HashSet<>()).add(normDn(m.getTargetDn()));
                }
            }
        }
        return new Owners((value, dn) -> elsewhere.getOrDefault(value, Set.of()).contains(normDn(dn)),
                (value, dn) -> here.getOrDefault(value, Set.of()).contains(normDn(dn)));
    }

    static SyncVerifyResult compare(Map<String, Expected> expected, Map<String, Entry> actual,
                                    Set<String> excluded, boolean sourceComplete, boolean targetComplete,
                                    com.unboundid.ldap.sdk.schema.Schema schema, List<String> dnAttrs,
                                    CompareProgress progress, String anchorAttr,
                                    Owners owners) {
        int inSync = 0;
        int compared = 0;
        List<String> missing = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        List<SyncDriftItem> missingItems = new ArrayList<>();
        List<SyncDriftItem> mismatchItems = new ArrayList<>();
        for (Map.Entry<String, Expected> en : expected.entrySet()) {
            Expected exp = en.getValue();
            progress.at(compared++, exp.displayDn());
            Entry target = actual.get(en.getKey());
            if (target == null) {
                missing.add(exp.displayDn());
                missingItems.add(new SyncDriftItem(SyncDriftCategory.MISSING, exp.displayDn(), exp.sourceDn(),
                        exp.identity(), List.of()));
                continue;
            }
            // A held reference (its referent isn't synced yet) is kept on the
            // target by the engine, so it isn't drift either.
            Set<String> untouched = en.getValue().held().isEmpty() ? excluded : union(excluded, en.getValue().held());
            // An untranslated reference the target already has exactly is kept by
            // the engine (a member whose entry isn't synced yet), so not drift.
            List<Attribute> desired = TargetEntryDiffer.keepUnresolved(en.getValue().desired(),
                    en.getValue().keepCandidates(), target).desired();
            List<Modification> mods = TargetEntryDiffer.diff(target, desired, untouched, schema, dnAttrs);
            if (mods.isEmpty()) {
                inSync++;
            } else {
                mismatches.add(exp.displayDn());
                mismatchItems.add(new SyncDriftItem(SyncDriftCategory.MISMATCH, target.getDN(), exp.sourceDn(),
                        exp.identity(), attributeDiffs(mods, desired, target, schema, dnAttrs)));
            }
        }

        // With an anchor, only entries carrying it are the set's to account for:
        // an unanchored entry under the target base is someone else's.
        List<String> orphans = new ArrayList<>();
        List<SyncDriftItem> orphanItems = new ArrayList<>();
        int leaving = 0;
        for (Map.Entry<String, Entry> en : actual.entrySet()) {
            if (expected.containsKey(en.getKey())) {
                continue;
            }
            if (anchorAttr != null) {
                if (!isOwnOrphan(en.getValue(), anchorAttr, owners.elsewhere())) {
                    continue;
                }
                // Still in this set's own index there: leaving the set (a delete
                // held for review, or the next reconcile's), not unaccounted for.
                if (placedBy(en.getValue(), anchorAttr, owners.here())) {
                    leaving++;
                    continue;
                }
            }
            orphans.add(en.getValue().getDN());
            orphanItems.add(new SyncDriftItem(SyncDriftCategory.ORPHAN, en.getValue().getDN(), null, null,
                    List.of()));
        }

        List<SyncDriftItem> items = new ArrayList<>(missingItems.size() + orphanItems.size() + mismatchItems.size());
        items.addAll(missingItems);
        items.addAll(orphanItems);
        items.addAll(mismatchItems);
        return new SyncVerifyResult(
                expected.size(), actual.size(), inSync,
                missing.size(), orphans.size(), mismatches.size(),
                sample(missing), sample(orphans), sample(mismatches),
                sourceComplete, targetComplete, null, anchorAttr != null, null, items, leaving);
    }

    /**
     * One {@link SyncDriftItem.AttributeDiff} per attribute the engine would
     * modify: what the projected source wants against what the target has.
     * Password material shows no values; each side keeps at most
     * {@link #VALUE_LIMIT} values, and a binary value shows as its size.
     * Values only one side has are flagged and listed first (see
     * {@link SyncDriftItem.AttributeDiff}).
     */
    static List<SyncDriftItem.AttributeDiff> attributeDiffs(List<Modification> mods, List<Attribute> desired,
                                                            Entry target) {
        return attributeDiffs(mods, desired, target, null, List.of());
    }

    static List<SyncDriftItem.AttributeDiff> attributeDiffs(List<Modification> mods, List<Attribute> desired,
                                                            Entry target, com.unboundid.ldap.sdk.schema.Schema schema,
                                                            List<String> dnAttrs) {
        Map<String, SyncDriftItem.AttributeDiff> out = new LinkedHashMap<>();
        for (Modification m : mods) {
            String name = m.getAttributeName();
            String key = name.toLowerCase(Locale.ROOT);
            if (out.containsKey(key)) {
                continue;
            }
            int semi = key.indexOf(';');
            boolean hidden = SECRET_ATTRIBUTES.contains(semi < 0 ? key : key.substring(0, semi));
            if (hidden) {
                out.put(key, new SyncDriftItem.AttributeDiff(name, List.of(), List.of(), true, false,
                        List.of(), List.of()));
                continue;
            }
            Attribute wantAttr = desired.stream().filter(a -> a.getName().equalsIgnoreCase(name)).findFirst()
                    .orElse(null);
            Attribute haveAttr = target.getAttribute(name);
            com.unboundid.ldap.matchingrules.MatchingRule rule =
                    TargetEntryDiffer.matchingRule(Attribute.getBaseName(name), schema, dnAttrs);
            // The value naming the entry compares as the directory matches it (see TargetEntryDiffer).
            TargetEntryDiffer.RdnValue rdn = rule == null ? TargetEntryDiffer.RdnValue.of(target, name, schema) : null;
            Sided want = sided(wantAttr, haveAttr, rule, rdn);
            Sided have = sided(haveAttr, wantAttr, rule, rdn);
            boolean truncated = want.values().size() > VALUE_LIMIT || have.values().size() > VALUE_LIMIT;
            out.put(key, new SyncDriftItem.AttributeDiff(name, limit(want.values()), limit(have.values()),
                    false, truncated, onlyIndexes(want.only()), onlyIndexes(have.only())));
        }
        return List.copyOf(out.values());
    }

    /** One side's display values, the ones the other side lacks first, and how many those are. */
    private record Sided(List<String> values, int only) {
    }

    private static Sided sided(Attribute side, Attribute other, com.unboundid.ldap.matchingrules.MatchingRule rule,
                               TargetEntryDiffer.RdnValue rdn) {
        if (side == null || !side.hasValue()) {
            return new Sided(List.of(), 0);
        }
        Set<String> otherKeys = new java.util.HashSet<>();
        if (other != null) {
            for (byte[] raw : other.getValueByteArrays()) {
                otherKeys.add(valueKey(raw, rule, rdn));
            }
        }
        List<String> only = new ArrayList<>();
        List<String> shared = new ArrayList<>();
        for (byte[] raw : side.getValueByteArrays()) {
            (otherKeys.contains(valueKey(raw, rule, rdn)) ? shared : only).add(display(raw));
        }
        List<String> all = new ArrayList<>(only.size() + shared.size());
        all.addAll(only);
        all.addAll(shared);
        return new Sided(all, only.size());
    }

    /**
     * The value as the sync compares it: normalized by the rule when it has one
     * and can, the RDN value whatever its case, else exact.
     */
    private static String valueKey(byte[] raw, com.unboundid.ldap.matchingrules.MatchingRule rule,
                                   TargetEntryDiffer.RdnValue rdn) {
        if (rdn != null && rdn.matches(raw)) {
            return "rdn";
        }
        if (rule != null) {
            try {
                return "n:" + rule.normalize(new com.unboundid.asn1.ASN1OctetString(raw)).stringValue();
            } catch (com.unboundid.ldap.sdk.LDAPException ex) {
                // not normalizable (e.g. not a valid DN): compare exactly
            }
        }
        return "x:" + new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static List<Integer> onlyIndexes(int only) {
        int n = Math.min(only, VALUE_LIMIT);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    private static String display(byte[] raw) {
        return printable(raw) ? new String(raw, java.nio.charset.StandardCharsets.UTF_8)
                : "(binary, " + raw.length + " bytes)";
    }

    private static boolean printable(byte[] raw) {
        java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
        try {
            String s = dec.decode(java.nio.ByteBuffer.wrap(raw)).toString();
            return s.chars().noneMatch(c -> c < 0x20 && c != '\t' && c != '\n' && c != '\r');
        } catch (java.nio.charset.CharacterCodingException ex) {
            return false;
        }
    }

    private static List<String> limit(List<String> v) {
        return v.size() <= VALUE_LIMIT ? v : List.copyOf(v.subList(0, VALUE_LIMIT));
    }

    /** Anchored, and not placed at its DN by another set under any of its anchor values. */
    /** Whether {@code index} places the entry at its DN under any of its anchor values. */
    private static boolean placedBy(Entry e, String anchorAttr, ManagedElsewhere index) {
        String[] values = e.getAttributeValues(anchorAttr);
        if (values == null) {
            return false;
        }
        for (String v : values) {
            if (index.test(v, e.getDN())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwnOrphan(Entry e, String anchorAttr, ManagedElsewhere managedElsewhere) {
        String[] values = e.getAttributeValues(anchorAttr);
        if (values == null || values.length == 0) {
            return false;
        }
        for (String v : values) {
            if (managedElsewhere.test(v, e.getDN())) {
                return false;
            }
        }
        return true;
    }

    /** The set's source anchor attribute, or {@code null} when it writes none. */
    static String anchorAttribute(SyncSet set) {
        String a = set.getSourceAnchorAttribute();
        return a == null || a.isBlank() ? null : a.trim();
    }

    // ── LDAP enumeration (no writes) ───────────────────────────────────────────

    private List<SearchResultEntry> enumerateSource(Context ctx) {
        String base = ctx.set.getObjectScopeBaseDn() != null
                ? ctx.set.getObjectScopeBaseDn() : ctx.source.getBaseDn();
        String idAttr = SyncIdentity.attribute(ctx.set, ctx.strategy);
        SearchScope scope = SyncScopes.searchScope(ctx.set);
        int pageSize = ctx.source.getPagingSize();
        return connectionFactory.withConnectionUnreplicated(ctx.source, conn -> {
            // "*" for user attributes (so membership/projection can be evaluated)
            // plus the identity attribute explicitly (it may be operational).
            // Paged: a scope above the server's size limit must still verify completely.
            SearchRequest req = new SearchRequest(base, scope,
                    Filter.createPresenceFilter("objectClass"), "*", idAttr);
            return SyncPagedSearch.all(conn, req, pageSize);
        });
    }

    private List<SearchResultEntry> enumerateTarget(Context ctx) {
        String base = targetBase(ctx);
        SearchScope scope = SyncScopes.searchScope(ctx.set);
        Filter filter = applicabilityFilter(ctx.set);
        int pageSize = ctx.target.getPagingSize();
        // The anchor explicitly too: orphan counting reads it, and it may be operational.
        String anchor = anchorAttribute(ctx.set);
        String[] attrs = anchor == null ? new String[] {"*"} : new String[] {"*", anchor};
        return connectionFactory.withConnectionUnreplicated(ctx.target, conn -> {
            SearchRequest req = new SearchRequest(base, scope, filter, attrs);
            return SyncPagedSearch.all(conn, req, pageSize);
        });
    }

    /**
     * Where placed entries live on the target: the explicit target base, else the
     * source object scope (identity placement keeps the source DN), else the
     * target directory base.
     */
    private static String targetBase(Context ctx) {
        return SyncPlacement.effectiveTargetBase(ctx.set, ctx.target);
    }

    /** The set's applicability filter, or a presence filter when none is configured. */
    private static Filter applicabilityFilter(SyncSet set) {
        String f = set.getApplicabilityFilter();
        if (f != null && !f.isBlank()) {
            try {
                return Filter.create(f);
            } catch (LDAPException ex) {
                log.warn("Sync set {}: invalid applicability filter [{}] during verify; using presence",
                        set.getId(), f);
            }
        }
        return Filter.createPresenceFilter("objectClass");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Whether an entry exists at {@code dn} on the target (a read failure counts as no). */
    private boolean readExists(Context ctx, String dn) {
        try {
            return connectionFactory.withConnectionUnreplicated(ctx.target, conn -> conn.getEntry(dn, "1.1") != null);
        } catch (Exception ex) {
            return false;
        }
    }

    private Context loadContext(UUID syncSetId) {
        SyncSet set = syncSetRepo.findById(syncSetId).orElse(null);
        if (set == null) {
            return null;
        }
        SyncLink link = syncLinkRepo.findById(set.getLinkId()).orElse(null);
        if (link == null) {
            return null;
        }
        DirectoryConnection source = directoryRepo.findById(link.getSourceDirId()).orElse(null);
        DirectoryConnection target = directoryRepo.findById(link.getTargetDirId()).orElse(null);
        if (source == null || target == null) {
            return null;
        }
        IdentityStrategy strategy = identityStrategies.forType(source.getDirectoryType());
        if (SyncIdentity.attribute(set, strategy) == null) {
            return null;
        }
        return new Context(set, link, source, target, strategy);
    }

    private static SyncVerifyResult unavailable(String note) {
        return new SyncVerifyResult(0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), false, false, note);
    }

    private static List<String> sample(List<String> all) {
        return all.size() <= SAMPLE_LIMIT ? List.copyOf(all) : List.copyOf(all.subList(0, SAMPLE_LIMIT));
    }

    /** DN-canonical key so spacing/case variants between the sides still match. */
    private static String normDn(String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException ex) {
            return dn == null ? "" : dn.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * {@code held}: lower-cased reference attributes the engine keeps as they are
     * on the target; {@code keepCandidates}: per attribute, each untranslated
     * source DN mapped to the target DN the engine keeps it as where the target
     * has it (see {@link UntranslatedReferenceTargets}).
     */
    record Expected(String displayDn, List<Attribute> desired, Set<String> held,
                    Map<String, Map<String, String>> keepCandidates, String sourceDn, String identity) {

        /** Without the source member's DN and identity (they only label drift items). */
        Expected(String displayDn, List<Attribute> desired, Set<String> held,
                 Map<String, Map<String, String>> keepCandidates) {
            this(displayDn, desired, held, keepCandidates, null, null);
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.HashSet<>(a);
        out.addAll(b);
        return out;
    }

    private record Context(SyncSet set, SyncLink link, DirectoryConnection source,
                           DirectoryConnection target, IdentityStrategy strategy) {
    }
}
