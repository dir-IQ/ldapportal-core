// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.time.OffsetDateTime;

/**
 * What a sync set's inventory actions are doing: its latest reconcile run and
 * its latest content verification, each with live progress while it runs.
 *
 * @param reconcile the latest reconcile run (not an incremental pass), or {@code null}
 * @param verify    the latest content verification, or {@code null}
 * @param asOf      the server's time when this was read, so a client can age the
 *                  runs' timestamps without trusting its own clock
 */
public record SyncSetActivity(SyncReconcileRunResponse reconcile, SyncVerifyProgressStatus verify,
                              OffsetDateTime asOf) {
}
