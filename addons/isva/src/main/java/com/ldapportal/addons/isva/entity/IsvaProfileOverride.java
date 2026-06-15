// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

/**
 * Per-profile ISVA override. Narrowing-only — layered on the
 * per-directory {@code enabled} flag, which stays the authoritative
 * on/kill-switch.
 *
 * <ul>
 *   <li>{@link #INHERIT} (default) — follow the directory. A profile
 *       with no override row is treated as {@code INHERIT}.</li>
 *   <li>{@link #FORCE_OFF} — fully exempt this profile from ISVA in an
 *       otherwise ISVA-enabled directory. The interceptor is a
 *       complete no-op for the profile's entries across create /
 *       delete / password / group (plain LDAP, as if ISVA weren't
 *       installed).</li>
 * </ul>
 *
 * <p>There is deliberately no {@code FORCE_ON}: a profile can only
 * narrow the directory setting, never widen it.</p>
 */
public enum IsvaProfileOverride {
    INHERIT,
    FORCE_OFF
}
