// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

/**
 * Computes the target DN for a source entry (a prefix-rewrite of the source base
 * to the target base — the Phase 1 placement). A DN-template generalization
 * (restructuring/flattening) lands in a later phase.
 *
 * <ul>
 *   <li>No target base, or no source base: identity placement (target DN ==
 *       source DN).</li>
 *   <li>Otherwise: the source DN's tail matching {@code objectScopeBaseDn} is
 *       replaced with {@code targetBaseDn}. DN-canonical comparison via the
 *       UnboundID parser tolerates spacing/case variants.</li>
 * </ul>
 */
public final class SyncPlacement {

    private SyncPlacement() {
    }

    /**
     * @return the target DN, or {@code null} when the source DN is outside the
     *         configured source base (so the entry can't be placed).
     */
    public static String targetDn(SyncSet set, String sourceDn) {
        String sourceBase = set.getObjectScopeBaseDn();
        String targetBase = set.getTargetBaseDn();
        if (targetBase == null || sourceBase == null) {
            return sourceDn;
        }
        try {
            DN source = new DN(sourceDn);
            DN scope = new DN(sourceBase);
            if (!source.isDescendantOf(scope, true)) {
                return null;
            }
            String sourceNorm = source.toNormalizedString();
            String scopeNorm = scope.toNormalizedString();
            if (sourceNorm.equals(scopeNorm)) {
                return targetBase;
            }
            String prefix = sourceNorm.substring(0, sourceNorm.length() - scopeNorm.length() - 1);
            return prefix + "," + targetBase;
        } catch (LDAPException ex) {
            return null;
        }
    }
}
