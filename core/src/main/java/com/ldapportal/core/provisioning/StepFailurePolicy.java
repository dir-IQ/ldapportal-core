// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

/**
 * What to do when a step in a provisioning plan fails.
 *
 * <p>Plans declare a per-step policy because the right answer varies:
 * a fire-and-forget audit annotation should be CONTINUE; the first
 * write of a two-write linked-mode create should be ABORT; the
 * second of those two writes — the one whose failure leaves a
 * recoverable orphan — should be COMPENSATE.</p>
 */
public enum StepFailurePolicy {

    /**
     * Stop executing the plan; bubble the LDAP exception to the
     * caller with "step N of M failed" context attached. Earlier
     * steps that succeeded stay applied — partial state is the
     * cost of a non-transactional LDAP. The default policy.
     */
    ABORT,

    /**
     * Log the failure and proceed to the next step. Useful for
     * best-effort writes (e.g. audit-side annotations whose
     * absence shouldn't break a primary operation).
     */
    CONTINUE,

    /**
     * Stop executing the plan, then run the plan's
     * {@code compensation} block as a best-effort cleanup of
     * earlier steps that succeeded. Used when failure of step N
     * leaves step N-1's state user-visible and recoverable —
     * e.g. the demographic entry created by step 1 of a
     * linked-mode user-create when step 2 (the secUser entry)
     * fails. The original exception is the one propagated; the
     * compensation outcome is annotated on it for diagnostics.
     */
    COMPENSATE
}
