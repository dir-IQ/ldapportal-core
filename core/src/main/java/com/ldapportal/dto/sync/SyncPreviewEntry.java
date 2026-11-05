// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/**
 * The predicted outcome of a reconcile for one identity.
 *
 * @param identity          the entry's stable identity (e.g. its entryUUID)
 * @param sourceDn          source DN, or {@code null} when the source entry is gone
 * @param targetDn          the target DN the action applies to (the new DN for a MOVE)
 * @param previousTargetDn  for a MOVE, the current target DN; otherwise {@code null}
 * @param action            what would happen
 * @param reason            why, for REVIEW / FORGET; otherwise {@code null}
 * @param changes           attribute changes for ADD / MODIFY / MOVE; empty otherwise
 */
public record SyncPreviewEntry(
        String identity,
        String sourceDn,
        String targetDn,
        String previousTargetDn,
        SyncPreviewAction action,
        String reason,
        List<SyncPreviewAttributeChange> changes) {
}
