// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * How a {@code ReplicationLink} detects source-side changes. Exclusive
 * per link — flipping it changes <em>how</em> changes are detected, not
 * <em>what</em> is replicated (DN/attribute mapping, dispatch, and
 * reconciliation are unchanged).
 *
 * <p>See {@code docs/plans/2026-06-03-changelog-replication-design.md} §0.3.
 */
public enum ReplicationCaptureMode {

    /**
     * Capture source-side writes in-app, through the
     * {@code ReplicatingLdapInterface} wrapper. The original v1 mode;
     * blind to out-of-band writes (native consoles, scripts, other IAM
     * tools) that don't go through the portal.
     */
    APP_INTERCEPT,

    /**
     * Poll the source directory's external changelog ({@code cn=changelog}
     * on OUD) and reconstruct each change into the replication queue.
     * Closes the out-of-band write gap.
     */
    CHANGELOG
}
