// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

/**
 * One LDAP write step in a provisioning plan. Sealed so the executor
 * can pattern-match exhaustively over the three flavours and so
 * implementers can't invent operation types the executor doesn't
 * know how to apply.
 *
 * <p>The three permitted shapes correspond to UnboundID's
 * {@code AddRequest}, {@code ModifyRequest}, and {@code delete(dn)}
 * calls. {@link AddStep} carries {@code List<Attribute>}
 * (what goes on a brand-new entry); {@link ModifyStep} carries
 * {@code List<Modification>} (what changes on an existing entry);
 * {@link DeleteStep} carries only the target DN. A single
 * record with both fields nullable would technically work but
 * the pattern-match readability is worth the extra files.</p>
 *
 * @see PlanExecutor for the dispatch
 */
public sealed interface LdapOperationStep
        permits AddStep, ModifyStep, DeleteStep {

    /** DN being written to. Never null. */
    String targetDn();

    /** What to do when this step fails. Defaults to ABORT for ADD/DELETE. */
    StepFailurePolicy onFailure();
}
