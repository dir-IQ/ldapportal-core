// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.repository.ProfileApprovalConfigRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalUsageServiceTest {

    @Mock private ApplicationSettingsService settingsService;
    @Mock private ProfileApprovalConfigRepository approvalConfigRepo;
    @Mock private ProvisioningProfileRepository profileRepo;

    private ApprovalUsageService service;
    private final ApplicationSettings settings = new ApplicationSettings();

    @BeforeEach
    void setUp() {
        service = new ApprovalUsageService(settingsService, approvalConfigRepo, profileRepo);
    }

    /**
     * Paired rule: each master switch only counts when its feature is
     * configured on a profile. Columns: approvalsEnabled, requireApproval
     * configured, selfRegistrationApprovalEnabled, self-registration
     * allowed, expected.
     */
    @ParameterizedTest
    @CsvSource({
            "true,  true,  false, false, true",
            "true,  false, false, true,  false",
            "false, true,  false, false, false",
            "false, false, true,  true,  true",
            "false, true,  true,  false, false",
            "true,  false, true,  false, false",
            "false, false, false, false, false",
            "true,  true,  true,  true,  true",
    })
    void isInUse_global_pairsSwitchWithConfiguration(boolean approvalsEnabled, boolean requireApproval,
                                                    boolean selfRegApprovalEnabled, boolean selfRegAllowed,
                                                    boolean expected) {
        settings.setApprovalsEnabled(approvalsEnabled);
        settings.setSelfRegistrationApprovalEnabled(selfRegApprovalEnabled);
        when(settingsService.getEntity()).thenReturn(settings);
        lenient().when(approvalConfigRepo.existsByRequireApprovalTrue()).thenReturn(requireApproval);
        lenient().when(profileRepo.existsBySelfRegistrationAllowedTrue()).thenReturn(selfRegAllowed);

        assertThat(service.isInUse()).isEqualTo(expected);
    }

    @Test
    void isInUse_directorySet_usesScopedQueries() {
        Set<UUID> dirs = Set.of(UUID.randomUUID());
        settings.setApprovalsEnabled(false);
        settings.setSelfRegistrationApprovalEnabled(true);
        when(settingsService.getEntity()).thenReturn(settings);
        when(profileRepo.existsBySelfRegistrationAllowedTrueAndDirectory_IdIn(dirs)).thenReturn(true);

        assertThat(service.isInUse(dirs)).isTrue();
        verify(approvalConfigRepo, never()).existsByRequireApprovalTrueAndProfile_Directory_IdIn(any());
    }

    @Test
    void isInUse_emptyDirectorySet_isFalseWithoutQuerying() {
        assertThat(service.isInUse(List.of())).isFalse();
        verifyNoInteractions(settingsService, approvalConfigRepo, profileRepo);
    }

    @Test
    void isInUse_singleDirectory_usesScopedQueries() {
        UUID dir = UUID.randomUUID();
        when(settingsService.getEntity()).thenReturn(settings);  // both switches default on
        when(approvalConfigRepo.existsByRequireApprovalTrueAndProfile_Directory_Id(dir)).thenReturn(false);
        when(profileRepo.existsBySelfRegistrationAllowedTrueAndDirectory_Id(dir)).thenReturn(false);

        assertThat(service.isInUse(dir)).isFalse();
    }
}
