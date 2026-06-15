// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Liveness/health of a sync link's changelog-capture poll loop.
 *
 * <ul>
 *   <li>{@code HEALTHY} — polling, cursor advancing.</li>
 *   <li>{@code LAGGING} — falling behind the source head (lag &gt; threshold).</li>
 *   <li>{@code STALLED} — repeated poll errors.</li>
 *   <li>{@code GAP_DETECTED} — the cursor fell off the bottom of the changelog
 *       (purged before it was read); a reconcile is triggered to re-derive state.</li>
 *   <li>{@code CURSOR_RESET} — the source changelog restarted (head &lt; cursor).</li>
 *   <li>{@code DISABLED_CONFIG_ERROR} — misconfiguration; polling suspended.</li>
 * </ul>
 */
public enum SyncChangelogHealth {
    HEALTHY,
    LAGGING,
    STALLED,
    GAP_DETECTED,
    CURSOR_RESET,
    DISABLED_CONFIG_ERROR
}
