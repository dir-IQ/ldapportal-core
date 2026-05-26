// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Captures the startup INFO line produced by
 * {@link EntitlementStartupReporter} and verifies its contents —
 * enough to detect both a bad license shape and a regression that
 * silently stopped emitting the line.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementStartupReporterTest {

    @Mock private EntitlementService entitlementService;
    @Mock private LicenseProvider licenseProvider;
    private EntitlementStartupReporter reporter;

    private Logger reporterLog;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        reporter = new EntitlementStartupReporter(entitlementService, licenseProvider);
        reporterLog = (Logger) LoggerFactory.getLogger(EntitlementStartupReporter.class);
        appender = new ListAppender<>();
        appender.start();
        reporterLog.addAppender(appender);
        // Default source — most tests don't care about the exact string.
        when(licenseProvider.source()).thenReturn("test-source");
    }

    @AfterEach
    void tearDown() {
        reporterLog.detachAppender(appender);
    }

    @Test
    void enterprise_edition_logsAllGranted_noneWithheld() {
        when(entitlementService.current()).thenReturn(license(Edition.ENTERPRISE,
                EnumSet.noneOf(Entitlement.class)));

        reporter.reportOnStartup();

        ILoggingEvent ev = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .findFirst().orElseThrow();
        String msg = ev.getFormattedMessage();
        assertThat(msg).contains("edition=ENTERPRISE");
        assertThat(msg).contains("GOVERNANCE").contains("HYBRID").contains("HR_SYNC");
        // Addon-granted entitlements are not in any Edition baseline,
        // so on an Enterprise tier without the matching addon they
        // appear in the "withheld" list. Pinned by entitlement name
        // so the test stays correct as the addon set grows.
        assertThat(msg).contains("withheld=[VENDOR_INTEGRATIONS_ISVA]");
    }

    @Test
    void community_edition_logsWithheldEntitlements() {
        when(entitlementService.current()).thenReturn(license(Edition.COMMUNITY,
                EnumSet.noneOf(Entitlement.class)));

        reporter.reportOnStartup();

        ILoggingEvent ev = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .findFirst().orElseThrow();
        String msg = ev.getFormattedMessage();
        assertThat(msg).contains("edition=COMMUNITY");
        assertThat(msg).contains("granted=[]");
        // Every entitlement should be in 'withheld'
        for (Entitlement e : Entitlement.values()) {
            assertThat(msg).contains(e.name());
        }
    }

    @Test
    void addon_entitlement_appearsInGranted_evenForCommunityEdition() {
        when(entitlementService.current()).thenReturn(license(Edition.COMMUNITY,
                EnumSet.of(Entitlement.GOVERNANCE)));

        reporter.reportOnStartup();

        String msg = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .findFirst().orElseThrow()
                .getFormattedMessage();
        assertThat(msg).matches(".*granted=\\[.*GOVERNANCE.*\\].*");
        assertThat(msg).matches(".*withheld=\\[(?!.*GOVERNANCE).*\\].*");
    }

    private static License license(Edition edition, Set<Entitlement> addOns) {
        return new License(null, edition, addOns, Map.of(),
                Instant.EPOCH, Instant.MAX, null);
    }
}
