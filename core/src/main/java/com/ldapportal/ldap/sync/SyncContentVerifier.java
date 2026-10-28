// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncVerifyResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
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
 *   <li><b>orphan</b> — a target entry with no corresponding source member,</li>
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

    /** Verify the live source/target contents of a sync set and report mismatches. */
    public SyncVerifyResult verify(UUID syncSetId) {
        Context ctx = loadContext(syncSetId);
        if (ctx == null) {
            return unavailable("Sync set, link, or source/target directory is unavailable or disabled.");
        }

        // Source side: enumerate + project members, keyed by normalized target DN.
        // Reference attributes resolve through the index exactly as the engine does,
        // so the desired image matches what was actually applied (see class doc).
        ReferenceResolver resolver = referenceResolvers.snapshot(ctx.link);
        Map<String, MembershipDecision> members = new LinkedHashMap<>();
        boolean sourceComplete = true;
        try {
            for (SearchResultEntry e : enumerateSource(ctx)) {
                var decision = membershipFunction.evaluate(ctx.set, ctx.strategy, e, resolver);
                if (decision.member() && decision.targetDn() != null) {
                    members.put(normDn(decision.targetDn()), decision);
                }
            }
        } catch (Exception ex) {
            log.warn("Sync set {}: source enumeration failed during verify ({})", syncSetId, ex.toString());
            sourceComplete = false;
        }

        // Target side: enumerate entries actually present, keyed by normalized DN.
        Map<String, Entry> actual = new LinkedHashMap<>();
        boolean targetComplete = true;
        try {
            for (SearchResultEntry e : enumerateTarget(ctx)) {
                actual.put(normDn(e.getDN()), e);
            }
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
        return compare(expected, actual, excluded, sourceComplete, targetComplete,
                targetSchemas.forDirectory(ctx.target), SyncReferenceAttributes.forSet(ctx.set));
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
        int inSync = 0;
        List<String> missing = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, Expected> en : expected.entrySet()) {
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

        List<String> orphans = new ArrayList<>();
        for (Map.Entry<String, Entry> en : actual.entrySet()) {
            if (!expected.containsKey(en.getKey())) {
                orphans.add(en.getValue().getDN());
            }
        }

        return new SyncVerifyResult(
                expected.size(), actual.size(), inSync,
                missing.size(), orphans.size(), mismatches.size(),
                sample(missing), sample(orphans), sample(mismatches),
                sourceComplete, targetComplete, null);
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
        return connectionFactory.withConnectionUnreplicated(ctx.target, conn -> {
            SearchRequest req = new SearchRequest(base, scope, filter, "*");
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
