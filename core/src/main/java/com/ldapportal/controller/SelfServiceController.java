// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.service.SelfServiceService;
import com.ldapportal.service.SelfServiceService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Self-service portal and self-registration endpoints.
 *
 * <ul>
 *   <li>{@code /api/v1/self-service/register/**} — public (no auth required)</li>
 *   <li>{@code /api/v1/self-service/**} — requires SELF_SERVICE JWT</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/self-service")
@RequiredArgsConstructor
public class SelfServiceController {

    private final SelfServiceService selfServiceService;

    // ── Authenticated self-service endpoints ──────────────────────────────────

    @GetMapping("/template")
    public SelfServiceTemplateResponse getTemplate(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return selfServiceService.getTemplate(principal);
    }

    @GetMapping("/profile")
    public SelfServiceProfileResponse getProfile(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return selfServiceService.getProfile(principal);
    }

    @PutMapping("/profile")
    public ResponseEntity<Void> updateProfile(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody Map<String, List<String>> attributes) {
        selfServiceService.updateProfile(principal, attributes);
        return ResponseEntity.noContent().build();
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword) {}

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest req) {
        selfServiceService.changePassword(principal, req.currentPassword(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/groups")
    public List<SelfServiceGroupResponse> getGroups(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return selfServiceService.getGroups(principal);
    }

    // ── Public registration endpoints ────────────────────────────────────────

    @GetMapping("/register/directories")
    public List<RegistrationDirectoryResponse> listRegistrationDirectories() {
        return selfServiceService.listRegistrationDirectories();
    }

    @GetMapping("/register/profiles/{directoryId}")
    public List<RegistrationProfileResponse> listRegistrationProfiles(
            @PathVariable UUID directoryId) {
        return selfServiceService.listRegistrationProfiles(directoryId);
    }

    @GetMapping("/register/form/{profileId}")
    public List<SelfServiceFieldConfig> getRegistrationForm(
            @PathVariable UUID profileId) {
        return selfServiceService.getRegistrationForm(profileId);
    }

    public record RegistrationSubmitBody(
            @NotNull UUID profileId,
            @NotBlank @Email String email,
            String justification,
            @NotNull Map<String, List<String>> attributes) {}

    @PostMapping("/register/submit")
    public RegistrationSubmitResponse submitRegistration(
            @Valid @RequestBody RegistrationSubmitBody body) {
        return selfServiceService.submitRegistration(
                new RegistrationSubmitRequest(
                        body.profileId(), body.email(), body.justification(), body.attributes()));
    }

    @PostMapping("/register/verify/{token}")
    public RegistrationVerifyResponse verifyEmail(@PathVariable String token) {
        return selfServiceService.verifyEmail(token);
    }

    @GetMapping("/register/status/{requestId}")
    public RegistrationStatusResponse getRegistrationStatus(
            @PathVariable UUID requestId,
            @RequestParam String email) {
        return selfServiceService.getRegistrationStatus(requestId, email);
    }
}
