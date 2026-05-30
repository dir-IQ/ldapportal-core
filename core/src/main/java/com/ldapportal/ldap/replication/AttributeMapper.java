// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.ldap.replication.ReplicationLinkSnapshot.AttrMappingSnapshot;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies a {@link ReplicationLinkSnapshot}'s attribute-rename and
 * value-template rules. Identity mapping (no rule for the attribute,
 * or an empty rule list) passes the attribute through unchanged.
 *
 * <p>Value templates support a single {@code ${value}} substitution
 * token; no scripting, no conditional logic. Hard transforms
 * (multi-valued joins, password format conversion) are explicitly out
 * of scope for v1.
 */
public final class AttributeMapper {

    private static final String VALUE_TOKEN = "${value}";

    private AttributeMapper() {}

    /**
     * Map a source attribute name to the target attribute name and a
     * value-transform function. Returns the identity rule (same name,
     * pass-through values) when no rule exists for {@code sourceAttr}.
     *
     * <p>Attribute names are compared case-insensitively to match LDAP
     * attribute-type matching semantics.
     */
    public static Mapping mappingFor(String sourceAttr, ReplicationLinkSnapshot link) {
        for (AttrMappingSnapshot rule : link.attributeMappings()) {
            if (rule.sourceAttr().equalsIgnoreCase(sourceAttr)) {
                return new Mapping(rule.targetAttr(), templateFn(rule.valueTemplate()));
            }
        }
        return new Mapping(sourceAttr, Function.identity());
    }

    /**
     * Map a full attribute map. Source keys are looked up in the link's
     * rules; absent keys map to identity. The returned map's keys are
     * the target attribute names.
     */
    public static Map<String, List<String>> mapAttributes(
            Map<String, List<String>> source, ReplicationLinkSnapshot link) {
        return source.entrySet().stream()
                .map(e -> {
                    Mapping m = mappingFor(e.getKey(), link);
                    List<String> mappedValues = e.getValue().stream()
                            .map(m.valueTransform)
                            .collect(Collectors.toList());
                    return Map.entry(m.targetAttr, mappedValues);
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> { a.addAll(b); return a; }));  // merge if two source attrs collide on target
    }

    private static Function<String, String> templateFn(String template) {
        if (template == null || template.isEmpty() || template.equals(VALUE_TOKEN)) {
            return Function.identity();
        }
        // Single-token replacement only. No nested expressions in v1.
        return value -> template.replace(VALUE_TOKEN, value == null ? "" : value);
    }

    public record Mapping(String targetAttr, Function<String, String> valueTransform) {}
}
