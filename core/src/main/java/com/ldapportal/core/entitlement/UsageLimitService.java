// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

/**
 * Enforces per-resource caps declared in the current license's
 * {@link License#limits()} map. Callers use this at resource-creation
 * boundaries to short-circuit before persisting a row that would put
 * the install over its quota.
 *
 * <p>Stateless by design — callers pass the current resource count
 * (usually a repository {@code count()} call inside the same
 * transaction) rather than this service querying for it. That keeps
 * the service free of direct database dependencies and lets each
 * call-site use whatever count query is most accurate for its
 * resource (per-directory profile counts, active-admin-only counts,
 * etc.).</p>
 *
 * <p>Grandfathering semantics: if {@code currentCount} already equals
 * or exceeds the license limit (e.g. a customer downgraded after
 * creating N+1 directories), existing rows stay functional but new
 * creates are rejected. There's no rollback of over-limit state;
 * that's a deliberate product decision documented in
 * {@code docs/edition-boundary.md}.</p>
 */
public interface UsageLimitService {

    /**
     * Throw {@link LimitExceededException} if creating one more row of
     * type {@code type} would put us over the cap. Callers should invoke
     * this immediately before the actual create.
     *
     * @param type         which license-declared cap to check
     * @param currentCount current number of rows of this type (callers
     *                     compute this themselves — usually via a
     *                     repository count() inside the @Transactional)
     */
    void requireWithinLimit(LimitType type, long currentCount);
}
