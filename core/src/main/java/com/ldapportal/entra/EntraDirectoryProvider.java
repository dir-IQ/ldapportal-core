// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.ldapportal.directory.*;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * {@link DirectoryProvider} implementation for Microsoft Entra ID (Azure AD)
 * using the Microsoft Graph API.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntraDirectoryProvider implements DirectoryProvider {

    private final GraphApiClient graphClient;

    private static final String USER_SELECT =
            "id,displayName,userPrincipalName,mail,accountEnabled,department,jobTitle,employeeId";

    private static final String GROUP_SELECT =
            "id,displayName,description";

    @Override
    public List<DirectoryType> supportedTypes() {
        return List.of(DirectoryType.ENTRA_ID);
    }

    // ── Users ────────────────────────────────────────────────────────────────

    @Override
    public List<DirectoryUser> searchUsers(DirectoryConnection dc, String filter, int maxResults) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("$select", USER_SELECT);
        params.put("$top", String.valueOf(Math.min(maxResults, 999)));
        if (filter != null && !filter.isBlank()) {
            params.put("$filter", filter);
        }

        List<JsonNode> nodes;
        if (maxResults <= 999) {
            JsonNode page = graphClient.get(dc, "/v1.0/users", params);
            nodes = new ArrayList<>();
            JsonNode value = page.get("value");
            if (value != null && value.isArray()) {
                for (JsonNode item : value) nodes.add(item);
            }
        } else {
            nodes = graphClient.getAllPages(dc, "/v1.0/users", params);
        }

        return nodes.stream().map(this::toDirectoryUser).toList();
    }

    @Override
    public DirectoryUser getUser(DirectoryConnection dc, String identifier) {
        JsonNode node = graphClient.get(dc, "/v1.0/users/" + identifier,
                Map.of("$select", USER_SELECT));

        // Also fetch group memberships
        List<JsonNode> memberOf = graphClient.getAllPages(dc,
                "/v1.0/users/" + identifier + "/memberOf",
                Map.of("$select", "id,displayName"));

        List<String> groupIds = memberOf.stream()
                .filter(n -> "#microsoft.graph.group".equals(textOrNull(n, "@odata.type")))
                .map(n -> text(n, "id"))
                .toList();

        DirectoryUser user = toDirectoryUser(node);
        return new DirectoryUser(
                user.id(), user.displayName(), user.loginName(), user.email(),
                user.enabled(), user.attributes(),
                groupIds);
    }

    // ── Groups ───────────────────────────────────────────────────────────────

    @Override
    public List<DirectoryGroup> searchGroups(DirectoryConnection dc, String filter, int maxResults) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("$select", GROUP_SELECT);
        params.put("$top", String.valueOf(Math.min(maxResults, 999)));
        if (filter != null && !filter.isBlank()) {
            params.put("$filter", filter);
        }

        List<JsonNode> nodes;
        if (maxResults <= 999) {
            JsonNode page = graphClient.get(dc, "/v1.0/groups", params);
            nodes = new ArrayList<>();
            JsonNode value = page.get("value");
            if (value != null && value.isArray()) {
                for (JsonNode item : value) nodes.add(item);
            }
        } else {
            nodes = graphClient.getAllPages(dc, "/v1.0/groups", params);
        }

        return nodes.stream().map(this::toDirectoryGroup).toList();
    }

    @Override
    public List<String> getGroupMembers(DirectoryConnection dc, String groupId) {
        List<JsonNode> members = graphClient.getAllPages(dc,
                "/v1.0/groups/" + groupId + "/members",
                Map.of("$select", "id,userPrincipalName"));

        return members.stream()
                .map(n -> text(n, "id"))
                .toList();
    }

    // ── Audit Events ─────────────────────────────────────────────────────────

    @Override
    public List<DirectoryAuditEvent> pollAuditEvents(DirectoryConnection dc, OffsetDateTime since) {
        String sinceStr = since.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        List<JsonNode> events = graphClient.getAllPages(dc,
                "/v1.0/auditLogs/directoryAudits",
                Map.of("$filter", "activityDateTime ge " + sinceStr,
                       "$orderby", "activityDateTime desc",
                       "$top", "500"));

        return events.stream().map(this::toAuditEvent).toList();
    }

    // ── Test Connection ──────────────────────────────────────────────────────

    @Override
    public String testConnection(DirectoryConnection dc) {
        try {
            // Minimal call to verify credentials work
            graphClient.get(dc, "/v1.0/organization",
                    Map.of("$select", "id,displayName", "$top", "1"));
            return null; // success
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private DirectoryUser toDirectoryUser(JsonNode node) {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        putIfPresent(attrs, "department", node);
        putIfPresent(attrs, "jobTitle", node);
        putIfPresent(attrs, "employeeId", node);

        return new DirectoryUser(
                text(node, "id"),
                text(node, "displayName"),
                text(node, "userPrincipalName"),
                text(node, "mail"),
                node.has("accountEnabled") && node.get("accountEnabled").asBoolean(true),
                attrs,
                List.of()  // group memberships loaded separately via getUser()
        );
    }

    private DirectoryGroup toDirectoryGroup(JsonNode node) {
        return new DirectoryGroup(
                text(node, "id"),
                text(node, "displayName"),
                textOrNull(node, "description"),
                0  // member count not available in list response; loaded on demand
        );
    }

    private DirectoryAuditEvent toAuditEvent(JsonNode node) {
        String actor = null;
        JsonNode initiatedBy = node.get("initiatedBy");
        if (initiatedBy != null) {
            JsonNode user = initiatedBy.get("user");
            if (user != null) {
                actor = textOrNull(user, "userPrincipalName");
                if (actor == null) actor = textOrNull(user, "displayName");
            }
            if (actor == null) {
                JsonNode app = initiatedBy.get("app");
                if (app != null) actor = textOrNull(app, "displayName");
            }
        }

        String targetName = null;
        String targetId = null;
        JsonNode targets = node.get("targetResources");
        if (targets != null && targets.isArray() && !targets.isEmpty()) {
            JsonNode first = targets.get(0);
            targetName = textOrNull(first, "displayName");
            targetId = textOrNull(first, "id");
        }

        OffsetDateTime occurredAt = null;
        String dateStr = textOrNull(node, "activityDateTime");
        if (dateStr != null) {
            try { occurredAt = OffsetDateTime.parse(dateStr); }
            catch (Exception ignored) {}
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("category", textOrNull(node, "category"));
        detail.put("result", textOrNull(node, "result"));
        detail.put("resultReason", textOrNull(node, "resultReason"));

        return new DirectoryAuditEvent(
                text(node, "id"),
                actor,
                text(node, "activityDisplayName"),
                targetName,
                targetId,
                occurredAt,
                detail);
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private static String text(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return val != null && !val.isNull() ? val.asText() : "";
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return val != null && !val.isNull() ? val.asText() : null;
    }

    private static void putIfPresent(Map<String, List<String>> attrs, String field, JsonNode node) {
        String val = textOrNull(node, field);
        if (val != null) attrs.put(field, List.of(val));
    }
}
