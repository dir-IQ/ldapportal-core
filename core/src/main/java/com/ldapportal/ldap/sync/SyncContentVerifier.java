// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncVerifyResult;
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
 *       the set writes a source anchor, only target entries carrying it count:
 *       an entry the set doesn't manage (another set's, or one never synced)
 *       under the same target base isn't this set's drift,</li>
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
        boolean sourceComplete = true;
        try {
            int read = 0;
            for (SearchResultEntry e : enumerateSource(ctx)) {
                var decision = membershipFunction.evaluate(ctx.set, ctx.strategy, e, resolver);
                if (decision.member() && decision.targetDn() != null) {
                    members.put(normDn(decision.targetDn()), decision);
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
                d.heldAttributeNames(), lookup.candidates(d.unresolvedReferences(), targetExists))));

        Set<String> excluded = SyncExcludedAttributes.effectiveFor(ctx.set);
        tracking.phase(SyncVerifyPhase.COMPARING);
        tracking.total(expected.size());
        SyncVerifyResult result = compare(expected, actual, excluded, sourceComplete, targetComplete,
                targetSchemas.forDirectory(ctx.target), SyncReferenceAttributes.forSet(ctx.set), (n, dn) -> {
                    tracking.processed(n);
                    tracking.current(dn);
                }, anchorAttribute(ctx.set));
        tracking.processed(expected.size());
        return result.withHeldReferences(heldReferencesOnSource(ctx, members.values()));
    }

    /**
     * {@link #heldReferences} with the referenced DNs looked up on the source over
     * one pooled connection rather than one borrow per DN. If the source can't be
     * reached, nothing counts as dangling.
     */
    private SyncVerifyResult.HeldReferences heldReferencesOnSource(Context ctx,
                                                                   java.util.Collection<MembershipDecision> members) {
        try {
            return connectionFactory.withConnectionUnreplicated(ctx.source, conn -> {
                Map<String, Boolean> onSource = new HashMap<>();
                return heldReferences(members, dn -> onSource.computeIfAbsent(normDn(dn), k -> {
                    try {
                        return conn.getEntry(dn, "1.1") != null;
                    } catch (Exception ex) {
                        return true; // a failed read isn't evidence the entry is missing
                    }
                }));
            });
        } catch (Exception ex) {
            log.warn("Sync set {}: couldn't look up held references on the source ({})",
                    ctx.set.getId(), ex.toString());
            return heldReferences(members, dn -> true);
        }
    }

    /** At most this many distinct referenced DNs are looked up on the source per verify. */
    static final int HELD_CHECK_LIMIT = 500;

    /**
     * The members whose reference values are held back (they name an entry the
     * sync hasn't placed), and how many of those name an entry that doesn't
     * exist on the source at all. Pure apart from {@code existsOnSource}
     * (package-private for direct unit testing).
     */
    static SyncVerifyResult.HeldReferences heldReferences(java.util.Collection<MembershipDecision> members,
                                                         java.util.function.Predicate<String> existsOnSource) {
        int entries = 0;
        int dangling = 0;
        boolean unchecked = false;
        Set<String> looked = new java.util.HashSet<>();
        List<SyncVerifyResult.HeldSample> samples = new ArrayList<>();
        for (MembershipDecision d : members) {
            Map<String, List<String>> refs = new java.util.LinkedHashMap<>(d.heldReferences());
            d.unresolvedReferences().forEach((attr, dns) -> refs.merge(attr, dns,
                    (x, y) -> { List<String> all = new ArrayList<>(x); all.addAll(y); return all; }));
            if (refs.isEmpty()) {
                continue;
            }
            entries++;
            boolean entryDangling = false;
            for (Map.Entry<String, List<String>> ref : refs.entrySet()) {
                for (String dn : ref.getValue()) {
                    Boolean missing = null;
                    if (looked.size() < HELD_CHECK_LIMIT || looked.contains(normDn(dn))) {
                        looked.add(normDn(dn));
                        missing = !existsOnSource.test(dn);
                    } else {
                        unchecked = true;
                    }
                    entryDangling |= Boolean.TRUE.equals(missing);
                    if (samples.size() < SAMPLE_LIMIT) {
                        samples.add(new SyncVerifyResult.HeldSample(d.targetDn(), ref.getKey(), dn,
                                Boolean.TRUE.equals(missing)));
                    }
                }
            }
            if (entryDangling) {
                dangling++;
            }
        }
        return new SyncVerifyResult.HeldReferences(entries, dangling, unchecked, List.copyOf(samples));
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
                CompareProgress.NONE, null);
    }

    static SyncVerifyResult compare(Map<String, Expected> expected, Map<String, Entry> actual,
                                    Set<String> excluded, boolean sourceComplete, boolean targetComplete,
                                    com.unboundid.ldap.sdk.schema.Schema schema, List<String> dnAttrs,
                                    CompareProgress progress, String anchorAttr) {
        int inSync = 0;
        int compared = 0;
        List<String> missing = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, Expected> en : expected.entrySet()) {
            progress.at(compared++, en.getValue().displayDn());
            Entry target = actual.get(en.getKey());
            if (target == null) {
                missing.add(en.getValue().displayDn());
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
                mismatches.add(en.getValue().displayDn());
            }
        }

        // With an anchor, only entries carrying it are the set's to account for:
        // an unanchored entry under the target base is someone else's.
        List<String> orphans = new ArrayList<>();
        for (Map.Entry<String, Entry> en : actual.entrySet()) {
            if (!expected.containsKey(en.getKey())
                    && (anchorAttr == null || en.getValue().hasAttribute(anchorAttr))) {
                orphans.add(en.getValue().getDN());
            }
        }

        return new SyncVerifyResult(
                expected.size(), actual.size(), inSync,
                missing.size(), orphans.size(), mismatches.size(),
                sample(missing), sample(orphans), sample(mismatches),
                sourceComplete, targetComplete, null, anchorAttr != null);
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
                    Map<String, Map<String, String>> keepCandidates) {
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
