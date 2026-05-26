// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.alerting;

import java.util.UUID;

/**
 * SPI for alerting-derived dashboard data the
 * {@link AlertSummaryProvider} doesn't already cover — specifically the
 * per-directory "are there any alert rules configured?" check that the
 * activity dashboard uses to decide whether to suggest initialising
 * alert rules for a directory.
 *
 * <p>Implemented by {@code ee.alerting.service.AlertService}; core
 * ships {@link NoopAlertingDashboardProvider} which always returns
 * {@code false} (no rules exist in the community edition, so the
 * suggestion is harmless regardless — but is filtered by the activity
 * dashboard's entitlement-aware suggestion pipeline).</p>
 */
public interface AlertingDashboardProvider {
    /** True if the directory has any configured alert rule. */
    boolean hasRulesForDirectory(UUID directoryId);
}
