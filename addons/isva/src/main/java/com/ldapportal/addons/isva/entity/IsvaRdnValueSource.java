// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

/**
 * Where the linked-mode secUser RDN's <em>value</em> comes from —
 * decoupled from the RDN attribute <em>name</em>
 * ({@link VendorIntegrationIsvaConfig#getSecuserRdnAttribute()}).
 *
 * <p>Stock IVIA deployments name secUser entries on {@code secUUID}
 * with a generated value, or {@code secLogin} mirroring the user's
 * {@code uid}. Splitting the value source from the attribute name lets
 * a non-stock deployment pair an arbitrary RDN attribute (e.g.
 * {@code principalName}, defined by the {@code eUser} class) with the
 * {@code uid} value — a combination the old name-implies-value
 * {@code switch} couldn't express.</p>
 *
 * <p>Linked-mode-only: inline mode reuses the demographic entry's own
 * RDN, so this has no effect there.</p>
 */
public enum IsvaRdnValueSource {

    /** A freshly generated {@code UUID} per user — opaque, immutable,
     * collision-free. The default; pairs naturally with {@code secUUID}. */
    GENERATED_UUID,

    /** The user's {@code uid} attribute value — human-readable, but
     * ties the secUser DN to the login (a uid rename forces a directory
     * rename). Pairs with {@code secLogin} or any custom login-named
     * RDN attribute such as {@code principalName}. */
    UID
}
