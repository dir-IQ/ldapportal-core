// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.dto.approval.ApprovalRejectRequest;
import com.ldapportal.dto.approval.PendingApprovalResponse;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.repository.ProfileApprovalConfigRepository;
import com.ldapportal.service.ApprovalWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/directories/{directoryId}/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalWorkflowService service;
    private final ProfileApprovalConfigRepository approvalConfigRepo;

    @GetMapping
    @RequiresFeature(FeatureKey.APPROVAL_MANAGE)
    public List<PendingApprovalResponse> list(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.listPending(directoryId, principal);
    }

    @GetMapping("/{approvalId}")
    @RequiresFeature(FeatureKey.APPROVAL_MANAGE)
    public PendingApprovalResponse get(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID approvalId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.getApproval(approvalId, principal);
    }

    @PostMapping("/{approvalId}/approve")
    @RequiresFeature(FeatureKey.APPROVAL_MANAGE)
    public PendingApprovalResponse approve(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID approvalId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.approve(approvalId, principal);
    }

    @PostMapping("/{approvalId}/reject")
    @RequiresFeature(FeatureKey.APPROVAL_MANAGE)
    public PendingApprovalResponse reject(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID approvalId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ApprovalRejectRequest req) {
        return service.reject(approvalId, principal, req.reason());
    }

    @PutMapping("/{approvalId}/payload")
    @RequiresFeature(FeatureKey.APPROVAL_MANAGE)
    public PendingApprovalResponse updatePayload(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID approvalId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody String payload) {
        return service.updatePayload(approvalId, principal, payload);
    }

    /**
     * Pending approval count + "is any profile in this directory configured
     * to use approvals?". The sidebar uses the pair to decide whether to
     * render the Approvals nav link: hide it when the directory neither
     * uses approvals nor has a residual pending queue.
     *
     * <p>Returned as a heterogeneous map of Object so the boolean and the
     * long live side by side. The historical {@code pending} key is kept
     * for backwards-compatible clients.</p>
     */
    @GetMapping("/count")
    @RequiresFeature(FeatureKey.APPROVAL_MANAGE)
    public Map<String, Object> countPending(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        long pending = service.countPending(directoryId);
        boolean configured = approvalConfigRepo
                .existsByRequireApprovalTrueAndProfile_Directory_Id(directoryId);
        return Map.of("pending", pending, "configured", configured);
    }
}
