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
import java.util.Optional;
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

    /** Verify the live source/target contents of a sync set and report mismatches. */
    public SyncVerifyResult verify(UUID syncSetId) {
        Context ctx = loadContext(syncSetId);
        if (ctx == null) {
            return unavailable("Sync set, link, or source/target directory is unavailable or disabled.");
        }

        // Source side: enumerate + project members, keyed by normalized target DN.
        // Reference attributes resolve through the index exactly as the engine does,
        // so the desired image matches what was actually applied (see class doc).
        ReferenceResolver resolver = referenceResolver(ctx);
        Map<String, Expected> expected = new LinkedHashMap<>();
        boolean sourceComplete = true;
        try {
            for (SearchResultEntry e : enumerateSource(ctx)) {
                var decision = membershipFunction.evaluate(ctx.set, ctx.strategy, e, resolver);
                if (decision.member() && decision.targetDn() != null) {
                    expected.put(normDn(decision.targetDn()),
                            new Expected(decision.targetDn(), decision.desiredAttrs()));
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

        Set<String> excluded = SyncExcludedAttributes.effectiveFor(ctx.set);
        return compare(expected, actual, excluded, sourceComplete, targetComplete);
    }

    /**
     * Pure comparison of projected source members against live target entries
     * (package-private for direct unit testing — needs no LDAP).
     */
    static SyncVerifyResult compare(Map<String, Expected> expected, Map<String, Entry> actual,
                                    Set<String> excluded, boolean sourceComplete, boolean targetComplete) {
        int inSync = 0;
        List<String> missing = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, Expected> en : expected.entrySet()) {
            Entry target = actual.get(en.getKey());
            if (target == null) {
                missing.add(en.getValue().displayDn());
                continue;
            }
            List<Modification> mods = TargetEntryDiffer.diff(target, en.getValue().desired(), excluded);
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
        return connectionFactory.withConnectionUnreplicated(ctx.source, conn -> {
            // "*" for user attributes (so membership/projection can be evaluated)
            // plus the identity attribute explicitly (it may be operational).
            SearchRequest req = new SearchRequest(base, scope,
                    Filter.createPresenceFilter("objectClass"), "*", idAttr);
            return new ArrayList<>(conn.search(req).getSearchEntries());
        });
    }

    private List<SearchResultEntry> enumerateTarget(Context ctx) {
        String base = targetBase(ctx);
        SearchScope scope = SyncScopes.searchScope(ctx.set);
        Filter filter = applicabilityFilter(ctx.set);
        return connectionFactory.withConnectionUnreplicated(ctx.target, conn -> {
            SearchRequest req = new SearchRequest(base, scope, filter, "*");
            return new ArrayList<>(conn.search(req).getSearchEntries());
        });
    }

    /**
     * Where placed entries live on the target: the explicit target base, else the
     * source object scope (identity placement keeps the source DN), else the
     * target directory base.
     */
    private static String targetBase(Context ctx) {
        if (ctx.set.getTargetBaseDn() != null) {
            return ctx.set.getTargetBaseDn();
        }
        return ctx.set.getObjectScopeBaseDn() != null
                ? ctx.set.getObjectScopeBaseDn() : ctx.target.getBaseDn();
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

    /**
     * The engine's DN-reference resolver, rebuilt from the membership index: a
     * source DN value maps to its target DN across all sets on the link (an
     * unsynced referent resolves to empty and is dropped from the projection,
     * mirroring {@code RecomputeEngine}). Preloaded into a map to avoid an N+1
     * lookup while projecting the whole scope.
     */
    private ReferenceResolver referenceResolver(Context ctx) {
        Map<String, String> targetBySource = new HashMap<>();
        for (SyncSet s : syncSetRepo.findAllByLinkId(ctx.link.getId())) {
            for (Membership m : membershipRepo.findAllBySyncSetId(s.getId())) {
                if (m.getSourceDn() != null && m.getTargetDn() != null) {
                    targetBySource.putIfAbsent(SyncDnUtil.normalize(m.getSourceDn()), m.getTargetDn());
                }
            }
        }
        return srcDn -> Optional.ofNullable(targetBySource.get(SyncDnUtil.normalize(srcDn)));
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

    record Expected(String displayDn, List<Attribute> desired) {
    }

    private record Context(SyncSet set, SyncLink link, DirectoryConnection source,
                           DirectoryConnection target, IdentityStrategy strategy) {
    }
}
