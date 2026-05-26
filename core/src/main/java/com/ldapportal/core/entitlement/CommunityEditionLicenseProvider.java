// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * No-license fallback {@link LicenseProvider}. Returns a
 * {@link License} with edition {@link Edition#COMMUNITY} and no add-on
 * entitlements — matching {@link Edition#baselineEntitlements()} for
 * that edition (the empty set).
 *
 * <p>{@link LicenseAutoConfiguration} picks this provider whenever no
 * signed license file is configured, on <em>either</em> distribution:
 * <ul>
 *   <li>Community JAR — ee/ classes aren't on the classpath, so this is
 *       the only meaningful state.</li>
 *   <li>Commercial JAR with no license file — an unconfigured commercial
 *       install runs with community baseline entitlements rather than
 *       silently unlocking everything. Installing a signed license hands
 *       control to {@link FileLicenseProvider}.</li>
 * </ul>
 *
 * <p>Granting nothing by default is deliberate: on the community JAR the
 * ee/ implementation classes don't exist, so granting e.g. GOVERNANCE
 * via /me would tell the frontend to show menus for features with no
 * backend; on the commercial JAR it closes the "forgot to install the
 * license = full entitlements" footgun.</p>
 */
public class CommunityEditionLicenseProvider implements LicenseProvider {

    @Override
    public License current() {
        return new License(
                /* customerId */ null,
                Edition.COMMUNITY,
                /* addOns    */ Set.of(),
                /* limits    */ Map.of(),
                /* issuedAt  */ Instant.EPOCH,
                /* expiresAt */ Instant.MAX,  // community has no expiry
                /* signature */ null          // unsigned
        );
    }

    @Override
    public String source() {
        return "community baseline (no license file configured)";
    }
}
