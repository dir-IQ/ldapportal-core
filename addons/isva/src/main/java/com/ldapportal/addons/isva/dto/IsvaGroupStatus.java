// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

/**
 * Whether a group is imported into IVIA.
 *
 * @param registered      a secGroup entry in the group container points at the group
 * @param registrationDn  that entry's DN, when registered
 * @param canImport       the directory has a group container (a management DIT),
 *                        so an import can be written
 * @param suggestedName   the IVIA group name an import would use by default (the
 *                        group's {@code cn}); null when registered
 */
public record IsvaGroupStatus(boolean registered, String registrationDn, boolean canImport,
                              String suggestedName) {
}
