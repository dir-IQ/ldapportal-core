// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.integration;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
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
import com.ldapportal.dto.dashboard.UnifiedDashboardDto;
import com.ldapportal.service.UnifiedDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the community edition boots and serves the
 * dashboard without any {@code com.ldapportal.ee.*} beans in the Spring
 * context.
 *
 * <p>Post-Phase-5a this test lives in the core module, which has
 * {@code ldapportal-ee} nowhere on its classpath — Maven enforces the
 * boundary at compile time. So "ee absent" isn't simulated with
 * component-scan filters like it used to be; it's the actual state of
 * this JVM. The regression barrier is thereby tighter: if anything in
 * core accidentally reaches into ee, the build fails at the
 * {@code mvn compile} step before this test even runs.</p>
 *
 * <p>What this test adds on top of that compile-time guarantee:</p>
 * <ol>
 *   <li>The Spring context actually assembles — no missing-bean errors
 *       from services that autowire an SPI whose ee implementation is
 *       absent.</li>
 *   <li>Every core SPI resolves to its {@code Noop*} default —
 *       delivered by {@code CoreNoopSpiAutoConfiguration}.</li>
 *   <li>{@link UnifiedDashboardService} returns a well-formed DTO with
 *       zeroed governance / alerting / HR panels.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class CommunityEditionContextTest {

    @Autowired private ConfigurableApplicationContext ctx;
    @Autowired private UnifiedDashboardService unifiedDashboardService;

    /**
     * {@link com.ldapportal.service.AuditQueryService} issues native
     * PostgreSQL SQL with {@code CAST(… AS TIMESTAMPTZ)} — H2's
     * PostgreSQL compatibility mode doesn't accept {@code TIMESTAMPTZ}
     * inside a CAST, so the query fails against the test DB. Mocking
     * this bean lets the dashboard-composition test exercise the full
     * bean graph without depending on H2 quirks.
     */
    @MockitoBean private com.ldapportal.service.AuditQueryService auditQueryService;

    // ── 1. No ee beans register in the context ─────────────────────────────

    @Test
    void no_ee_beans_in_application_context() {
        List<String> eeBeans = Arrays.stream(ctx.getBeanDefinitionNames())
                .filter(name -> {
                    try {
                        Class<?> type = ctx.getBean(name).getClass();
                        return type.getName().startsWith("com.ldapportal.ee.");
                    } catch (Exception e) {
                        return false;
                    }
                })
                .sorted()
                .toList();

        assertThat(eeBeans)
                .as("Community edition must not instantiate any com.ldapportal.ee.* beans. "
                        + "Post-Phase-5a this is enforced by Maven (ee isn't on core's classpath); "
                        + "if this assertion ever fires it means ee classes sneaked onto the test "
                        + "classpath some other way.")
                .isEmpty();
    }

    // ── 2. Every SPI resolves to its Noop default ──────────────────────────

    @Test
    void governance_spis_resolve_to_noop_defaults() {
        assertThat(ctx.getBean(GovernanceDashboardProvider.class))
                .isInstanceOf(NoopGovernanceDashboardProvider.class);
        assertThat(ctx.getBean(MembershipGate.class))
                .isInstanceOf(NoopMembershipGate.class);
    }

    @Test
    void alerting_spis_resolve_to_noop_defaults() {
        assertThat(ctx.getBean(AlertSummaryProvider.class))
                .isInstanceOf(NoopAlertSummaryProvider.class);
        assertThat(ctx.getBean(AlertingDashboardProvider.class))
                .isInstanceOf(NoopAlertingDashboardProvider.class);
    }

    @Test
    void hr_spi_resolves_to_noop_default() {
        assertThat(ctx.getBean(HrDashboardProvider.class))
                .isInstanceOf(NoopHrDashboardProvider.class);
    }

    @Test
    void report_job_health_spi_resolves_to_noop_default() {
        assertThat(ctx.getBean(ReportJobHealthProvider.class))
                .isInstanceOf(NoopReportJobHealthProvider.class);
    }

    // ── 3. Dashboard produces a clean, zeroed payload end-to-end ───────────

    @Test
    void unified_dashboard_returns_zeroed_payload_for_superadmin() {
        AuthPrincipal superadmin = new AuthPrincipal(
                PrincipalType.SUPERADMIN, UUID.randomUUID(), "test-super");

        // Stub the native-SQL audit query so the full dashboard path
        // runs on H2. See the @MockitoBean Javadoc above for context.
        org.mockito.Mockito.when(auditQueryService.query(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        UnifiedDashboardDto dto = unifiedDashboardService.getDashboard(superadmin);

        assertThat(dto).as("dashboard DTO assembled without ee beans").isNotNull();

        // NB: complianceEnabled / hrIntegrationEnabled aren't asserted
        // here. They're entitlement-derived (GOVERNANCE / HR_SYNC) and in
        // this core-only context the no-license CommunityEditionLicenseProvider
        // withholds them. What this test cares about is that the *SPI
        // outputs* are well-formed zero values, because that's what
        // proves the ee implementations weren't reached.

        assertThat(dto.alertSummary()).isNotNull();
        assertThat(dto.alertSummary().openCount()).isZero();
        assertThat(dto.alertSummary().criticalCount()).isZero();
        assertThat(dto.alertSummary().highCount()).isZero();

        assertThat(dto.metrics().openSodViolations()).isZero();
        assertThat(dto.metrics().overdueCampaigns()).isZero();
        assertThat(dto.metrics().activeCampaigns()).isZero();
        assertThat(dto.campaignProgress()).isEmpty();

        assertThat(dto.enabledReportJobs()).isZero();
        assertThat(dto.failedReportJobs()).isZero();

        assertThat(dto.actions())
                .extracting(UnifiedDashboardDto.ActionItem::type)
                .doesNotContain("REVIEW", "CAMPAIGN_OVERDUE");
        assertThat(dto.awareness())
                .extracting(UnifiedDashboardDto.AwarenessItem::type)
                .doesNotContain("UPCOMING_DEADLINE");
    }
}
