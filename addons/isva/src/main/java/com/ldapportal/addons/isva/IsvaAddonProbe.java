// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.core.entitlement.AddonProbe;
import com.ldapportal.core.entitlement.Entitlement;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Grants {@link Entitlement#VENDOR_INTEGRATIONS_ISVA} unconditionally
 * whenever this bean exists. The bean only exists when the
 * {@code addons/isva} jar is on the classpath (this class is
 * registered by component-scan from the addon's own package), so
 * presence of the probe and presence of the addon are the same
 * fact — no further runtime check needed.
 *
 * <p>{@link com.ldapportal.core.entitlement.AddonProbingLicenseProvider}
 * picks the probe up via Spring's {@code List<AddonProbe>} injection
 * and unions its entitlements into the final license.</p>
 */
@Component
public class IsvaAddonProbe implements AddonProbe {

    @Override
    public Set<Entitlement> probedEntitlements() {
        return Set.of(Entitlement.VENDOR_INTEGRATIONS_ISVA);
    }

    @Override
    public String addonName() {
        return "isva";
    }
}
