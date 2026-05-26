// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

/**
 * Which DN gets written into a group's {@code member} attribute
 * when adding membership. Per-deployment in ISVA — some shops
 * point groups at demographic DNs, some at secUser DNs. The
 * operator picks at config time; the probe surfaces a recommendation
 * but never auto-picks because wrong inference here silently
 * corrupts ACLs.
 *
 * <p>Inline-mode deployments only have one DN per user, so this
 * field has no effect on them.</p>
 */
public enum IsvaGroupMemberTarget {

    /**
     * {@code member} values are demographic DNs
     * (e.g. {@code uid=alice,ou=people,dc=corp,dc=com}). Common
     * in shops with an HR-fed demographic LDAP that pre-dates ISVA.
     */
    DEMOGRAPHIC_DN,

    /**
     * {@code member} values are secUser DNs
     * (e.g. {@code secUUID=...,secAuthority=Default,o=ibm,c=us}).
     * The interceptor does a one-time secDN lookup per group write
     * to resolve the secUser DN.
     */
    SECUSER_DN
}
