// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAction;

import java.util.List;

/**
 * What one live recompute did to an entry.
 *
 * @param action   the outcome, in the preview's vocabulary ({@code ERROR} for a failed write)
 * @param targetDn the target entry it concerned, when known
 * @param reason     why it was held for review or failed, else {@code null}
 * @param attributes the attributes a write modified on the existing target entry, in the
 *                   order first modified (empty for an add, a delete, or no modify)
 */
public record RecomputeOutcome(SyncPreviewAction action, String targetDn, String reason, List<String> attributes) {

    public RecomputeOutcome {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    public RecomputeOutcome(SyncPreviewAction action, String targetDn, String reason) {
        this(action, targetDn, reason, List.of());
    }
}
