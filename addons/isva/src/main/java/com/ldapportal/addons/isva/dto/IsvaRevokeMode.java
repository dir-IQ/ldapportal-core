// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

/**
 * Which flavour of revoke to apply.
 *
 * <ul>
 *   <li>{@link #SOFT} — mark the IVIA account invalid + expired. The
 *       account stays in LDAP for audit / reconcile. Reversible by
 *       a subsequent grant (which restores defaults). Maps to
 *       {@code USER_DISABLE} in the audit table.</li>
 *   <li>{@link #HARD} — remove the IVIA account outright. Linked mode
 *       deletes the paired secUser entry; inline mode strips the
 *       {@code secUser} objectClass + {@code sec*} overlay. The
 *       demographic identity stays — this verb is decoupled from
 *       demographic delete. Maps to {@code USER_DELETE}.</li>
 * </ul>
 */
public enum IsvaRevokeMode {
    SOFT, HARD
}
