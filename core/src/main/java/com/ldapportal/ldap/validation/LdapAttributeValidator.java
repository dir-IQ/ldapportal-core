// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.InputType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies built-in {@link AttributeSyntax} checks across a whole attribute map,
 * resolving each attribute's expected value shape from — in priority order — the
 * profile's configured {@code inputType} and then the {@link WellKnownAttributes}
 * fallback. This is the syntax layer the attribute-validation plan places
 * <em>beside</em> the configurable profile-rule engine: the profile engine owns
 * required/length/regex/allowed-values, this owns DN / email / boolean shape.
 *
 * <p>Stateless and directory-type aware — DN checks are skipped for Entra ID via
 * {@link DnValidator}. Attributes with neither a syntax-bearing {@code inputType}
 * nor a well-known mapping pass through untouched, so unprofiled directories and
 * free-form attributes are never over-constrained.</p>
 */
public final class LdapAttributeValidator {

    private LdapAttributeValidator() {
    }

    /**
     * Validates the syntax of every value in {@code attributes}.
     *
     * @param directoryType  directory type, so DN checks can honour the Entra
     *                       exemption
     * @param attributes     attribute name → values being written (create or the
     *                       modified subset of an update)
     * @param inputTypeByAttr profile-configured input types keyed by
     *                        <strong>lower-case</strong> attribute name; pass an
     *                        empty map when no profile applies
     * @throws IllegalArgumentException on the first malformed value
     */
    public static void validateSyntax(DirectoryType directoryType,
                                      Map<String, List<String>> attributes,
                                      Map<String, InputType> inputTypeByAttr) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        Map<String, InputType> inputTypes = inputTypeByAttr == null ? Map.of() : inputTypeByAttr;
        for (Map.Entry<String, List<String>> entry : attributes.entrySet()) {
            String attribute = entry.getKey();
            List<String> values = entry.getValue();
            if (attribute == null || values == null || values.isEmpty()) {
                continue;
            }
            AttributeSyntax.Kind kind = resolveKind(
                    attribute, inputTypes.get(attribute.toLowerCase(Locale.ROOT)));
            if (kind == null) {
                continue;
            }
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                AttributeSyntax.require(kind, attribute, value, directoryType);
            }
        }
    }

    /**
     * Resolves the syntax kind for an attribute. A profile {@code inputType} of
     * {@code DN_LOOKUP} or {@code BOOLEAN} is authoritative; otherwise the
     * well-known map decides (so {@code mail} validates as email and
     * {@code manager} as a DN even when carried by a plain-text or absent config).
     */
    private static AttributeSyntax.Kind resolveKind(String attribute, InputType inputType) {
        if (inputType == InputType.DN_LOOKUP) {
            return AttributeSyntax.Kind.DN;
        }
        if (inputType == InputType.BOOLEAN) {
            return AttributeSyntax.Kind.BOOLEAN;
        }
        return WellKnownAttributes.syntaxFor(attribute);
    }
}
