// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.Attribute;

import java.util.List;

/**
 * The output of the membership function for one source entry: either OUT (not a
 * member — absent, wrong objectClass, fails applicability, or unplaceable) or IN
 * with the projected desired target state.
 *
 * @param member       whether the entry is in the sync set's membership.
 * @param identity     the normalized correlation key (may be non-null even when
 *                     OUT, e.g. a present-but-excluded entry).
 * @param targetDn     desired target DN (IN only).
 * @param desiredAttrs projected desired attributes (IN only).
 * @param contentHash  hash over {@code (targetDn, desiredAttrs)} (IN only).
 */
public record MembershipDecision(boolean member,
                                 String identity,
                                 String targetDn,
                                 List<Attribute> desiredAttrs,
                                 byte[] contentHash) {

    public static MembershipDecision out(String identity) {
        return new MembershipDecision(false, identity, null, null, null);
    }

    public static MembershipDecision in(String identity, String targetDn,
                                        List<Attribute> desiredAttrs, byte[] contentHash) {
        return new MembershipDecision(true, identity, targetDn, desiredAttrs, contentHash);
    }
}
