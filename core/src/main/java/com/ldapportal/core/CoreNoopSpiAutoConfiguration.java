// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core;

import com.ldapportal.core.alerting.AlertSummaryProvider;
import com.ldapportal.core.alerting.AlertingDashboardProvider;
import com.ldapportal.core.alerting.NoopAlertSummaryProvider;
import com.ldapportal.core.alerting.NoopAlertingDashboardProvider;
import com.ldapportal.core.dashboard.NoopReportJobHealthProvider;
import com.ldapportal.core.dashboard.ReportJobHealthProvider;
import com.ldapportal.core.governance.GovernanceDashboardProvider;
import com.ldapportal.core.governance.MembershipGate;
import com.ldapportal.core.governance.NoopGovernanceDashboardProvider;
import com.ldapportal.core.governance.NoopMembershipGate;
import com.ldapportal.core.hr.HrDashboardProvider;
import com.ldapportal.core.hr.NoopHrDashboardProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the no-op default implementation for every core SPI when
 * no other implementation is on the classpath. The ee module supplies
 * concrete implementations ({@code AlertService}, {@code SodPolicyService},
 * {@code GovernanceDashboardService}, etc.); when ee isn't present the
 * beans defined here take over and the dashboards render zero'd panels
 * instead of throwing missing-bean errors.
 *
 * <p><strong>Why an auto-configuration class rather than
 * {@code @Component @ConditionalOnMissingBean} on each Noop?</strong>
 * Spring Boot guarantees {@code @ConditionalOnMissingBean} ordering for
 * auto-configuration beans — the condition is evaluated after every
 * other user bean definition has been loaded. On a {@code @Component}-
 * scanned class the condition evaluates mid-scan, and when the
 * "canonical" ee implementation is absent (community edition) the noop
 * sometimes failed to register, breaking startup. This pattern is the
 * officially-supported alternative and matches how Spring Boot itself
 * ships its own defaults.</p>
 */
@AutoConfiguration
public class CoreNoopSpiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(GovernanceDashboardProvider.class)
    GovernanceDashboardProvider governanceDashboardProvider() {
        return new NoopGovernanceDashboardProvider();
    }

    @Bean
    @ConditionalOnMissingBean(MembershipGate.class)
    MembershipGate membershipGate() {
        return new NoopMembershipGate();
    }

    @Bean
    @ConditionalOnMissingBean(AlertSummaryProvider.class)
    AlertSummaryProvider alertSummaryProvider() {
        return new NoopAlertSummaryProvider();
    }

    @Bean
    @ConditionalOnMissingBean(AlertingDashboardProvider.class)
    AlertingDashboardProvider alertingDashboardProvider() {
        return new NoopAlertingDashboardProvider();
    }

    @Bean
    @ConditionalOnMissingBean(HrDashboardProvider.class)
    HrDashboardProvider hrDashboardProvider() {
        return new NoopHrDashboardProvider();
    }

    @Bean
    @ConditionalOnMissingBean(ReportJobHealthProvider.class)
    ReportJobHealthProvider reportJobHealthProvider() {
        return new NoopReportJobHealthProvider();
    }
}
