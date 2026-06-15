// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.ldapportal.controller.superadmin.EntraController;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entra.entity.EntraSyncState;
import com.ldapportal.entra.repository.EntraGroupRepository;
import com.ldapportal.entra.repository.EntraSyncStateRepository;
import com.ldapportal.entra.repository.EntraUserRepository;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntraControllerTest {

    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private EntraSyncService syncService;
    @Mock private EntraSyncStateRepository stateRepo;
    @Mock private EntraUserRepository userRepo;
    @Mock private EntraGroupRepository groupRepo;
    @Mock private EntraEntitlementService entitlementService;
    @Mock private EntraDirectoryProvider entraProvider;
    @Mock private EncryptionService encryptionService;

    private EntraController controller;

    @BeforeEach
    void setUp() {
        controller = new EntraController(dirRepo, syncService, stateRepo, userRepo, groupRepo,
                entitlementService, entraProvider, encryptionService);
    }

    @Test
    void getSyncStatus_returnsCountsAndTimestamps() {
        UUID dirId = UUID.randomUUID();
        EntraSyncState state = new EntraSyncState();
        state.setDirectoryId(dirId);
        state.setLastFullSync(OffsetDateTime.now().minusHours(1));
        state.setAuditLastPoll(OffsetDateTime.now().minusMinutes(5));
        state.setUserDeltaToken("tok");

        when(stateRepo.findById(dirId)).thenReturn(Optional.of(state));
        when(userRepo.countByDirectoryId(dirId)).thenReturn(150L);
        when(groupRepo.countByDirectoryId(dirId)).thenReturn(25L);

        Map<String, Object> result = controller.getSyncStatus(dirId);

        assertThat(result.get("userCount")).isEqualTo(150L);
        assertThat(result.get("groupCount")).isEqualTo(25L);
        assertThat(result.get("hasDeltaTokens")).isEqualTo(true);
        assertThat((String) result.get("lastFullSync")).isNotBlank();
    }

    @Test
    void getSyncStatus_handlesNoState() {
        UUID dirId = UUID.randomUUID();
        when(stateRepo.findById(dirId)).thenReturn(Optional.empty());
        when(userRepo.countByDirectoryId(dirId)).thenReturn(0L);
        when(groupRepo.countByDirectoryId(dirId)).thenReturn(0L);

        Map<String, Object> result = controller.getSyncStatus(dirId);

        assertThat(result.get("userCount")).isEqualTo(0L);
        assertThat(result.get("hasDeltaTokens")).isEqualTo(false);
        assertThat(result.get("lastFullSync")).isEqualTo("");
    }

    @Test
    void triggerSync_callsDeltaSync() {
        UUID dirId = UUID.randomUUID();
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(dirId);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(syncService.deltaSync(dc)).thenReturn(new EntraSyncService.SyncResult(5, 3, 10, 2));

        var result = controller.triggerSync(dirId, false);

        assertThat(result.usersSynced()).isEqualTo(5);
        verify(syncService).deltaSync(dc);
        verify(syncService, never()).fullSync(any());
    }

    @Test
    void triggerSync_callsFullSync() {
        UUID dirId = UUID.randomUUID();
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(dirId);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(syncService.fullSync(dc)).thenReturn(new EntraSyncService.SyncResult(100, 20, 500, 0));

        var result = controller.triggerSync(dirId, true);

        assertThat(result.usersSynced()).isEqualTo(100);
        verify(syncService).fullSync(dc);
    }

    @Test
    void triggerSync_throwsForMissingDirectory() {
        UUID dirId = UUID.randomUUID();
        when(dirRepo.findById(dirId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.triggerSync(dirId, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void testConnection_returnsSuccess() {
        UUID dirId = UUID.randomUUID();
        when(encryptionService.encrypt("my-secret")).thenReturn("encrypted");
        when(entraProvider.testConnection(any())).thenReturn(null);

        Map<String, Object> result = controller.testConnection(dirId, Map.of(
                "tenantId", "t1", "entraClientId", "c1", "entraClientSecret", "my-secret"));

        assertThat(result.get("success")).isEqualTo(true);
        assertThat((String) result.get("message")).contains("successful");
    }

    @Test
    void testConnection_returnsFailure() {
        UUID dirId = UUID.randomUUID();
        when(encryptionService.encrypt("bad-secret")).thenReturn("encrypted");
        when(entraProvider.testConnection(any())).thenReturn("Invalid client credentials");

        Map<String, Object> result = controller.testConnection(dirId, Map.of(
                "tenantId", "t1", "entraClientId", "c1", "entraClientSecret", "bad-secret"));

        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("message")).contains("Invalid client");
    }

    @Test
    void listUsers_delegatesToEntitlementService() {
        UUID dirId = UUID.randomUUID();
        when(entitlementService.getUserEntitlements(dirId)).thenReturn(List.of());

        controller.listUsers(dirId);
        verify(entitlementService).getUserEntitlements(dirId);
    }

    @Test
    void listGroups_delegatesToEntitlementService() {
        UUID dirId = UUID.randomUUID();
        when(entitlementService.getGroups(dirId)).thenReturn(List.of());

        controller.listGroups(dirId);
        verify(entitlementService).getGroups(dirId);
    }
}
