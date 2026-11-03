// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * How much of each run a sync set's history keeps.
 *
 * <ul>
 *   <li>{@code SUMMARY} — the run's counts and failure reasons only.</li>
 *   <li>{@code DETAILED} — the summary plus every changed or flagged entry's DN
 *       (sampled per outcome on the run, the full list downloadable as CSV). A
 *       large run stores one row per entry.</li>
 * </ul>
 */
public enum SyncLoggingLevel {
    SUMMARY,
    DETAILED
}
