// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/**
 * One attribute's planned change: the values that would be removed from and
 * added to the target entry. Password attributes are redacted and binary values
 * summarised; each list is capped, with the remainder counted.
 *
 * @param attribute      attribute name
 * @param removed        values that would be removed (empty for a new attribute)
 * @param added          values that would be added (empty when the attribute is removed)
 * @param omittedRemoved values beyond the cap not listed in {@code removed}
 * @param omittedAdded   values beyond the cap not listed in {@code added}
 */
public record SyncPreviewAttributeChange(
        String attribute,
        List<String> removed,
        List<String> added,
        int omittedRemoved,
        int omittedAdded) {
}
