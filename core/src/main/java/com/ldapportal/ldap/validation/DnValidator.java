// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import com.ldapportal.entity.enums.DirectoryType;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RDN;

/**
 * Syntactic validation of distinguished names (DNs) and relative distinguished
 * names (RDNs) using the UnboundID LDAP SDK parser. Malformed input is rejected
 * with {@link IllegalArgumentException}, which the core
 * {@code GlobalExceptionHandler} maps to an HTTP 400 ProblemDetail.
 *
 * <p>This centralises the DN-format check that previously lived inline in
 * {@code LdapBrowseService.createContainer} so every write path can apply the
 * same rule before building a provisioning plan, rather than relying on the
 * directory server to reject a malformed DN at {@code add} time.</p>
 *
 * <p><strong>Directory-type aware.</strong> Entra ID identifies objects by
 * object id / UPN rather than by DN, so DN-syntax checks are skipped for
 * {@link DirectoryType#ENTRA_ID}, mirroring the existing Entra guard in
 * {@code LdapBrowseService.createContainer}.</p>
 */
public final class DnValidator {

    private DnValidator() {
    }

    /**
     * Parses {@code dn} and returns the validated {@link DN}.
     *
     * @throws IllegalArgumentException if {@code dn} is null, blank, the root
     *                                  DSE (empty/null DN), or not a
     *                                  syntactically valid DN
     */
    public static DN parse(String dn) {
        if (dn == null || dn.isBlank()) {
            throw new IllegalArgumentException("DN must not be empty");
        }
        DN parsed;
        try {
            parsed = new DN(dn);
        } catch (LDAPException e) {
            throw new IllegalArgumentException("Invalid DN: " + dn, e);
        }
        if (parsed.isNullDN() || parsed.getRDNs().length == 0) {
            throw new IllegalArgumentException("DN must not be the root DSE: " + dn);
        }
        return parsed;
    }

    /** @return {@code true} if {@code dn} is a syntactically valid, non-empty DN. */
    public static boolean isValidDn(String dn) {
        if (dn == null || dn.isBlank()) {
            return false;
        }
        try {
            DN parsed = new DN(dn);
            return !parsed.isNullDN() && parsed.getRDNs().length > 0;
        } catch (LDAPException e) {
            return false;
        }
    }

    /**
     * Validates that {@code dn} is a syntactically valid, non-empty DN. No-op
     * for Entra ID directories.
     *
     * @throws IllegalArgumentException if the DN is null, blank, the root DSE,
     *                                  or malformed
     */
    public static void requireValidDn(String dn, DirectoryType directoryType) {
        if (directoryType == DirectoryType.ENTRA_ID) {
            return;
        }
        parse(dn);
    }

    /**
     * Validates that {@code rdn} is a single, syntactically valid RDN
     * (e.g. {@code uid=jsmith} or a multi-valued {@code cn=a+sn=b}). A full DN
     * with multiple components is rejected. No-op for Entra ID directories.
     *
     * @throws IllegalArgumentException if the RDN is null, blank, or malformed
     */
    /**
     * @return {@code true} if {@code dn} is equal to, or a descendant of,
     * {@code baseDn} — i.e. lies within the {@code baseDn} subtree. Uses the
     * UnboundID parser so the comparison is RDN-boundary aware (a naive string
     * suffix check would let {@code uid=x,ou=people2,dc=…} masquerade as being
     * under {@code ou=people,dc=…}). Returns {@code false} when either DN is
     * malformed.
     */
    public static boolean isWithinSubtree(String dn, String baseDn) {
        if (dn == null || dn.isBlank() || baseDn == null || baseDn.isBlank()) {
            return false;
        }
        try {
            return new DN(dn).isDescendantOf(new DN(baseDn), true);
        } catch (LDAPException e) {
            return false;
        }
    }

    public static void requireValidRdn(String rdn, DirectoryType directoryType) {
        if (directoryType == DirectoryType.ENTRA_ID) {
            return;
        }
        if (rdn == null || rdn.isBlank()) {
            throw new IllegalArgumentException("RDN must not be empty");
        }
        try {
            new RDN(rdn);
        } catch (LDAPException e) {
            throw new IllegalArgumentException("Invalid RDN: " + rdn, e);
        }
    }
}
