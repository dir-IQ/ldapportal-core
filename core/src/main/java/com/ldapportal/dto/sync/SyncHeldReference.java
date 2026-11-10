// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.SyncHeldStatus;

/**
 * One reference value the sync holds back: the member holding it (its target
 * DN, source DN and identity), the attribute, the source DN the value names, and
 * what the verify found out about that entry.
 */
public record SyncHeldReference(String entryDn, String sourceDn, String identity, String attribute,
                                String reference, SyncHeldStatus status) {
}
