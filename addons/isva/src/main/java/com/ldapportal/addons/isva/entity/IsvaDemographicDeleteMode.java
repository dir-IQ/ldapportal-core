// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

/**
 * Linked-mode-only: when soft-deleting (delete policy = DISABLE),
 * what to do with the demographic entry.
 *
 * <ul>
 *   <li>{@link #LEAVE} (default) — touch only the secUser entry;
 *       demographic entry stays as-is. Least-surprise; lets
 *       customers with site-local conventions manage the demographic
 *       side themselves. The interceptor's path for v1.</li>
 *   <li>{@link #DISABLE_AND_MARK} — also annotate the demographic
 *       entry with a customer-specific marker. v1 logs a TODO and
 *       treats it as {@link #LEAVE} until a real customer surfaces
 *       what marker they want written. Once known, the second
 *       MODIFY lands as a Phase-1.1 follow-up.</li>
 * </ul>
 */
public enum IsvaDemographicDeleteMode {
    LEAVE,
    DISABLE_AND_MARK
}
