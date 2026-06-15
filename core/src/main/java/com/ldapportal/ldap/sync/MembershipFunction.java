// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.SyncTransformRule;
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
                    return MembershipDecision.out(identity);
                }
            } catch (LDAPException ex) {
                log.warn("Sync set {}: invalid applicability filter [{}]: {}",
                        set.getId(), filter, ex.getMessage());
                return MembershipDecision.out(identity);
            }
        }

        String targetDn = SyncPlacement.targetDn(set, entry.getDN());
        if (targetDn == null) {
            return MembershipDecision.out(identity);
        }

        List<Attribute> desired = project(set, strategy, entry, identity, resolver);
        byte[] hash = SyncContentHash.of(targetDn, desired);
        return MembershipDecision.in(identity, targetDn, desired, hash);
    }

    private List<Attribute> project(SyncSet set, IdentityStrategy strategy, Entry entry,
                                    String identity, ReferenceResolver resolver) {
        List<String> refAttrs = SyncReferenceAttributes.forSet(set);
        String idAttr = SyncIdentity.attribute(set, strategy);
        // Preserve insertion order; merge values when two source attrs collide on
        // the same target name.
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        Map<String, String> targetCase = new LinkedHashMap<>();

        for (Attribute a : entry.getAttributes()) {
            String name = a.getName();
            // Never project server-maintained operational attributes (timestamps,
            // entryUUID, structural metadata, …) — see SyncOperationalAttributes.
            if (SyncOperationalAttributes.contains(name)) {
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
                values = new ArrayList<>();
                for (String v : a.getValues()) {
                    resolver.resolveTargetDn(v).ifPresent(values::add);
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
        return out;
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
