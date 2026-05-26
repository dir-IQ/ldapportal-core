// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.unboundid.ldap.sdk.Attribute;

import java.util.List;

/**
 * {@link LdapOperationStep} that creates a new LDAP entry. Translates
 * to an UnboundID {@code AddRequest(targetDn, attributes)} when
 * {@link PlanExecutor} runs it.
 *
 * <p>{@link Attribute} is the UnboundID type rather than a wrapper —
 * the executor would unwrap a wrapper to the same UnboundID class
 * anyway, and the records here exist to model the plan, not to
 * abstract over the LDAP driver.</p>
 */
public record AddStep(
        String targetDn,
        List<Attribute> attributes,
        StepFailurePolicy onFailure) implements LdapOperationStep {

    public AddStep {
        // Defensive immutability: records are nominally immutable but
        // List<Attribute> is a reference. Copy on construction.
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    /**
     * Convenience: ADD with the default ABORT failure policy. ADDs
     * are usually critical-path; CONTINUE is rarely what you want for
     * a new-entry creation, and COMPENSATE is meaningful only on
     * later steps that need a rollback hook.
     */
    public static AddStep of(String targetDn, List<Attribute> attributes) {
        return new AddStep(targetDn, attributes, StepFailurePolicy.ABORT);
    }
}
