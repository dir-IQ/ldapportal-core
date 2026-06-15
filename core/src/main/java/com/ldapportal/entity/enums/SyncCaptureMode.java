// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * How a {@link com.ldapportal.entity.SyncLink} detects source changes.
 * Exclusive per link — flipping it changes <em>how</em> changes are seen,
 * not <em>what</em> is synced.
 *
 * <ul>
 *   <li>{@code APP_INTERCEPT} — capture writes made through the portal.</li>
 *   <li>{@code CHANGELOG} — poll the source directory's external changelog.</li>
 * </ul>
 */
public enum SyncCaptureMode {
    APP_INTERCEPT,
    CHANGELOG
}
