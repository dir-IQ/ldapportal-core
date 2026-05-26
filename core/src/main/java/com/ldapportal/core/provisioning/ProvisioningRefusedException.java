// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

/**
 * Thrown when a {@link GroupMemberPlan} returns
 * {@link GroupMemberPlan#refuse}. Carries the human-readable
 * refusal reason that the controller layer can map to a 422
 * response body.
 *
 * <p>The exception's existence is the loud-fail mechanism that
 * makes refusals visible to operators — silently dropping a
 * membership write because some interceptor disagreed with it
 * would be a far worse failure mode than a 422 with a clear
 * explanation.</p>
 */
public class ProvisioningRefusedException extends RuntimeException {

    public ProvisioningRefusedException(String message) {
        super(message);
    }
}
