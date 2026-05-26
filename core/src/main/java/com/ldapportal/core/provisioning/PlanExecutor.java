// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.ModifyRequest;
import com.unboundid.ldap.sdk.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Walks the steps of a {@code *Plan} and applies each one against
 * the LDAP. Borrows the existing connection-pooling and
 * error-classification machinery from {@link LdapConnectionFactory}.
 *
 * <p>Per-step semantics driven by {@link StepFailurePolicy}:
 * <ul>
 *   <li>{@link StepFailurePolicy#ABORT ABORT} (the default for ADD /
 *       MODIFY / DELETE) — failure surfaces as an {@link LdapOperationException}
 *       annotated with which step (1-based) of how many failed.
 *       Earlier successful steps stay applied; partial state is the
 *       cost of non-transactional LDAP.</li>
 *   <li>{@link StepFailurePolicy#CONTINUE CONTINUE} — log a warning,
 *       move to the next step. Used for best-effort writes whose
 *       failure shouldn't break the primary operation.</li>
 *   <li>{@link StepFailurePolicy#COMPENSATE COMPENSATE} — run the
 *       plan's {@code compensation} block as a best-effort cleanup
 *       of earlier completed steps. The original exception is what
 *       propagates; compensation outcomes are logged. Only
 *       {@link UserCreatePlan} carries a compensation block today
 *       (linked-mode user create whose secUser step fails).</li>
 * </ul>
 *
 * <p>Each plan-typed {@code execute} method opens one LDAP
 * connection from the pool for the whole plan — multiple steps
 * targeting different DNs share the same connection. That's the
 * right shape for our scenarios (cheap; reads use the same pool)
 * and avoids burning pool slots on every step.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanExecutor {

    private final LdapConnectionFactory connectionFactory;

    public void execute(DirectoryConnection dir, UserCreatePlan plan) {
        executeWithCompensation(dir, plan.steps(), plan.compensation().orElse(null));
    }

    public void execute(DirectoryConnection dir, DeletePlan plan) {
        executeWithCompensation(dir, plan.steps(), null);
    }

    public void execute(DirectoryConnection dir, PasswordPlan plan) {
        executeWithCompensation(dir, plan.steps(), null);
    }

    /**
     * Group-membership executor. Returns the {@link GroupMemberPlan}
     * itself rather than void so the caller can detect a refusal
     * (refused plans have no steps to execute and must be surfaced
     * as a 422 rather than silently dropped).
     *
     * @throws ProvisioningRefusedException when {@code plan.isRefused()}
     */
    public void execute(DirectoryConnection dir, GroupMemberPlan plan) {
        if (plan.isRefused()) {
            throw new ProvisioningRefusedException(plan.refusalReason().orElseThrow());
        }
        executeWithCompensation(dir, plan.steps(), null);
    }

    // ── core walker ──────────────────────────────────────────────────

    private void executeWithCompensation(DirectoryConnection dir,
                                         List<LdapOperationStep> steps,
                                         List<LdapOperationStep> compensation) {
        if (steps.isEmpty()) {
            return;
        }
        connectionFactory.withConnection(dir, conn -> {
            int total = steps.size();
            for (int i = 0; i < total; i++) {
                LdapOperationStep step = steps.get(i);
                int stepNumber = i + 1;  // 1-based for human-readable error messages
                try {
                    applyStep(conn, step);
                } catch (LDAPException e) {
                    // ABORT / COMPENSATE throw out of handleFailure and so out
                    // of this lambda; CONTINUE returns normally and we fall
                    // through to the next iteration. Don't add a `return null`
                    // here — it would short-circuit CONTINUE the same way as
                    // the throwing paths.
                    handleFailure(conn, step, e, stepNumber, total, compensation);
                }
            }
            return null;
        });
    }

    private void applyStep(LDAPConnection conn, LdapOperationStep step) throws LDAPException {
        LDAPResult result = switch (step) {
            case AddStep add ->
                    conn.add(new AddRequest(add.targetDn(), add.attributes()));
            case ModifyStep mod ->
                    conn.modify(new ModifyRequest(mod.targetDn(), mod.mods()));
            case DeleteStep del ->
                    conn.delete(del.targetDn());
        };
        checkResult(result, describe(step), step.targetDn());
    }

    private void handleFailure(LDAPConnection conn,
                               LdapOperationStep step,
                               LDAPException originalFailure,
                               int stepNumber,
                               int totalSteps,
                               List<LdapOperationStep> compensation) {
        String context = String.format("step %d of %d (%s on %s)",
                stepNumber, totalSteps, describe(step), step.targetDn());

        switch (step.onFailure()) {
            case CONTINUE -> {
                log.warn("Plan {} failed (CONTINUE policy — proceeding to next step): {}",
                        context, originalFailure.getMessage());
                // No-op — outer loop already moved on.
            }
            case COMPENSATE -> {
                log.error("Plan {} failed — running compensation block", context);
                runCompensation(conn, compensation, originalFailure);
                throw wrap(originalFailure, context, "compensation attempted");
            }
            case ABORT -> {
                throw wrap(originalFailure, context, "aborted");
            }
        }
    }

    private void runCompensation(LDAPConnection conn,
                                  List<LdapOperationStep> compensation,
                                  LDAPException originalFailure) {
        if (compensation == null || compensation.isEmpty()) {
            log.warn("COMPENSATE failure policy fired but plan has no compensation block; "
                    + "leaving partial state intact. Manual repair may be needed.");
            return;
        }
        for (LdapOperationStep step : compensation) {
            try {
                applyStep(conn, step);
                log.info("Compensation step succeeded: {} on {}",
                        describe(step), step.targetDn());
            } catch (LDAPException e) {
                // Compensation is best-effort by definition — log and continue
                // so a stuck compensation step doesn't mask the original error.
                log.error("Compensation step failed (manual repair may be needed): "
                        + "{} on {} — {}",
                        describe(step), step.targetDn(), e.getMessage());
            }
        }
    }

    private LdapOperationException wrap(LDAPException ldapException,
                                         String context,
                                         String outcome) {
        return new LdapOperationException(
                "Provisioning plan " + outcome + ": " + context + " — "
                        + ldapException.getMessage(),
                ldapException);
    }

    private void checkResult(LDAPResult result, String operation, String dn) throws LDAPException {
        if (result.getResultCode() != ResultCode.SUCCESS) {
            // Re-throw as LDAPException so the outer loop's failure handler
            // sees a uniform exception type. The caller's wrap() turns it
            // into an LdapOperationException with step context attached.
            throw new LDAPException(result.getResultCode(),
                    operation + " on " + dn + " returned " + result.getResultCode());
        }
    }

    private static String describe(LdapOperationStep step) {
        return switch (step) {
            case AddStep    a -> "ADD";
            case ModifyStep m -> "MODIFY";
            case DeleteStep d -> "DELETE";
        };
    }
}
