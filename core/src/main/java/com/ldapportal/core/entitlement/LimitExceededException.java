// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.Getter;

/**
 * Thrown by {@link UsageLimitService#requireWithinLimit} when a create
 * operation would push a resource count past the current license's
 * per-resource cap.
 *
 * <p>Mapped to HTTP 402 (Payment Required) by the global exception
 * handler. The response body carries {@link #getLimitType()},
 * {@link #getCurrentCount()}, {@link #getMaximum()}, and
 * {@link #getCurrentEdition()} so the frontend can render an upgrade
 * prompt with accurate numbers.</p>
 *
 * <p>Distinct from {@link EntitlementMissingException} — that one
 * means "this feature isn't available in your tier at all." This one
 * means "the feature is available, you've just reached your quota."
 * Both are 402s but with different {@code code} fields on the
 * ProblemDetail so the frontend routes to the right modal.</p>
 */
@Getter
public class LimitExceededException extends RuntimeException {

    private final LimitType limitType;
    private final long currentCount;
    private final long maximum;
    private final Edition currentEdition;

    public LimitExceededException(LimitType limitType,
                                   long currentCount,
                                   long maximum,
                                   Edition currentEdition) {
        super(describeFailure(limitType, currentCount, maximum, currentEdition));
        this.limitType = limitType;
        this.currentCount = currentCount;
        this.maximum = maximum;
        this.currentEdition = currentEdition;
    }

    private static String describeFailure(LimitType type,
                                           long current,
                                           long max,
                                           Edition edition) {
        return "License limit reached for " + type
                + " (" + current + "/" + max + ", edition: " + edition
                + "). Upgrade to lift this cap.";
    }
}
