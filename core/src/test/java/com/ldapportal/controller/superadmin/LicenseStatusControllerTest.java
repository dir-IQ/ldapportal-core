// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.core.entitlement.Edition;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.entitlement.License;
import com.ldapportal.core.entitlement.LicenseProvider;
import com.ldapportal.core.entitlement.LimitType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LicenseStatusController.class)
class LicenseStatusControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean EntitlementService entitlementService;
    @MockitoBean LicenseProvider licenseProvider;

    private static final String BASE_URL = "/api/v1/license/status";

    @Test
    void rejects_unauthenticated() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejects_regularAdmin() throws Exception {
        mockMvc.perform(get(BASE_URL).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void superadmin_sees_valid_business_license() throws Exception {
        UUID customerId = UUID.fromString("7f2e8a12-0000-4000-8000-000000000001");
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        Instant expires = Instant.now().plus(Duration.ofDays(365));

        given(entitlementService.current()).willReturn(new License(
                customerId,
                Edition.BUSINESS,
                Set.of(Entitlement.GOVERNANCE, Entitlement.HR_SYNC),
                Map.of(LimitType.DIRECTORIES, 25L, LimitType.ADMIN_ACCOUNTS, 100L),
                issued,
                expires,
                "signed-jwt-placeholder"));
        given(licenseProvider.source()).willReturn("/etc/ldapportal/license.jwt");

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edition").value("BUSINESS"))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.signed").value(true))
                .andExpect(jsonPath("$.addOns", org.hamcrest.Matchers.containsInAnyOrder("GOVERNANCE", "HR_SYNC")))
                .andExpect(jsonPath("$.grantedEntitlements", org.hamcrest.Matchers.hasItems(
                        "GOVERNANCE", "HR_SYNC", "ALERTING", "EVENTS", "HYBRID")))
                .andExpect(jsonPath("$.limits.DIRECTORIES").value(25))
                .andExpect(jsonPath("$.limits.ADMIN_ACCOUNTS").value(100))
                .andExpect(jsonPath("$.graceState").value("VALID"))
                .andExpect(jsonPath("$.source").value("/etc/ldapportal/license.jwt"))
                .andExpect(jsonPath("$.daysRemaining").isNumber());
    }

    @Test
    void superadmin_sees_settings_derived_as_noExpiry() throws Exception {
        given(entitlementService.current()).willReturn(new License(
                null,
                Edition.COMMUNITY,
                Set.of(Entitlement.GOVERNANCE, Entitlement.HR_SYNC, Entitlement.ALERTING,
                        Entitlement.EVENTS, Entitlement.HYBRID, Entitlement.SAML_ADMIN_SSO,
                        Entitlement.AUDIT_LOG_SIGNING, Entitlement.HA_DEPLOYMENT,
                        Entitlement.DATA_RESIDENCY),
                Map.of(),
                Instant.EPOCH,
                Instant.MAX,
                null));  // null signature = unsigned
        given(licenseProvider.source()).willReturn("application settings (no license file configured)");

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edition").value("COMMUNITY"))
                .andExpect(jsonPath("$.customerId").isEmpty())
                .andExpect(jsonPath("$.signed").value(false))
                .andExpect(jsonPath("$.issuedAt").isEmpty())
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andExpect(jsonPath("$.daysRemaining").isEmpty())
                .andExpect(jsonPath("$.graceState").value("NO_EXPIRY"))
                .andExpect(jsonPath("$.source").value(
                        org.hamcrest.Matchers.containsString("application settings")));
    }

    @Test
    void approaching_expiry_within_30_days_flagged() throws Exception {
        Instant expires = Instant.now().plus(Duration.ofDays(15));
        given(entitlementService.current()).willReturn(new License(
                UUID.randomUUID(), Edition.ENTERPRISE,
                Set.of(), Map.of(),
                Instant.now().minus(Duration.ofDays(350)),
                expires,
                "signed"));
        given(licenseProvider.source()).willReturn("/etc/ldapportal/license.jwt");

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graceState").value("APPROACHING_EXPIRY"));
    }

    @Test
    void expired_within_grace_flagged() throws Exception {
        // Expired 5 days ago — within the default 30-day grace.
        given(entitlementService.current()).willReturn(new License(
                UUID.randomUUID(), Edition.ENTERPRISE,
                Set.of(), Map.of(),
                Instant.now().minus(Duration.ofDays(370)),
                Instant.now().minus(Duration.ofDays(5)),
                "signed"));
        given(licenseProvider.source()).willReturn("/etc/ldapportal/license.jwt");

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                // Without a FileLicenseProvider in front of the mocked
                // provider, the controller can't use a verifier's
                // grace duration — it falls back to the default 30
                // days. This exercises that fallback path.
                .andExpect(jsonPath("$.graceState").value("EXPIRED_WITHIN_GRACE"))
                .andExpect(jsonPath("$.graceDays").value(30));
    }

    @Test
    void past_grace_flagged() throws Exception {
        // Expired 60 days ago — past the default 30-day grace.
        given(entitlementService.current()).willReturn(new License(
                UUID.randomUUID(), Edition.ENTERPRISE,
                Set.of(), Map.of(),
                Instant.now().minus(Duration.ofDays(425)),
                Instant.now().minus(Duration.ofDays(60)),
                "signed"));
        given(licenseProvider.source()).willReturn("/etc/ldapportal/license.jwt");

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graceState").value("PAST_GRACE"));
    }
}
