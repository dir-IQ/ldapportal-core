// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import com.ldapportal.entity.enums.InputType;
import com.ldapportal.ldap.validation.AttributeSyntax;
import com.ldapportal.ldap.validation.WellKnownAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-facing description of the built-in attribute <em>syntax</em> the server
 * enforces on write (DN / email / boolean), so the admin UI can mirror those
 * checks for instant field-level feedback from a single source of truth instead
 * of hard-coding a parallel list that could drift from the backend.
 *
 * <p>Two maps, both with {@link AttributeSyntax.Kind} names as values:</p>
 * <ul>
 *   <li>{@code wellKnownAttributes} — lower-case attribute name → kind, for bare
 *       / unprofiled attributes (e.g. {@code manager} → {@code DN},
 *       {@code mail} → {@code EMAIL}).</li>
 *   <li>{@code inputTypeSyntax} — {@link InputType} name → kind, for the
 *       profile-configured input types that imply a shape (e.g.
 *       {@code DN_LOOKUP} → {@code DN}, {@code BOOLEAN} → {@code BOOLEAN}). The
 *       UI already receives each field's {@code inputType} in the profile
 *       config; this tells it how to map that to a syntax check.</li>
 * </ul>
 */
public record AttributeSyntaxHints(
        Map<String, String> wellKnownAttributes,
        Map<String, String> inputTypeSyntax) {

    /** Builds the hints from the validation layer's source-of-truth maps. */
    public static AttributeSyntaxHints current() {
        Map<String, String> wellKnown = new LinkedHashMap<>();
        WellKnownAttributes.all().forEach((name, kind) -> wellKnown.put(name, kind.name()));

        Map<String, String> byInputType = new LinkedHashMap<>();
        for (InputType inputType : InputType.values()) {
            AttributeSyntax.Kind kind = AttributeSyntax.forInputType(inputType);
            if (kind != null) {
                byInputType.put(inputType.name(), kind.name());
            }
        }
        return new AttributeSyntaxHints(wellKnown, byInputType);
    }
}
