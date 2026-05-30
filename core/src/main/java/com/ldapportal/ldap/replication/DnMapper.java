// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationLink;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

/**
 * Translates a source-side DN to the target-side DN for a given
 * {@link ReplicationLink}. Three cases:
 *
 * <ul>
 *   <li><b>Identity mapping</b> ({@code sourceBaseDn == null}): target
 *       DN is byte-equal to source DN.</li>
 *   <li><b>Base-DN substitution</b> ({@code sourceBaseDn != null}):
 *       the DN's tail matching {@code sourceBaseDn} is replaced with
 *       {@code targetBaseDn}. Comparison is DN-canonical via
 *       UnboundID's {@code DN} parser so spacing / case variants
 *       don't cause spurious misses.</li>
 *   <li><b>Out-of-scope DN</b>: the source DN does not sit under
 *       {@code sourceBaseDn}. The mapper returns {@code null}; the
 *       enqueuer treats this as "this write isn't covered by this
 *       link" and skips enqueueing for it.</li>
 * </ul>
 */
public final class DnMapper {

    private DnMapper() {}

    /**
     * @return the mapped target DN, or {@code null} when the source DN
     *         is outside the link's source base scope.
     */
    public static String map(String sourceDn, ReplicationLink link) {
        if (link.getSourceBaseDn() == null) {
            // Identity mapping — return the DN as-is.
            return sourceDn;
        }
        try {
            DN source = new DN(sourceDn);
            DN scope = new DN(link.getSourceBaseDn());
            if (!source.isDescendantOf(scope, /* allowEquals */ true)) {
                return null;
            }
            // Strip the source base suffix, append the target base suffix.
            // DN.toNormalizedString() canonicalises before comparison so
            // mixed-case / whitespace inputs don't accidentally fall out.
            String sourceNorm = source.toNormalizedString();
            String scopeNorm  = scope.toNormalizedString();
            // sourceNorm ends with scopeNorm (possibly equals); the prefix
            // is the part to keep. Identity case (sourceNorm == scopeNorm)
            // means the source DN IS the base, and the target is the
            // target base.
            if (sourceNorm.equals(scopeNorm)) {
                return link.getTargetBaseDn();
            }
            // Otherwise sourceNorm = '<prefix>,<scopeNorm>'.
            String prefix = sourceNorm.substring(0, sourceNorm.length() - scopeNorm.length() - 1);
            return prefix + "," + link.getTargetBaseDn();
        } catch (LDAPException ex) {
            // Unparseable DN — skip this link rather than enqueue an
            // event with the malformed string as the target. Without
            // this guard the enqueuer fans out an event per enabled
            // link, the worker burns its retry budget on every link
            // before dead-lettering, and the per-link FIFO blocks
            // every subsequent valid write behind the wedged head.
            // The source-side write already succeeded; the divergence
            // here surfaces in the operator's monitoring rather than
            // as a queue full of garbage. Returning null is the same
            // signal as "out of scope for this link" — the enqueuer
            // already handles that case.
            return null;
        }
    }
}
