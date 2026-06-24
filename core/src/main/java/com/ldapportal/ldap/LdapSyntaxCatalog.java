// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import java.util.Map;

/**
 * Human-readable names for well-known LDAP attribute-syntax OIDs.
 *
 * <p>The schema browser prefers whatever description the directory publishes in
 * its {@code ldapSyntaxes} subschema attribute; this catalogue is the fallback
 * for servers that don't (Active Directory, and some others, omit syntax
 * descriptions). Covers the RFC 4517 standard syntaxes (the
 * {@code 1.3.6.1.4.1.1466.115.121.1.*} arc) plus a handful of common
 * vendor-specific ones.</p>
 */
public final class LdapSyntaxCatalog {

    private LdapSyntaxCatalog() {}

    private static final Map<String, String> NAMES = Map.ofEntries(
            // ── RFC 4517 standard syntaxes ──────────────────────────────────
            Map.entry("1.3.6.1.4.1.1466.115.121.1.3",  "Attribute Type Description"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.5",  "Binary"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.6",  "Bit String"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.7",  "Boolean"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.8",  "Certificate"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.9",  "Certificate List"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.10", "Certificate Pair"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.11", "Country String"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.12", "Distinguished Name (DN)"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.14", "Delivery Method"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.15", "Directory String"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.16", "DIT Content Rule Description"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.17", "DIT Structure Rule Description"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.21", "Enhanced Guide"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.22", "Facsimile Telephone Number"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.23", "Fax"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.24", "Generalized Time"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.25", "Guide"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.26", "IA5 String"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.27", "Integer"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.28", "JPEG"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.30", "Matching Rule Description"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.31", "Matching Rule Use Description"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.34", "Name and Optional UID"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.35", "Name Form Description"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.36", "Numeric String"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.37", "Object Class Description"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.38", "OID"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.39", "Other Mailbox"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.40", "Octet String"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.41", "Postal Address"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.43", "Presentation Address"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.44", "Printable String"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.49", "Supported Algorithm"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.50", "Telephone Number"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.51", "Teletex Terminal Identifier"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.52", "Telex Number"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.53", "UTC Time"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.54", "LDAP Syntax Description"),
            Map.entry("1.3.6.1.4.1.1466.115.121.1.58", "Substring Assertion"),
            // ── Common vendor-specific syntaxes (Active Directory) ──────────
            Map.entry("1.2.840.113556.1.4.903",  "Object (DN-Binary)"),
            Map.entry("1.2.840.113556.1.4.904",  "Object (DN-String)"),
            Map.entry("1.2.840.113556.1.4.905",  "Case-Insensitive String (Teletex)"),
            Map.entry("1.2.840.113556.1.4.906",  "Large Integer"),
            Map.entry("1.2.840.113556.1.4.907",  "Object (Security Descriptor)"),
            Map.entry("1.2.840.113556.1.4.1221", "Object (OR-Name)"),
            Map.entry("1.2.840.113556.1.4.1362", "Case-Sensitive String"));

    /**
     * Human-readable name for {@code oid}, or {@code null} if it isn't a
     * catalogued syntax. {@code oid} must be the bare syntax OID — strip any
     * {@code {length}} suffix before calling.
     */
    public static String describe(String oid) {
        return oid == null ? null : NAMES.get(oid);
    }
}
