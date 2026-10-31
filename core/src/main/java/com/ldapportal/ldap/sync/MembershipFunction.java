// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.SyncTransformRule;
import com.ldapportal.entity.enums.SyncNotInSetReason;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The pure membership function: {@code membership(syncSet, sourceEntry) -> IN | OUT}.
 * Always evaluated against a known image (or known-absent, handled by the
 * caller) — that property removes the entire "entry unavailable at DELETE/MODIFY
 * time" problem class.
 *
 * <p>IN computes: {@code identity} (via the {@link IdentityStrategy}),
 * {@code targetDn} (placement = prefix-rewrite), {@code desiredAttrs}
 * (transform = attribute mapping + sourceAnchor + DN-reference remapping), and a
 * content hash over the projected output.
 */
@Component
@Slf4j
public class MembershipFunction {

    private static final String VALUE_TOKEN = "${value}";

    /**
     * Evaluate membership for a present source entry. (Absent entries are OUT and
     * handled by the engine without calling this.)
     */
    public MembershipDecision evaluate(SyncSet set, IdentityStrategy strategy,
                                       Entry entry, ReferenceResolver resolver) {
        String identity = SyncIdentity.extract(set, strategy, entry);
        if (identity == null || identity.isBlank()) {
            log.debug("Sync set {}: entry {} has no identity ({}); treating as OUT",
                    set.getId(), entry.getDN(), SyncIdentity.attribute(set, strategy));
            return MembershipDecision.out(null);
        }

        String filter = set.getApplicabilityFilter();
        if (filter != null && !filter.isBlank()) {
            try {
                if (!Filter.create(filter).matchesEntry(entry)) {
                    return MembershipDecision.out(identity, SyncNotInSetReason.FILTER);
                }
            } catch (LDAPException ex) {
                // A filter that cannot be evaluated is a fault, not an OUT: an OUT
                // would delete every tracked target entry as each one is touched.
                // Propagate so the request is retried (the worker releases its
                // claim) and the reconcile records an incomplete plan.
                throw new IllegalStateException("Sync set " + set.getId()
                        + ": applicability filter [" + filter + "] cannot be evaluated: "
                        + ex.getMessage(), ex);
            }
        }

        String targetDn = SyncPlacement.targetDn(set, entry.getDN());
        if (targetDn == null) {
            return MembershipDecision.out(identity, SyncNotInSetReason.PLACEMENT);
        }

        Projection projected = project(set, strategy, entry, identity, resolver);
        java.util.Map<String, List<String>> untranslated = new LinkedHashMap<>(projected.held());
        untranslated.putAll(projected.unresolved());
        byte[] hash = SyncContentHash.of(targetDn, projected.attrs(), untranslated);
        return MembershipDecision.in(identity, targetDn, projected.attrs(), hash, projected.held(),
                projected.unresolved());
    }

    /**
     * The projected attributes, plus the reference values that couldn't be
     * translated yet (target name → untranslated source DNs): {@code held} for
     * attributes none of whose values translated, {@code unresolved} for those
     * that kept some.
     */
    private record Projection(List<Attribute> attrs, Map<String, List<String>> held,
                              Map<String, List<String>> unresolved) {
    }

