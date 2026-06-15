// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.enums;

/**
 * Lifecycle state of an {@code OutboxEntry}.
 *
 * <pre>
 * PENDING ──(dispatcher claim)──▶ DELIVERING ──success────▶ DELIVERED
 *   ▲                                │
 *   │                                ├─transient (attempts&lt;MAX)─▶ PENDING + backoff
 *   └────────(stale sweeper)─────────┘
 *                                    └─permanent | attempts==MAX ─▶ DEAD_LETTERED
 * </pre>
 *
 * Operator requeue on a DEAD_LETTERED row resets status to PENDING and
 * attempts to 0.
 */
public enum OutboxStatus { PENDING, DELIVERING, DELIVERED, DEAD_LETTERED }
