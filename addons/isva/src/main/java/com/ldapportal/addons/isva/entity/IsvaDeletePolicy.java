// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

/**
 * What the interceptor does on a user delete request.
 *
 * <ul>
 *   <li>{@link #DISABLE} (default) — rewrite the LDAP {@code DEL}
 *       as a {@code MODIFY} setting {@code secAcctValid = FALSE}
 *       on the secUser side of the identity. Preserves policy
 *       associations + audit trail. This is the ISVA-supported
 *       way to "remove" a user.</li>
 *   <li>{@link #HARD_DELETE} — actually issue an LDAP {@code DEL}.
 *       Destroys policy associations + audit linkage. Available
 *       behind an explicit operator confirm dialog for the
 *       destroy-it-anyway case.</li>
 * </ul>
 */
public enum IsvaDeletePolicy {
    DISABLE,
    HARD_DELETE
}
