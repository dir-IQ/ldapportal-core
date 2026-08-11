// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

/**
 * One configured {@code secUser} attribute in the unified per-attribute
 * overlay model: its LDAP name, whether it's written on a grant, and how
 * its value is produced.
 *
 * <p>This replaces the split representation the config used before —
 * a {@code secuserOverlayAttributes} name-list plus standalone
 * {@code secAuthority} / {@code secLoginType} / {@code defaultValidUntilYears}
 * value fields — with one uniform row per attribute, each carrying its own
 * literal value or computed expression. The two IBM-{@code secUser}-MUST
 * attributes ({@code secLoginType}, {@code secAuthority}) are modelled as
 * ordinary rows that happen to be forced {@code enabled} (they can't be
 * excluded); everything else is optional.</p>
 *
 * @param name      the LDAP attribute name (canonical spelling)
 * @param enabled   whether a grant writes this attribute
 * @param valueKind literal vs computed — see {@link SecUserAttributeValueKind}
 * @param value     the literal value, or the expression when {@code COMPUTED}
 */
public record SecUserAttribute(String name,
                               boolean enabled,
                               SecUserAttributeValueKind valueKind,
                               String value) {
}
