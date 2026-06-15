// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.directory.DirectoryAuditEvent;
import com.ldapportal.directory.DirectoryGroup;
import com.ldapportal.directory.DirectoryUser;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntraDirectoryProviderTest {

    @Mock private GraphApiClient graphClient;

    private EntraDirectoryProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        provider = new EntraDirectoryProvider(graphClient);
    }

    private DirectoryConnection makeConnection() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setGraphEndpoint("https://graph.microsoft.com");
        return dc;
    }

    @Test
    void supportedTypes_returnsEntraId() {
        assertThat(provider.supportedTypes()).containsExactly(DirectoryType.ENTRA_ID);
    }

    @Test
    void searchUsers_mapsFieldsCorrectly() throws Exception {
        DirectoryConnection dc = makeConnection();
        JsonNode response = mapper.readTree("{\"value\":[" +
                "{\"id\":\"obj-1\",\"displayName\":\"Alice\",\"userPrincipalName\":\"alice@corp.com\"," +
                "\"mail\":\"alice@corp.com\",\"accountEnabled\":true,\"department\":\"Engineering\"," +
                "\"jobTitle\":\"SDE\",\"employeeId\":\"E001\"}" +
                "]}");
        when(graphClient.get(eq(dc), eq("/v1.0/users"), any())).thenReturn(response);

        List<DirectoryUser> users = provider.searchUsers(dc, null, 100);

        assertThat(users).hasSize(1);
        DirectoryUser u = users.get(0);
        assertThat(u.id()).isEqualTo("obj-1");
        assertThat(u.displayName()).isEqualTo("Alice");
        assertThat(u.loginName()).isEqualTo("alice@corp.com");
        assertThat(u.email()).isEqualTo("alice@corp.com");
        assertThat(u.enabled()).isTrue();
        assertThat(u.attributes()).containsEntry("department", List.of("Engineering"));
        assertThat(u.attributes()).containsEntry("jobTitle", List.of("SDE"));
    }

    @Test
    void searchUsers_handlesDisabledAccount() throws Exception {
        DirectoryConnection dc = makeConnection();
        JsonNode response = mapper.readTree("{\"value\":[" +
                "{\"id\":\"obj-2\",\"displayName\":\"Bob\",\"userPrincipalName\":\"bob@corp.com\"," +
                "\"accountEnabled\":false}" +
                "]}");
        when(graphClient.get(eq(dc), eq("/v1.0/users"), any())).thenReturn(response);

        List<DirectoryUser> users = provider.searchUsers(dc, null, 100);
        assertThat(users.get(0).enabled()).isFalse();
    }

    @Test
    void searchGroups_mapsFieldsCorrectly() throws Exception {
        DirectoryConnection dc = makeConnection();
        JsonNode response = mapper.readTree("{\"value\":[" +
                "{\"id\":\"grp-1\",\"displayName\":\"Finance\",\"description\":\"Finance team\"}" +
                "]}");
        when(graphClient.get(eq(dc), eq("/v1.0/groups"), any())).thenReturn(response);

        List<DirectoryGroup> groups = provider.searchGroups(dc, null, 100);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).id()).isEqualTo("grp-1");
        assertThat(groups.get(0).name()).isEqualTo("Finance");
        assertThat(groups.get(0).description()).isEqualTo("Finance team");
    }

    @Test
    void getGroupMembers_returnsMemberIds() throws Exception {
        DirectoryConnection dc = makeConnection();
        JsonNode m1 = mapper.readTree("{\"id\":\"user-1\",\"userPrincipalName\":\"a@x.com\"}");
        JsonNode m2 = mapper.readTree("{\"id\":\"user-2\",\"userPrincipalName\":\"b@x.com\"}");
        when(graphClient.getAllPages(eq(dc), contains("/members"), any()))
                .thenReturn(List.of(m1, m2));

        List<String> members = provider.getGroupMembers(dc, "grp-1");
        assertThat(members).containsExactly("user-1", "user-2");
    }

    @Test
    void getUser_includesGroupMemberships() throws Exception {
        DirectoryConnection dc = makeConnection();
        JsonNode userJson = mapper.readTree(
                "{\"id\":\"obj-1\",\"displayName\":\"Alice\",\"userPrincipalName\":\"alice@corp.com\"," +
                "\"accountEnabled\":true}");
        when(graphClient.get(eq(dc), contains("/users/obj-1"), any())).thenReturn(userJson);

        JsonNode group = mapper.readTree("{\"@odata.type\":\"#microsoft.graph.group\",\"id\":\"grp-1\",\"displayName\":\"Finance\"}");
        JsonNode role = mapper.readTree("{\"@odata.type\":\"#microsoft.graph.directoryRole\",\"id\":\"role-1\"}");
        when(graphClient.getAllPages(eq(dc), contains("/memberOf"), any()))
                .thenReturn(List.of(group, role));

        DirectoryUser user = provider.getUser(dc, "obj-1");
        assertThat(user.groupIds()).containsExactly("grp-1"); // role filtered out
    }

    @Test
    void pollAuditEvents_mapsAuditFields() throws Exception {
        DirectoryConnection dc = makeConnection();
        JsonNode evt = mapper.readTree(
                "{\"id\":\"evt-1\",\"activityDisplayName\":\"Add user\"," +
                "\"activityDateTime\":\"2026-03-28T12:00:00Z\"," +
                "\"initiatedBy\":{\"user\":{\"userPrincipalName\":\"admin@corp.com\"}}," +
                "\"targetResources\":[{\"id\":\"target-1\",\"displayName\":\"New User\"}]," +
                "\"category\":\"UserManagement\",\"result\":\"success\",\"resultReason\":null}");
        when(graphClient.getAllPages(eq(dc), contains("/auditLogs"), any()))
                .thenReturn(List.of(evt));

        List<DirectoryAuditEvent> events = provider.pollAuditEvents(dc, OffsetDateTime.now().minusHours(1));

        assertThat(events).hasSize(1);
        DirectoryAuditEvent e = events.get(0);
        assertThat(e.id()).isEqualTo("evt-1");
        assertThat(e.actorName()).isEqualTo("admin@corp.com");
        assertThat(e.action()).isEqualTo("Add user");
        assertThat(e.targetName()).isEqualTo("New User");
        assertThat(e.targetId()).isEqualTo("target-1");
        assertThat(e.occurredAt()).isNotNull();
    }

    @Test
    void pollAuditEvents_fallsBackToAppActor() throws Exception {
        DirectoryConnection dc = makeConnection();
        JsonNode evt = mapper.readTree(
                "{\"id\":\"evt-2\",\"activityDisplayName\":\"Add member to group\"," +
                "\"activityDateTime\":\"2026-03-28T12:00:00Z\"," +
                "\"initiatedBy\":{\"app\":{\"displayName\":\"HR Sync App\"}}," +
                "\"targetResources\":[]}");
        when(graphClient.getAllPages(eq(dc), contains("/auditLogs"), any()))
                .thenReturn(List.of(evt));

        List<DirectoryAuditEvent> events = provider.pollAuditEvents(dc, OffsetDateTime.now().minusHours(1));
        assertThat(events.get(0).actorName()).isEqualTo("HR Sync App");
    }

    @Test
    void testConnection_returnsNullOnSuccess() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(graphClient.get(eq(dc), eq("/v1.0/organization"), any()))
                .thenReturn(mapper.readTree("{\"value\":[]}"));

        assertThat(provider.testConnection(dc)).isNull();
    }

    @Test
    void testConnection_returnsErrorMessage() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(graphClient.get(eq(dc), eq("/v1.0/organization"), any()))
                .thenThrow(new EntraApiException("Unauthorized"));

        assertThat(provider.testConnection(dc)).isEqualTo("Unauthorized");
    }
}