    private Projection project(SyncSet set, IdentityStrategy strategy, Entry entry,
                               String identity, ReferenceResolver resolver) {
        Set<String> excluded = SyncExcludedAttributes.effectiveFor(set);
        List<String> refAttrs = SyncReferenceAttributes.forSet(set);
        String idAttr = SyncIdentity.attribute(set, strategy);
        // Preserve insertion order; merge values when two source attrs collide on
        // the same target name.
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        Map<String, String> targetCase = new LinkedHashMap<>();
        Map<String, List<String>> unresolved = new LinkedHashMap<>();

        // Resolve every reference value in one go rather than one lookup each.
        List<String> refValues = new ArrayList<>();
        for (Attribute a : entry.getAttributes()) {
            if (SyncReferenceAttributes.containsIgnoreCase(refAttrs, a.getName())
                    && !excluded.contains(a.getName().toLowerCase(Locale.ROOT))) {
                refValues.addAll(java.util.Arrays.asList(a.getValues()));
            }
        }
        if (refValues.size() > 1) {
            resolver = resolver.prefetched(refValues);
        }

        for (Attribute a : entry.getAttributes()) {
            String name = a.getName();
            // Never project excluded attributes — server-maintained operational
            // attributes and password values by default, plus any the sync set
            // configures. See SyncExcludedAttributes.
            if (excluded.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (idAttr != null && name.equalsIgnoreCase(idAttr)) {
                continue;
            }
            Mapping m = mappingFor(set, name);
            List<String> values;
            if (SyncReferenceAttributes.containsIgnoreCase(refAttrs, name)) {
                // DN-reference remapping: rewrite each value through the index;
                // drop unsynced referents (a closure trigger re-emits later).
                // Note every value that doesn't translate, so the target's own
                // copy of it can be kept rather than deleted until it lands.
                values = new ArrayList<>();
                for (String v : a.getValues()) {
                    java.util.Optional<String> translated = resolver.resolveTargetDn(v);
                    if (translated.isPresent()) {
                        values.add(translated.get());
                    } else {
                        // One entry per target attribute whatever the case it's
                        // written in (two mappings may target "Member" and "member").
                        String key = targetCase.computeIfAbsent(m.targetAttr().toLowerCase(Locale.ROOT),
                                k -> m.targetAttr());
                        unresolved.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
                    }
                }
                if (values.isEmpty()) {
                    continue;
                }
            } else {
                values = new ArrayList<>(a.getValues().length);
                for (String v : a.getValues()) {
                    values.add(m.apply(v));
                }
            }
            addValues(byTarget, targetCase, m.targetAttr(), values);
        }

        String anchorAttr = set.getSourceAnchorAttribute();
        if (anchorAttr != null && !anchorAttr.isBlank()) {
            addValues(byTarget, targetCase, anchorAttr, List.of(identity));
        }

        List<Attribute> out = new ArrayList<>(byTarget.size());
        byTarget.forEach((key, values) ->
                out.add(new Attribute(targetCase.get(key), values.toArray(new String[0]))));
        // Held (the whole target attribute kept) only when the attribute ends up
        // with no values at all; otherwise the untranslated values are kept one
        // by one, where the target already has them. Another source attribute
        // mapped onto the same target may have supplied some values.
        Map<String, List<String>> held = new LinkedHashMap<>();
        Map<String, List<String>> partial = new LinkedHashMap<>();
        unresolved.forEach((name, dns) -> (byTarget.containsKey(name.toLowerCase(Locale.ROOT)) ? partial : held)
                .put(name, List.copyOf(dns)));
        return new Projection(out, held.isEmpty() ? Map.of() : held, partial.isEmpty() ? Map.of() : partial);
    }

    private static void addValues(Map<String, List<String>> byTarget,
                                  Map<String, String> targetCase,
                                  String targetAttr, List<String> values) {
        String key = targetAttr.toLowerCase(Locale.ROOT);
        targetCase.putIfAbsent(key, targetAttr);
        byTarget.computeIfAbsent(key, k -> new ArrayList<>()).addAll(values);
    }

    private static Mapping mappingFor(SyncSet set, String sourceAttr) {
        if (set.getTransformRules() != null) {
            for (SyncTransformRule rule : set.getTransformRules()) {
                if (rule.getSourceAttr() != null && rule.getSourceAttr().equalsIgnoreCase(sourceAttr)) {
                    return new Mapping(
                            rule.getTargetAttr() == null ? sourceAttr : rule.getTargetAttr(),
                            rule.getValueTemplate());
                }
            }
        }
        return new Mapping(sourceAttr, null);
    }

    private record Mapping(String targetAttr, String valueTemplate) {
        String apply(String value) {
            if (valueTemplate == null || valueTemplate.isEmpty() || valueTemplate.equals(VALUE_TOKEN)) {
                return value;
            }
            return valueTemplate.replace(VALUE_TOKEN, value == null ? "" : value);
        }
    }
}
