// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Snapshot of what an LDAP server advertises about itself via the
 * root DSE. Read once at connect-time by {@link
 * com.ldapportal.ldap.LdapCapabilityProbeService} and persisted as
 * the {@code capabilities} JSONB column on
 * {@link com.ldapportal.entity.DirectoryConnection}; surfaced as a
 * vendor/version badge in the directory-connection UI.
 *
 * <p>Every field is nullable except {@code probedAt} — root DSE
 * attributes are server-optional and behaviour differs by vendor:
 * <ul>
 *   <li>OpenLDAP / AD typically populate every field.</li>
 *   <li>OpenDJ (the OUD substitute) populates the OID lists but
 *       may omit {@code vendorName}.</li>
 *   <li>Real Oracle OUD advertises {@code vendorName} as
 *       {@code Oracle Corporation} or, on older builds, the
 *       inherited {@code Sun Microsystems}.</li>
 *   <li>IBM Directory Server advertises vendor through the proprietary
 *       {@code ibm-slapdVersion} attribute rather than {@code vendorVersion}
 *       — the probe falls back to the standard fields for now; a vendor-
 *       specific override lands in a later phase of the ITDS support work.</li>
 * </ul>
 * Consumers must therefore treat every attribute as may-be-null.
 *
 * @param vendorName             value of root-DSE {@code vendorName}, or null
 * @param vendorVersion          value of root-DSE {@code vendorVersion}, or null
 * @param supportedControls      OIDs from root-DSE {@code supportedControl}
 *                                — empty if the server doesn't expose any
 * @param supportedExtensions    OIDs from root-DSE {@code supportedExtension}
 * @param supportedSaslMechanisms names from root-DSE {@code supportedSASLMechanisms}
 * @param namingContexts         DNs from root-DSE {@code namingContexts} —
 *                                useful for cross-referencing the configured
 *                                base DN against what the server actually hosts
 * @param probedAt               when the probe ran; refresh policy is
 *                                a deferred concern (see P2 plan)
 */
public record DirectoryCapabilities(
        String vendorName,
        String vendorVersion,
        List<String> supportedControls,
        List<String> supportedExtensions,
        List<String> supportedSaslMechanisms,
        List<String> namingContexts,
        OffsetDateTime probedAt) {

    /**
     * Compact constructor — defensively defaults null list fields to
     * empty lists so call-sites never have to null-check before
     * iterating. Vendor / version stay null if absent because their
     * nullness is semantically meaningful ("server didn't advertise
     * this") whereas an empty list is just "nothing supported".
     */
    public DirectoryCapabilities {
        supportedControls       = supportedControls       == null ? List.of() : List.copyOf(supportedControls);
        supportedExtensions     = supportedExtensions     == null ? List.of() : List.copyOf(supportedExtensions);
        supportedSaslMechanisms = supportedSaslMechanisms == null ? List.of() : List.copyOf(supportedSaslMechanisms);
        namingContexts          = namingContexts          == null ? List.of() : List.copyOf(namingContexts);
    }
}
