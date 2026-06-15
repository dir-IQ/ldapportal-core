// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.hr;

import java.util.UUID;

/**
 * SPI for HR-integration-derived dashboard data. Implemented by
 * {@code ee.hr.service.HrConnectionService} (or a companion in that
 * module); core ships {@link NoopHrDashboardProvider} which reports
 * no connections in the community edition.
 *
 * <p>The only call site today is the activity dashboard's suggestion
 * builder — "connect an HR system" only makes sense when the directory
 * has no connection yet. Minimal surface keeps the SPI honest; grow it
 * as new HR-dashboard needs land.</p>
 */
public interface HrDashboardProvider {
    /** True if the directory has at least one configured HR connection. */
    boolean hasConnectionForDirectory(UUID directoryId);
}
