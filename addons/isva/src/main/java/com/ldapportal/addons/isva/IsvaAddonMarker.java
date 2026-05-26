// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

/**
 * Sentinel — presence on the classpath signals the
 * {@code addons/isva} module is loaded. Used by
 * {@link IsvaAddonProbe} and (in P5) by the
 * {@code distribution/community-plus-isva} build to assert the
 * jar shipped what it was supposed to.
 *
 * <p>This class has no behaviour. Its only contract is its fully
 * qualified name ({@code com.ldapportal.addons.isva.IsvaAddonMarker})
 * and its presence in the addon jar. Renaming, moving, or deleting
 * it silently disables the entitlement grant for ISVA — don't.</p>
 */
public final class IsvaAddonMarker {
    private IsvaAddonMarker() {}
}
