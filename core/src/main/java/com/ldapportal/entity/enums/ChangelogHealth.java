// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Health of a changelog-capture link's poll loop, surfaced per link on
 * {@code ReplicationLinkResponse} and the Directory Sync dashboard so a
 * degraded link is obvious at a glance rather than buried.
 *
 * <p>See {@code docs/plans/2026-06-03-changelog-replication-design.md} §7A.7.
 */
public enum ChangelogHealth {

    /** Polling normally; lag within threshold. */
    HEALTHY,

    /** Lag (or oldest-undelivered age) exceeds the configured threshold. */
    LAGGING,

    /** {@code lastPolledAt} is older than N×interval while the source head advanced. */
    STALLED,

    /** Entries were trimmed before we read them ({@code cursor + 1 < firstChangeNumber}). */
    GAP_DETECTED,

    /** Source changelog was reinitialized ({@code lastChangeNumber < cursor}); awaits operator reseed. */
    CURSOR_RESET,

    /** Polling disabled after a persistent config/connection error; awaits operator re-enable. */
    DISABLED_CONFIG_ERROR
}
