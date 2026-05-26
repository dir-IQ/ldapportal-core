// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entra.EntraDirectoryProvider;
import com.ldapportal.entra.EntraEntitlementService;
import com.ldapportal.entra.EntraSyncService;
import com.ldapportal.entra.entity.EntraSyncState;
import com.ldapportal.entra.repository.EntraGroupRepository;
import com.ldapportal.entra.repository.EntraSyncStateRepository;
import com.ldapportal.entra.repository.EntraUserRepository;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.EncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/superadmin/entra/{directoryId}")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class EntraController {

    private final DirectoryConnectionRepository dirRepo;
    private final EntraSyncService syncService;
    private final EntraSyncStateRepository stateRepo;
    private final EntraUserRepository userRepo;
    private final EntraGroupRepository groupRepo;
    private final EntraEntitlementService entitlementService;
    private final EntraDirectoryProvider entraProvider;
    private final EncryptionService encryptionService;

    @PostMapping("/test-connection")
    public Map<String, Object> testConnection(@PathVariable UUID directoryId,
                                               @RequestBody Map<String, String> body) {
        long start = System.currentTimeMillis();
        try {
            // Build a temporary DirectoryConnection for testing
            DirectoryConnection dc = new DirectoryConnection();
            dc.setId(directoryId);
            dc.setTenantId(body.get("tenantId"));
            dc.setEntraClientId(body.get("entraClientId"));
            // Use provided secret, or decrypt from existing directory if blank
            String secret = body.get("entraClientSecret");
            if (secret == null || secret.isBlank()) {
                DirectoryConnection existing = dirRepo.findById(directoryId).orElse(null);
                if (existing != null && existing.getEntraClientSecretEncrypted() != null) {
                    dc.setEntraClientSecretEncrypted(existing.getEntraClientSecretEncrypted());
                }
            } else {
                dc.setEntraClientSecretEncrypted(encryptionService.encrypt(secret));
            }
            dc.setGraphEndpoint(body.getOrDefault("graphEndpoint", "https://graph.microsoft.com"));

            String error = entraProvider.testConnection(dc);
            long ms = System.currentTimeMillis() - start;
            if (error == null) {
                return Map.of("success", true, "message", "Connection successful", "elapsedMs", ms);
            }
            return Map.of("success", false, "message", error, "elapsedMs", ms);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            return Map.of("success", false, "message", e.getMessage(), "elapsedMs", ms);
        }
    }

    @GetMapping("/sync-status")
    public Map<String, Object> getSyncStatus(@PathVariable UUID directoryId) {
        EntraSyncState state = stateRepo.findById(directoryId).orElse(null);
        long userCount = userRepo.countByDirectoryId(directoryId);
        long groupCount = groupRepo.countByDirectoryId(directoryId);

        return Map.of(
                "lastFullSync", state != null && state.getLastFullSync() != null ? state.getLastFullSync().toString() : "",
                "auditLastPoll", state != null && state.getAuditLastPoll() != null ? state.getAuditLastPoll().toString() : "",
                "hasDeltaTokens", state != null && state.getUserDeltaToken() != null,
                "userCount", userCount,
                "groupCount", groupCount);
    }

    @PostMapping("/sync")
    public EntraSyncService.SyncResult triggerSync(@PathVariable UUID directoryId,
                                                     @RequestParam(defaultValue = "false") boolean full) {
        DirectoryConnection dc = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
        return full ? syncService.fullSync(dc) : syncService.deltaSync(dc);
    }

    @GetMapping("/users")
    public List<EntraEntitlementService.UserEntitlement> listUsers(@PathVariable UUID directoryId) {
        return entitlementService.getUserEntitlements(directoryId);
    }

    @GetMapping("/groups")
    public List<EntraEntitlementService.GroupDetail> listGroups(@PathVariable UUID directoryId) {
        return entitlementService.getGroups(directoryId);
    }
}
