// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.matchingrules.MatchingRule;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.Schema;

import java.util.Locale;

/**
 * Canonicalises an attribute value for <em>equality</em> comparison using
 * the attribute's LDAP matching rule, so reconciliation does not report
 * case- or DN-formatting-only differences as drift.
 *
 * <p>The directory considers two values equal when they normalise equal
 * under the attribute's declared equality matching rule — not when their
 * raw strings match byte-for-byte. Comparing raw strings (the previous
 * behaviour) over-reports drift for the common case-insensitive attribute
 * classes ({@code cn}, {@code mail}, {@code objectClass}, …) and for
 * DN-syntax attributes ({@code member}, {@code manager}, {@code owner}, …)
 * whose component case or whitespace differs between source and target —
 * and, against a target that canonicalises on write (e.g. OUD), such
 * "drift" never clears, so auto-correct would rewrite it on every run.
 *
 * <p>With a target {@link Schema} each attribute's real matching rule is
 * honoured: caseIgnore folds case and collapses whitespace, DN-syntax
 * normalises the DN, genuinely case-exact attributes keep their case.
 * Without a schema (unit tests, schema-less servers) UnboundID's selection
 * falls back to caseIgnore — still the right call for the overwhelming
 * majority of string attributes.
 *
 * <p>Normalisation feeds the equality decision only; finding payloads keep
 * the original source/target values for display.
 */
@FunctionalInterface
public interface ValueNormalizer {

    /** Canonical form of {@code value} under {@code attrName}'s equality rule. */
    String canonical(String attrName, String value);

    /** Schema-less normaliser (caseIgnore default for unknown attributes). */
    ValueNormalizer DEFAULT = forSchema(null);

    /**
     * Normaliser backed by {@code schema} (nullable). Resolves each
     * attribute's equality matching rule via the schema and normalises with
     * it; on any rule failure (e.g. a value that is not a valid DN for a
     * DN-syntax attribute) it case-folds so equal-but-uncanonical values
     * still compare equal rather than throwing.
     */
    static ValueNormalizer forSchema(Schema schema) {
        return (attrName, value) -> {
            if (value == null) return "";
            try {
                MatchingRule rule = MatchingRule.selectEqualityMatchingRule(attrName, schema);
                return rule.normalize(new ASN1OctetString(value)).stringValue();
            } catch (LDAPException e) {
                return value.trim().toLowerCase(Locale.ROOT);
            }
        };
    }
}
