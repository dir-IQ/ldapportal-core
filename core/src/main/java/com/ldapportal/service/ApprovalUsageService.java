// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.repository.ProfileApprovalConfigRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Answers "is the approval workflow in use?" — i.e. can any approval source
 * actually produce requests — for the dashboard and sidebar gating of
 * approval UI (Pending Approvals card, Profiles "Pending" column, Approvals
 * nav link).
 *
 * <p>Each global master switch is paired with whether its feature is
 * configured on a profile in scope:</p>
 * <ul>
 *   <li>{@code approvalsEnabled} AND some profile has {@code requireApproval}, or</li>
 *   <li>{@code selfRegistrationApprovalEnabled} AND some profile has
 *       {@code selfRegistrationAllowed}.</li>
 * </ul>
 *
 * <p>Callers still OR this with the live pending count so residual items
 * from a since-disabled workflow stay reachable until drained.</p>
 */
@Service
@RequiredArgsConstructor
public class ApprovalUsageService {

    private final ApplicationSettingsService settingsService;
    private final ProfileApprovalConfigRepository approvalConfigRepo;
    private final ProvisioningProfileRepository profileRepo;

    /** System-wide variant (superadmin dashboard). */
    @Transactional(readOnly = true)
    public boolean isInUse() {
        return evaluate(approvalConfigRepo::existsByRequireApprovalTrue,
                profileRepo::existsBySelfRegistrationAllowedTrue);
    }

    /** Scoped to a caller's authorized directories (admin dashboard). */
    @Transactional(readOnly = true)
    public boolean isInUse(Collection<UUID> directoryIds) {
        if (directoryIds.isEmpty()) return false;
        return evaluate(
                () -> approvalConfigRepo.existsByRequireApprovalTrueAndProfile_Directory_IdIn(directoryIds),
                () -> profileRepo.existsBySelfRegistrationAllowedTrueAndDirectory_IdIn(directoryIds));
    }

    /** Single-directory variant (per-directory sidebar nav link). */
    @Transactional(readOnly = true)
    public boolean isInUse(UUID directoryId) {
        return evaluate(
                () -> approvalConfigRepo.existsByRequireApprovalTrueAndProfile_Directory_Id(directoryId),
                () -> profileRepo.existsBySelfRegistrationAllowedTrueAndDirectory_Id(directoryId));
    }

    private boolean evaluate(BooleanSupplier requireApprovalConfigured,
                             BooleanSupplier selfRegistrationConfigured) {
        ApplicationSettings settings = settingsService.getEntity();
        return (settings.isApprovalsEnabled() && requireApprovalConfigured.getAsBoolean())
                || (settings.isSelfRegistrationApprovalEnabled() && selfRegistrationConfigured.getAsBoolean());
    }
}
