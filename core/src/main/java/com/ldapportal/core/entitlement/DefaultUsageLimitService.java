// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Reads the current license's {@link License#limitFor(LimitType)} via
 * {@link EntitlementService} and compares it against the caller-supplied
 * {@code currentCount}. Throws if {@code currentCount + 1} would exceed
 * the cap.
 *
 * <p>Unlimited ({@link Long#MAX_VALUE}) short-circuits to a no-op, so
 * this check is cheap enough to run on every create endpoint without
 * measurable overhead.</p>
 */
@Service
@RequiredArgsConstructor
public class DefaultUsageLimitService implements UsageLimitService {

    private final EntitlementService entitlementService;

    @Override
    public void requireWithinLimit(LimitType type, long currentCount) {
        License lic = entitlementService.current();
        long max = lic.limitFor(type);

        // Unlimited — skip immediately. Most installs have no limits set
        // (settings-derived licenses always emit an empty limits map), so
        // this short-circuit is the common path.
        if (max == Long.MAX_VALUE) return;

        if (currentCount >= max) {
            throw new LimitExceededException(type, currentCount, max, lic.edition());
        }
    }
}
