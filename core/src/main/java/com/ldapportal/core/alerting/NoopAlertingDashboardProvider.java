// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.alerting;

import java.util.UUID;

/**
 * Default {@link AlertingDashboardProvider} used when no alerting
 * module is loaded. Reports that no directory has rules — accurate,
 * because with no alerting there are none.
 *
 * <p>Registered via {@link com.ldapportal.core.CoreNoopSpiAutoConfiguration}.</p>
 */
public class NoopAlertingDashboardProvider implements AlertingDashboardProvider {

    @Override
    public boolean hasRulesForDirectory(UUID directoryId) {
        return false;
    }
}
