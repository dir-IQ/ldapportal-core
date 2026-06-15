// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.ResultCode;

/**
 * Normalizes LDAP result codes for the convergent apply path: an operation is
 * "converged" when the target already reflects the desired state, even if the
 * raw op returned an error.
 *
 * <ul>
 *   <li>ADD against an existing entry ({@code ENTRY_ALREADY_EXISTS}) — converge
 *       to a MODIFY (handled by the engine reading the target).</li>
 *   <li>DELETE of a missing entry ({@code NO_SUCH_OBJECT}) — already gone, the
 *       state we wanted.</li>
 *   <li>MODIFY of a missing entry ({@code NO_SUCH_OBJECT}) — needs an ADD
 *       (handled by the engine).</li>
 * </ul>
 *
 * Mirrors the result-code normalization the legacy delivery path used; reused
 * so duplicate/at-least-once triggers and crash-replays are idempotent.
 */
public final class LdapResultInterpreter {

    private LdapResultInterpreter() {
    }

    /** A DELETE has converged when it succeeded or the entry was already gone. */
    public static boolean deleteConverged(ResultCode rc) {
        return rc == ResultCode.SUCCESS || rc == ResultCode.NO_SUCH_OBJECT;
    }

    /** An ADD that returns ENTRY_ALREADY_EXISTS must fall through to a MODIFY. */
    public static boolean addNeedsModify(ResultCode rc) {
        return rc == ResultCode.ENTRY_ALREADY_EXISTS;
    }

    public static boolean success(ResultCode rc) {
        return rc == ResultCode.SUCCESS;
    }
}
