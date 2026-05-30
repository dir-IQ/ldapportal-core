// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationOperationType;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;

import java.util.List;

/**
 * A successful LDAP mutation captured by {@code ReplicatingLDAPInterface}
 * before it reaches the {@link com.ldapportal.ldap.replication.ReplicationEnqueuer}.
 *
 * <p>Source-side DN is kept verbatim from the original request — the
 * enqueuer applies DN + attribute mapping when it builds the persisted
 * payload. Keeping the wrapper / enqueuer split this way means the
 * wrapper doesn't need any link-specific knowledge; it just records.
 *
 * <p>Exactly one of {@code attributes} / {@code modifications} /
 * {@code modifyDn} is non-null depending on {@link #operation}:
 * <ul>
 *   <li>{@code ADD}       — attributes set; modifications + modifyDn null.</li>
 *   <li>{@code MODIFY}    — modifications set; the rest null.</li>
 *   <li>{@code DELETE}    — all three null.</li>
 *   <li>{@code MODIFY_DN} — modifyDn set; the rest null.</li>
 * </ul>
 */
public record CapturedWrite(
        ReplicationOperationType operation,
        String dn,
        List<Attribute> attributes,
        List<Modification> modifications,
        ModifyDnParts modifyDn) {

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
