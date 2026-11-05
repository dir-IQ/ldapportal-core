// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import java.util.Locale;
import java.util.Map;

/**
 * What a bulk import may do row by row, on top of the file-level
 * {@code bulk.import} gate: the same per-entry features the single-entry
 * endpoints require. A row that needs a feature the admin lacks is reported
 * as an error on that row; every other row still runs.
 *
 * <ul>
 *   <li>Creating a user needs {@code user.create}; overwriting an existing one
 *       needs {@code user.edit}, and changing a password or account-control
 *       attribute on it needs {@code user.reset_password} /
 *       {@code user.enable_disable} — the same split the user edit endpoint
 *       enforces. A user's initial password is part of creating it.</li>
 *   <li>Creating a group needs {@code group.create_delete}; changing attributes
 *       of an existing group needs {@code group.edit}. Member changes keep
 *       their file-level {@code group.manage_members} check.</li>
 * </ul>
 *
 * <p>For an import replayed after approval, a feature counts only when both
 * the requester and the approver hold it (see
 * {@link LdapOperationService#importPermissions}).</p>
 *
 * @param protectedUserAttributes lower-case attribute name → the permission
 *        (display name) needed to change it on an existing user, for the
 *        attributes this admin may not change
 */
public record BulkImportPermissions(
        boolean createUsers,
        boolean editUsers,
        Map<String, String> protectedUserAttributes,
        boolean createGroups,
        boolean editGroups) {

    /** Every row may run; for callers outside an admin request (and tests). */
    public static final BulkImportPermissions ALL =
            new BulkImportPermissions(true, true, Map.of(), true, true);

    static final String CREATE_USERS  = "Create users";
    static final String EDIT_USERS    = "Edit users";
    static final String RESET_PASSWORDS = "Reset user passwords";
    static final String ENABLE_DISABLE  = "Enable / disable users";
    static final String CREATE_GROUPS = "Create / delete groups";
    static final String EDIT_GROUPS   = "Edit groups";

    static String needs(String action, String permission) {
        return action + " needs the '" + permission + "' permission";
    }

    /**
     * Why an import row may not touch this user, or {@code null} when it may.
     *
     * @param exists        whether the user is already in the directory
     * @param overwrite     whether the import overwrites existing users (else
     *                      an existing user is skipped, which needs nothing)
     * @param written       attributes an overwrite would write (naming
     *                      attributes already excluded)
     */
    String userRowProblem(boolean exists, boolean overwrite, Iterable<String> written) {
        if (!exists) {
            return createUsers ? null : needs("Creating this user", CREATE_USERS);
        }
        if (!overwrite) return null;
        if (!editUsers) return needs("Updating this existing user", EDIT_USERS);
        for (String attr : written) {
            String missing = protectedUserAttributes.get(attributeKey(attr));
            if (missing != null) {
                return needs("Changing " + attr + " on an existing user", missing);
            }
        }
        return null;
    }

    /**
     * Whether {@link #userRowProblem} could refuse an overwrite or a create,
     * i.e. whether it's worth looking up if the user exists.
     */
    boolean restrictsUsers(boolean overwrite) {
        return !createUsers || (overwrite && (!editUsers || !protectedUserAttributes.isEmpty()));
    }

    /** Attribute name without options ({@code userPassword;binary} → {@code userpassword}). */
    static String attributeKey(String attribute) {
        int semi = attribute.indexOf(';');
        String base = semi >= 0 ? attribute.substring(0, semi) : attribute;
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
