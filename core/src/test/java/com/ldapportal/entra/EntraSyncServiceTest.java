// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.directory.DirectoryAuditEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entra.entity.*;
import com.ldapportal.entra.repository.*;
import com.ldapportal.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntraSyncServiceTest {

    @Mock private GraphApiClient graphClient;
    @Mock private EntraDirectoryProvider entraProvider;
    @Mock private EntraUserRepository userRepo;
    @Mock private EntraGroupRepository groupRepo;
    @Mock private EntraGroupMembershipRepository membershipRepo;
    @Mock private EntraSyncStateRepository stateRepo;
    @Mock private AuditEventRepository auditEventRepo;

    private EntraSyncService syncService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        syncService = new EntraSyncService(graphClient, entraProvider, userRepo, groupRepo,
                membershipRepo, stateRepo, auditEventRepo);
    }

    private DirectoryConnection makeConnection() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName("Test Entra");
        dc.setGraphEndpoint("https://graph.microsoft.com");
        return dc;
    }

    @Test
    void fullSync_syncsUsersGroupsAndMemberships() throws Exception {
        DirectoryConnection dc = makeConnection();

        // Users
        JsonNode usersPage = mapper.readTree("{\"value\":[{\"id\":\"u1\",\"displayName\":\"Alice\"}]}");
        when(graphClient.getAllPages(eq(dc), eq("/v1.0/users"), any())).thenReturn(List.of(
                mapper.readTree("{\"id\":\"u1\",\"displayName\":\"Alice\"}")));

        // Groups
        when(graphClient.getAllPages(eq(dc), eq("/v1.0/groups"), any())).thenReturn(List.of(
                mapper.readTree("{\"id\":\"g1\",\"displayName\":\"Finance\"}")));

        // After groups are saved, return them for membership sync
        EntraGroup savedGroup = new EntraGroup();
        savedGroup.setEntraObjectId("g1");
        savedGroup.setDisplayName("Finance");
        when(groupRepo.findAllByDirectoryId(dc.getId())).thenReturn(List.of(savedGroup));

        // Members
        when(graphClient.getAllPages(eq(dc), contains("/members"), any())).thenReturn(List.of(
                mapper.readTree("{\"id\":\"u1\"}")));

        // Delta token responses
        JsonNode userDelta = mapper.readTree("{\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/users/delta?$deltatoken=tok1\"}");
        JsonNode groupDelta = mapper.readTree("{\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/groups/delta?$deltatoken=tok2\"}");
        when(graphClient.get(eq(dc), eq("/v1.0/users/delta"), any())).thenReturn(userDelta);
        when(graphClient.get(eq(dc), eq("/v1.0/groups/delta"), any())).thenReturn(groupDelta);

        when(stateRepo.findById(dc.getId())).thenReturn(Optional.empty());

        EntraSyncService.SyncResult result = syncService.fullSync(dc);

        assertThat(result.usersSynced()).isEqualTo(1);
        assertThat(result.groupsSynced()).isEqualTo(1);
        assertThat(result.membershipsSynced()).isEqualTo(1);

        // Verify state was saved with delta tokens
        ArgumentCaptor<EntraSyncState> stateCaptor = ArgumentCaptor.forClass(EntraSyncState.class);
        verify(stateRepo).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getUserDeltaToken()).isEqualTo("tok1");
        assertThat(stateCaptor.getValue().getGroupDeltaToken()).isEqualTo("tok2");
    }

    @Test
    void deltaSync_fallsBackToFullSync_whenNoState() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(stateRepo.findById(dc.getId())).thenReturn(Optional.empty());

        // Set up minimal mocks for fullSync path
        when(graphClient.getAllPages(eq(dc), eq("/v1.0/users"), any())).thenReturn(List.of());
        when(graphClient.getAllPages(eq(dc), eq("/v1.0/groups"), any())).thenReturn(List.of());
        when(groupRepo.findAllByDirectoryId(dc.getId())).thenReturn(List.of());
        when(graphClient.get(eq(dc), contains("/users/delta"), any()))
                .thenReturn(mapper.readTree("{}"));
        when(graphClient.get(eq(dc), contains("/groups/delta"), any()))
                .thenReturn(mapper.readTree("{}"));

        EntraSyncService.SyncResult result = syncService.deltaSync(dc);

        // Verify full sync ran (deleteAll was called)
        verify(userRepo).deleteAllByDirectoryId(dc.getId());
        verify(groupRepo).deleteAllByDirectoryId(dc.getId());
    }

    @Test
    void deltaSync_processesUserChanges() throws Exception {
        DirectoryConnection dc = makeConnection();
        EntraSyncState state = new EntraSyncState();
        state.setDirectoryId(dc.getId());
        state.setLastFullSync(OffsetDateTime.now().minusHours(1));
        state.setUserDeltaToken("old-token");
        state.setGroupDeltaToken("old-group-token");
        state.setAuditLastPoll(OffsetDateTime.now().minusMinutes(30));
        when(stateRepo.findById(dc.getId())).thenReturn(Optional.of(state));

        // User delta response: one updated user, no more pages
        JsonNode deltaResponse = mapper.readTree(
                "{\"value\":[{\"id\":\"u1\",\"displayName\":\"Alice Updated\"}]," +
                "\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/users/delta?$deltatoken=new-token\"}");
        when(graphClient.getByUrl(eq(dc), contains("users/delta"))).thenReturn(deltaResponse);

        // Group delta: empty
        JsonNode groupDelta = mapper.readTree(
                "{\"value\":[],\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/groups/delta?$deltatoken=new-grp\"}");
        when(graphClient.getByUrl(eq(dc), contains("groups/delta"))).thenReturn(groupDelta);

        // No existing user
        when(userRepo.findByDirectoryIdAndEntraObjectId(dc.getId(), "u1")).thenReturn(Optional.empty());

        // Audit events
        when(entraProvider.pollAuditEvents(eq(dc), any())).thenReturn(List.of());

        EntraSyncService.SyncResult result = syncService.deltaSync(dc);
        assertThat(result.usersSynced()).isEqualTo(1);

        // Verify user was saved
        verify(userRepo).save(argThat(u -> "Alice Updated".equals(u.getDisplayName())));
        // Verify delta token updated
        assertThat(state.getUserDeltaToken()).isEqualTo("new-token");
    }

    @Test
    void deltaSync_handlesRemovedUser() throws Exception {
        DirectoryConnection dc = makeConnection();
        EntraSyncState state = new EntraSyncState();
        state.setDirectoryId(dc.getId());
        state.setLastFullSync(OffsetDateTime.now().minusHours(1));
        state.setUserDeltaToken("tok");
        state.setGroupDeltaToken("grp-tok");
        state.setAuditLastPoll(OffsetDateTime.now().minusMinutes(5));
        when(stateRepo.findById(dc.getId())).thenReturn(Optional.of(state));

        // User delta: one removed user
        JsonNode deltaResponse = mapper.readTree(
                "{\"value\":[{\"id\":\"u-gone\",\"@removed\":{\"reason\":\"deleted\"}}]," +
                "\"@odata.deltaLink\":\"https://x?$deltatoken=t2\"}");
        when(graphClient.getByUrl(eq(dc), contains("users/delta"))).thenReturn(deltaResponse);

        // Group delta: empty
        when(graphClient.getByUrl(eq(dc), contains("groups/delta"))).thenReturn(
                mapper.readTree("{\"value\":[],\"@odata.deltaLink\":\"https://x?$deltatoken=g2\"}"));

        EntraUser existingUser = new EntraUser();
        existingUser.setEntraObjectId("u-gone");
        when(userRepo.findByDirectoryIdAndEntraObjectId(dc.getId(), "u-gone"))
                .thenReturn(Optional.of(existingUser));

        when(entraProvider.pollAuditEvents(eq(dc), any())).thenReturn(List.of());

        syncService.deltaSync(dc);
        verify(userRepo).delete(existingUser);
    }

    @Test
    void syncAuditEvents_deduplicatesById() throws Exception {
        DirectoryConnection dc = makeConnection();
        EntraSyncState state = new EntraSyncState();
        state.setDirectoryId(dc.getId());
        state.setLastFullSync(OffsetDateTime.now().minusHours(1));
        state.setUserDeltaToken("tok");
        state.setGroupDeltaToken("grp-tok");
        state.setAuditLastPoll(OffsetDateTime.now().minusMinutes(10));
        when(stateRepo.findById(dc.getId())).thenReturn(Optional.of(state));

        // Empty delta responses
        when(graphClient.getByUrl(eq(dc), contains("users/delta"))).thenReturn(
                mapper.readTree("{\"value\":[],\"@odata.deltaLink\":\"https://x?$deltatoken=t\"}"));
        when(graphClient.getByUrl(eq(dc), contains("groups/delta"))).thenReturn(
                mapper.readTree("{\"value\":[],\"@odata.deltaLink\":\"https://x?$deltatoken=g\"}"));

        // Return one audit event
        DirectoryAuditEvent evt = new DirectoryAuditEvent(
                "evt-1", "admin", "Add user", "Alice", "u1", OffsetDateTime.now(), Map.of());
        when(entraProvider.pollAuditEvents(eq(dc), any())).thenReturn(List.of(evt));

        // Already exists — should be skipped
        when(auditEventRepo.existsByDirectoryIdAndChangelogChangeNumber(dc.getId(), "evt-1"))
                .thenReturn(true);

        syncService.deltaSync(dc);
        verify(auditEventRepo, never()).save(any());
    }

    @Test
    void mapEntraAction_mapsCommonActions() throws Exception {
        // Use reflection to test the private method via the audit event path
        DirectoryConnection dc = makeConnection();
        EntraSyncState state = new EntraSyncState();
        state.setDirectoryId(dc.getId());
        state.setLastFullSync(OffsetDateTime.now().minusHours(1));
        state.setUserDeltaToken("tok");
        state.setGroupDeltaToken("grp-tok");
        state.setAuditLastPoll(OffsetDateTime.now().minusMinutes(10));
        when(stateRepo.findById(dc.getId())).thenReturn(Optional.of(state));

        when(graphClient.getByUrl(eq(dc), contains("users/delta"))).thenReturn(
                mapper.readTree("{\"value\":[],\"@odata.deltaLink\":\"https://x?$deltatoken=t\"}"));
        when(graphClient.getByUrl(eq(dc), contains("groups/delta"))).thenReturn(
                mapper.readTree("{\"value\":[],\"@odata.deltaLink\":\"https://x?$deltatoken=g\"}"));

        DirectoryAuditEvent evt = new DirectoryAuditEvent(
                "evt-new", "admin", "Add user", "Alice", "u1", OffsetDateTime.now(), Map.of());
        when(entraProvider.pollAuditEvents(eq(dc), any())).thenReturn(List.of(evt));
        when(auditEventRepo.existsByDirectoryIdAndChangelogChangeNumber(dc.getId(), "evt-new"))
                .thenReturn(false);

        syncService.deltaSync(dc);

        verify(auditEventRepo).save(argThat(ae -> ae.getAction() == AuditAction.USER_CREATE));
    }
}
