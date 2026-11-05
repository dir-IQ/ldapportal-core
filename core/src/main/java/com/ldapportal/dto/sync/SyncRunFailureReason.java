// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

/**
 * Failed or faulted entries of a reconcile run that share a reason.
 *
 * @param reason  the reason without its entry-specific detail, e.g.
 *                {@code "apply failed: NO_SUCH_OBJECT"}
 * @param count   entries with this reason
 * @param example the full reason of one of them (server diagnostic included)
 */
public record SyncRunFailureReason(String reason, int count, String example) {
}
