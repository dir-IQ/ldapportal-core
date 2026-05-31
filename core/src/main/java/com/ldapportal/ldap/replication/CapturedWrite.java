// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationOperationType;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;

import java.util.List;

/**
 * Immutable snapshot of a single successful LDAP write, captured by
 * {@link ReplicatingLdapInterface} and handed to
 * {@link ReplicationEnqueuer}. Carries everything the enqueuer needs to
 * fan out one event per matching link.
 *
 * <p>The source-side correlation id is <em>not</em> carried here: the
 * enqueuer runs synchronously on the originating write thread, so it reads
 * the active {@link com.ldapportal.core.observability.CorrelationContext}
 * scope directly — and does so only after confirming there are links to
 * replicate, keeping the no-link hot path free of any per-write id work.
 */
public record CapturedWrite(
        ReplicationOperationType operation,
        String dn,
        List<Attribute> attributes,
        List<Modification> modifications,
        CapturedWrite.ModifyDnParts modifyDn) {

    public record ModifyDnParts(String newRdn, boolean deleteOldRdn, String newSuperiorDn) {}

    public static CapturedWrite add(String dn, List<Attribute> attributes) {
        return new CapturedWrite(ReplicationOperationType.ADD, dn,
                List.copyOf(attributes), null, null);
    }

    public static CapturedWrite modify(String dn, List<Modification> modifications) {
        return new CapturedWrite(ReplicationOperationType.MODIFY, dn,
                null, List.copyOf(modifications), null);
    }

    public static CapturedWrite delete(String dn) {
        return new CapturedWrite(ReplicationOperationType.DELETE, dn, null, null, null);
    }

    public static CapturedWrite modifyDn(String dn, String newRdn,
                                          boolean deleteOldRdn, String newSuperiorDn) {
        return new CapturedWrite(ReplicationOperationType.MODIFY_DN, dn, null, null,
                new ModifyDnParts(newRdn, deleteOldRdn, newSuperiorDn));
    }
}
