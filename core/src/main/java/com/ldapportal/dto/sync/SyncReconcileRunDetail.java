// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;
import java.util.Map;

/**
 * Everything recorded about one reconcile run.
 *
 * @param run            the run's summary and counts
 * @param settings       the set's settings at run time, or {@code null} for a run
 *                       recorded before settings were kept
 * @param failureReasons failed / faulted entries grouped by reason, most frequent first
 * @param samples        up to {@code sampleLimit} target DNs per outcome
 *                       ({@link SyncPreviewAction} name → DNs), in processing order
 * @param sampleLimit    the cap on DNs kept per outcome
 */
public record SyncReconcileRunDetail(
        SyncReconcileRunResponse run,
        SyncRunSettings settings,
        List<SyncRunFailureReason> failureReasons,
        Map<String, List<String>> samples,
        int sampleLimit) {
}
