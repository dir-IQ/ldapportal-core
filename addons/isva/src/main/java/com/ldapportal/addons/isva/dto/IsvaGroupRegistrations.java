// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import java.util.List;

/**
 * Which of the asked-about groups are imported into IVIA.
 *
 * @param enabled    the directory has an active IVIA integration (when false,
 *                   the page shows no IVIA controls)
 * @param canImport  the directory has a group container, so groups can be imported
 * @param registered the asked-about DNs (as sent) that are imported
 */
public record IsvaGroupRegistrations(boolean enabled, boolean canImport, List<String> registered) {
}
