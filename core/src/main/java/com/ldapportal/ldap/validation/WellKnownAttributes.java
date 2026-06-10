// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import java.util.Locale;
import java.util.Map;

/**
 * Maps a small set of standard LDAP attribute names to the intrinsic
 * {@link AttributeSyntax.Kind} their values must satisfy, so syntax validation
 * applies even when an attribute carries no profile config (an "Other" /
 * unprofiled attribute). Profile configuration can still override the shape via
 * {@code inputType} — see {@link LdapAttributeValidator}.
 *
 * <p>Kept intentionally conservative: only attributes whose value shape is
 * unambiguous across OpenLDAP / AD / IBM directory schemas are listed, to avoid
 * false rejections of legitimate data. DN-valued attributes are the
 * highest-value entries — a typed or pasted {@code manager}/{@code secretary}
 * value is otherwise validated only by the directory server at write time.</p>
 */
public final class WellKnownAttributes {

    private static final Map<String, AttributeSyntax.Kind> SYNTAX = Map.ofEntries(
            // DN-valued person/group references
            Map.entry("manager", AttributeSyntax.Kind.DN),
            Map.entry("secretary", AttributeSyntax.Kind.DN),
            Map.entry("owner", AttributeSyntax.Kind.DN),
            Map.entry("seealso", AttributeSyntax.Kind.DN),
            Map.entry("roleoccupant", AttributeSyntax.Kind.DN),
            Map.entry("member", AttributeSyntax.Kind.DN),
            Map.entry("uniquemember", AttributeSyntax.Kind.DN),
            // Email-valued
            Map.entry("mail", AttributeSyntax.Kind.EMAIL));

    private WellKnownAttributes() {
    }

    /**
     * @return the syntax kind for {@code attribute} (case-insensitive), or
     *         {@code null} if the attribute is not well-known and has no
     *         intrinsic syntax to enforce.
     */
    public static AttributeSyntax.Kind syntaxFor(String attribute) {
        if (attribute == null) {
            return null;
        }
        return SYNTAX.get(attribute.toLowerCase(Locale.ROOT));
    }
}
