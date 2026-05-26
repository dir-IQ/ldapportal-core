// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.hr;

import java.util.UUID;

/**
 * Default {@link HrDashboardProvider} used when no HR module is loaded.
 * Reports that no directory has a connection — accurate, because
 * without ee.hr there is no HR infrastructure to connect.
 *
 * <p>Registered via {@link com.ldapportal.core.CoreNoopSpiAutoConfiguration}.</p>
 */
public class NoopHrDashboardProvider implements HrDashboardProvider {

    @Override
    public boolean hasConnectionForDirectory(UUID directoryId) {
        return false;
    }
}
