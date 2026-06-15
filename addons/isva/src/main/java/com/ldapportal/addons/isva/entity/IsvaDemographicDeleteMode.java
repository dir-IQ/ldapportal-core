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
 *       entry by writing the directory's own configured enable/disable
 *       attribute (its disable value), e.g. AD
 *       {@code userAccountControl=514} or {@code nsAccountLock=TRUE}.
 *       The interceptor adds a second MODIFY (after the secUser disable)
 *       against the demographic DN. If the directory has no enable/disable
 *       attribute configured, the delete is refused rather than silently
 *       treated as {@link #LEAVE}.</li>
 * </ul>
 */
public enum IsvaDemographicDeleteMode {
    LEAVE,
    DISABLE_AND_MARK
}
