// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

/**
 * The two LDAP topologies ISVA full-user mode supports — see the
 * design doc's "Two full-mode topologies" section.
 */
public enum IsvaTopologyMode {

    /**
     * Single LDAP entry per user under the provisioning OU,
     * carrying both demographic attributes and the {@code secUser}
     * overlay. Simpler; common in newer deployments.
     */
    INLINE,

    /**
     * Two LDAP entries per user in different DITs: a demographic
     * entry under the provisioning OU, plus a paired secUser entry
     * under a separate management DIT (typically
     * {@code secAuthority=Default,o=ibm,c=us}). The secUser carries
     * a {@code secDN} back-reference to the demographic.
     * Implementation: P3.
     */
    LINKED
}
