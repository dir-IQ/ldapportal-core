// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import java.util.List;
import java.util.Optional;

/**
 * Result of {@link ProvisioningInterceptor#planGroupMembership} —
 * either an ordered list of LDAP operations (proceed) or a
 * {@code refusalReason} (don't proceed, return 4xx with the reason).
 *
 * <p>The refusal path exists because some interceptors validate
 * the target group's shape before allowing membership writes. The
 * ISVA interceptor, for example, refuses when the target group
 * lacks {@code objectClass: secGroup} — the membership would
 * succeed at the LDAP level but be silently ignored by ISVA, so
 * a loud refusal is the right answer.</p>
 *
 * <p>Exactly one of {@code steps} (non-empty) or {@code refusalReason}
 * (present) is meaningful per plan. The constructor enforces this.</p>
 */
public record GroupMemberPlan(
        List<LdapOperationStep> steps,
        Optional<String> refusalReason) {

    public GroupMemberPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        refusalReason = refusalReason == null ? Optional.empty() : refusalReason;
        // Mutual exclusion: refusing means no steps.
        if (refusalReason.isPresent() && !steps.isEmpty()) {
            throw new IllegalArgumentException(
                    "GroupMemberPlan cannot both refuse and carry steps");
        }
    }

    public boolean isRefused() {
        return refusalReason.isPresent();
    }

    public static GroupMemberPlan proceed(LdapOperationStep step) {
        return new GroupMemberPlan(List.of(step), Optional.empty());
    }

    public static GroupMemberPlan refuse(String reason) {
        return new GroupMemberPlan(List.of(), Optional.of(reason));
    }
}
