// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.ldapportal.directory.DirectoryAuditEvent;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.core.observability.CorrelationContext;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;
import com.ldapportal.entra.entity.*;
import com.ldapportal.entra.repository.*;
import com.ldapportal.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Syncs Entra ID users, groups, memberships, and audit events into
 * local cache tables for offline access by SoD, access reviews, etc.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EntraSyncService {

    private final GraphApiClient graphClient;
    private final EntraDirectoryProvider entraProvider;
    private final EntraUserRepository userRepo;
    private final EntraGroupRepository groupRepo;
    private final EntraGroupMembershipRepository membershipRepo;
    private final EntraSyncStateRepository stateRepo;
    private final AuditEventRepository auditEventRepo;

    private static final String USER_SELECT =
            "id,displayName,userPrincipalName,mail,accountEnabled,department,jobTitle,employeeId";
    private static final String GROUP_SELECT = "id,displayName,description";

    // ══════════════════════════════════════════════════════════════════════
    //  Full Sync
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    public SyncResult fullSync(DirectoryConnection dc) {
        UUID dirId = dc.getId();
        log.info("Starting full Entra ID sync for directory {}", dc.getDisplayName());
        OffsetDateTime now = OffsetDateTime.now();

        int usersSync = syncAllUsers(dc, dirId, now);
        int groupsSync = syncAllGroups(dc, dirId, now);
        int membershipsSync = syncAllMemberships(dc, dirId, now);

        // Save delta tokens for future incremental syncs
        EntraSyncState state = stateRepo.findById(dirId).orElse(new EntraSyncState());
        state.setDirectoryId(dirId);
        state.setLastFullSync(now);

        // Get initial delta tokens
        try {
            JsonNode userDelta = graphClient.get(dc, "/v1.0/users/delta",
                    Map.of("$select", USER_SELECT));
            String userDeltaLink = textOrNull(userDelta, "@odata.deltaLink");
            if (userDeltaLink != null) state.setUserDeltaToken(extractDeltaToken(userDeltaLink));
        } catch (Exception e) {
            log.warn("Failed to get user delta token: {}", e.getMessage());
        }

        try {
            JsonNode groupDelta = graphClient.get(dc, "/v1.0/groups/delta",
                    Map.of("$select", GROUP_SELECT));
            String groupDeltaLink = textOrNull(groupDelta, "@odata.deltaLink");
            if (groupDeltaLink != null) state.setGroupDeltaToken(extractDeltaToken(groupDeltaLink));
        } catch (Exception e) {
            log.warn("Failed to get group delta token: {}", e.getMessage());
        }

        stateRepo.save(state);

        log.info("Full sync complete for {}: {} users, {} groups, {} memberships",
                dc.getDisplayName(), usersSync, groupsSync, membershipsSync);
        return new SyncResult(usersSync, groupsSync, membershipsSync, 0);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Delta Sync
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    public SyncResult deltaSync(DirectoryConnection dc) {
        UUID dirId = dc.getId();
        EntraSyncState state = stateRepo.findById(dirId).orElse(null);

        if (state == null || state.getLastFullSync() == null) {
            log.info("No previous sync state — running full sync for {}", dc.getDisplayName());
            return fullSync(dc);
        }

        OffsetDateTime now = OffsetDateTime.now();
        int usersChanged = 0;
        int groupsChanged = 0;

        // Delta sync users
        if (state.getUserDeltaToken() != null) {
            try {
                usersChanged = syncUserDelta(dc, dirId, state, now);
            } catch (Exception e) {
                log.warn("User delta sync failed for {} — falling back to full sync: {}",
                        dc.getDisplayName(), e.getMessage());
                return fullSync(dc);
            }
        }

        // Delta sync groups
        if (state.getGroupDeltaToken() != null) {
            try {
                groupsChanged = syncGroupDelta(dc, dirId, state, now);
            } catch (Exception e) {
                log.warn("Group delta sync failed for {} — falling back to full sync: {}",
                        dc.getDisplayName(), e.getMessage());
                return fullSync(dc);
            }
        }

        // Audit log sync
        int auditEvents = syncAuditEvents(dc, dirId, state, now);

        stateRepo.save(state);

        if (usersChanged > 0 || groupsChanged > 0 || auditEvents > 0) {
            log.info("Delta sync for {}: {} users changed, {} groups changed, {} audit events",
                    dc.getDisplayName(), usersChanged, groupsChanged, auditEvents);
        }
        return new SyncResult(usersChanged, groupsChanged, 0, auditEvents);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Internals — Full sync
    // ══════════════════════════════════════════════════════════════════════

    private int syncAllUsers(DirectoryConnection dc, UUID dirId, OffsetDateTime now) {
        List<JsonNode> users = graphClient.getAllPages(dc, "/v1.0/users",
                Map.of("$select", USER_SELECT, "$top", "999"));

        userRepo.deleteAllByDirectoryId(dirId);
        userRepo.flush();

        int count = 0;
        for (JsonNode node : users) {
            EntraUser user = new EntraUser();
            user.setDirectoryId(dirId);
            user.setEntraObjectId(text(node, "id"));
            user.setDisplayName(textOrNull(node, "displayName"));
            user.setUserPrincipalName(textOrNull(node, "userPrincipalName"));
            user.setMail(textOrNull(node, "mail"));
            user.setDepartment(textOrNull(node, "department"));
            user.setJobTitle(textOrNull(node, "jobTitle"));
            user.setEmployeeId(textOrNull(node, "employeeId"));
            user.setAccountEnabled(node.has("accountEnabled") ? node.get("accountEnabled").asBoolean() : null);
            user.setSyncedAt(now);
            userRepo.save(user);
            count++;
        }
        return count;
    }

    private int syncAllGroups(DirectoryConnection dc, UUID dirId, OffsetDateTime now) {
        List<JsonNode> groups = graphClient.getAllPages(dc, "/v1.0/groups",
                Map.of("$select", GROUP_SELECT, "$top", "999"));

        groupRepo.deleteAllByDirectoryId(dirId);
        groupRepo.flush();

        int count = 0;
        for (JsonNode node : groups) {
            EntraGroup group = new EntraGroup();
            group.setDirectoryId(dirId);
            group.setEntraObjectId(text(node, "id"));
            group.setDisplayName(textOrNull(node, "displayName"));
            group.setDescription(textOrNull(node, "description"));
            group.setSyncedAt(now);
            groupRepo.save(group);
            count++;
        }
        return count;
    }

    private int syncAllMemberships(DirectoryConnection dc, UUID dirId, OffsetDateTime now) {
        membershipRepo.deleteAllByDirectoryId(dirId);
        membershipRepo.flush();

        List<EntraGroup> groups = groupRepo.findAllByDirectoryId(dirId);
        int count = 0;

        for (EntraGroup group : groups) {
            try {
                List<JsonNode> members = graphClient.getAllPages(dc,
                        "/v1.0/groups/" + group.getEntraObjectId() + "/members",
                        Map.of("$select", "id", "$top", "999"));

                for (JsonNode member : members) {
                    EntraGroupMembership m = new EntraGroupMembership();
                    m.setDirectoryId(dirId);
                    m.setUserObjectId(text(member, "id"));
                    m.setGroupObjectId(group.getEntraObjectId());
                    m.setSyncedAt(now);
                    membershipRepo.save(m);
                    count++;
                }
            } catch (Exception e) {
                log.warn("Failed to sync members for group {}: {}", group.getDisplayName(), e.getMessage());
            }
        }
        return count;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Internals — Delta sync
    // ══════════════════════════════════════════════════════════════════════

    private int syncUserDelta(DirectoryConnection dc, UUID dirId, EntraSyncState state,
                               OffsetDateTime now) {
        String deltaUrl = resolveEndpoint(dc) + "/v1.0/users/delta?$deltatoken=" + state.getUserDeltaToken();
        int changed = 0;

        while (deltaUrl != null) {
            JsonNode page = graphClient.getByUrl(dc, deltaUrl);
            JsonNode value = page.get("value");

            if (value != null && value.isArray()) {
                for (JsonNode node : value) {
                    String objectId = text(node, "id");
                    boolean removed = node.has("@removed");

                    if (removed) {
                        userRepo.findByDirectoryIdAndEntraObjectId(dirId, objectId)
                                .ifPresent(userRepo::delete);
                    } else {
                        EntraUser user = userRepo.findByDirectoryIdAndEntraObjectId(dirId, objectId)
                                .orElseGet(EntraUser::new);
                        user.setDirectoryId(dirId);
                        user.setEntraObjectId(objectId);
                        if (node.has("displayName")) user.setDisplayName(textOrNull(node, "displayName"));
                        if (node.has("userPrincipalName")) user.setUserPrincipalName(textOrNull(node, "userPrincipalName"));
                        if (node.has("mail")) user.setMail(textOrNull(node, "mail"));
                        if (node.has("department")) user.setDepartment(textOrNull(node, "department"));
                        if (node.has("jobTitle")) user.setJobTitle(textOrNull(node, "jobTitle"));
                        if (node.has("employeeId")) user.setEmployeeId(textOrNull(node, "employeeId"));
                        if (node.has("accountEnabled")) user.setAccountEnabled(node.get("accountEnabled").asBoolean());
                        user.setSyncedAt(now);
                        userRepo.save(user);
                    }
                    changed++;
                }
            }

            // Next page or delta link
            JsonNode nextLink = page.get("@odata.nextLink");
            JsonNode deltaLink = page.get("@odata.deltaLink");

            if (nextLink != null && !nextLink.isNull()) {
                deltaUrl = nextLink.asText();
            } else {
                deltaUrl = null;
                if (deltaLink != null && !deltaLink.isNull()) {
                    state.setUserDeltaToken(extractDeltaToken(deltaLink.asText()));
                }
            }
        }
        return changed;
    }

    private int syncGroupDelta(DirectoryConnection dc, UUID dirId, EntraSyncState state,
                                OffsetDateTime now) {
        String deltaUrl = resolveEndpoint(dc) + "/v1.0/groups/delta?$deltatoken=" + state.getGroupDeltaToken();
        int changed = 0;

        while (deltaUrl != null) {
            JsonNode page = graphClient.getByUrl(dc, deltaUrl);
            JsonNode value = page.get("value");

            if (value != null && value.isArray()) {
                for (JsonNode node : value) {
                    String objectId = text(node, "id");
                    boolean removed = node.has("@removed");

                    if (removed) {
                        groupRepo.findByDirectoryIdAndEntraObjectId(dirId, objectId)
                                .ifPresent(groupRepo::delete);
                        membershipRepo.deleteAllByDirectoryIdAndGroupObjectId(dirId, objectId);
                    } else {
                        EntraGroup group = groupRepo.findByDirectoryIdAndEntraObjectId(dirId, objectId)
                                .orElseGet(EntraGroup::new);
                        group.setDirectoryId(dirId);
                        group.setEntraObjectId(objectId);
                        if (node.has("displayName")) group.setDisplayName(textOrNull(node, "displayName"));
                        if (node.has("description")) group.setDescription(textOrNull(node, "description"));
                        group.setSyncedAt(now);
                        groupRepo.save(group);

                        // Re-sync members for changed groups
                        membershipRepo.deleteAllByDirectoryIdAndGroupObjectId(dirId, objectId);
                        membershipRepo.flush();
                        try {
                            List<JsonNode> members = graphClient.getAllPages(dc,
                                    "/v1.0/groups/" + objectId + "/members",
                                    Map.of("$select", "id", "$top", "999"));
                            for (JsonNode m : members) {
                                EntraGroupMembership mem = new EntraGroupMembership();
                                mem.setDirectoryId(dirId);
                                mem.setUserObjectId(text(m, "id"));
                                mem.setGroupObjectId(objectId);
                                mem.setSyncedAt(now);
                                membershipRepo.save(mem);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to re-sync members for group {}: {}", objectId, e.getMessage());
                        }
                    }
                    changed++;
                }
            }

            JsonNode nextLink = page.get("@odata.nextLink");
            JsonNode deltaLink = page.get("@odata.deltaLink");

            if (nextLink != null && !nextLink.isNull()) {
                deltaUrl = nextLink.asText();
            } else {
                deltaUrl = null;
                if (deltaLink != null && !deltaLink.isNull()) {
                    state.setGroupDeltaToken(extractDeltaToken(deltaLink.asText()));
                }
            }
        }
        return changed;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Internals — Audit event sync
    // ══════════════════════════════════════════════════════════════════════

    private int syncAuditEvents(DirectoryConnection dc, UUID dirId, EntraSyncState state,
                                 OffsetDateTime now) {
        OffsetDateTime since = state.getAuditLastPoll();
        if (since == null) since = now.minusHours(1); // first run: last hour

        try {
            List<DirectoryAuditEvent> events = entraProvider.pollAuditEvents(dc, since);

            int count = 0;
            for (DirectoryAuditEvent evt : events) {
                // Dedup by event ID
                if (auditEventRepo.existsByDirectoryIdAndChangelogChangeNumber(dirId, evt.id())) {
                    continue;
                }

                // LDAP_CHANGELOG is reused as a generic "external directory" source.
                // A dedicated ENTRA_ID source would require a DB migration to update
                // the CHECK constraint on audit_events.source. TODO for future cleanup.
                AuditEvent ae = AuditEvent.builder()
                        .source(AuditSource.LDAP_CHANGELOG)
                        .directoryId(dirId)
                        .directoryName(dc.getDisplayName())
                        .actorUsername(evt.actorName())
                        .action(mapEntraAction(evt.action()))
                        .targetDn(evt.targetId()) // use object ID as target identifier
                        .detail(evt.detail())
                        .changelogChangeNumber(evt.id()) // for dedup
                        // Stamp the active per-directory sync scope (set by
                        // EntraSyncScheduler) so a sync's ingested rows share
                        // a correlation id. Null when called outside a scope.
                        .correlationId(CorrelationContext.current().orElse(null))
                        .occurredAt(evt.occurredAt())
                        .build();
                auditEventRepo.save(ae);
                count++;
            }

            state.setAuditLastPoll(now);
            return count;
        } catch (Exception e) {
            log.warn("Audit event sync failed for {}: {}", dc.getDisplayName(), e.getMessage());
            return 0;
        }
    }

    private AuditAction mapEntraAction(String activityDisplayName) {
        if (activityDisplayName == null) return AuditAction.LDAP_CHANGE;
        String lower = activityDisplayName.toLowerCase();
        if (lower.contains("add user") || lower.contains("create user")) return AuditAction.USER_CREATE;
        if (lower.contains("delete user")) return AuditAction.USER_DELETE;
        if (lower.contains("update user")) return AuditAction.USER_UPDATE;
        if (lower.contains("add member")) return AuditAction.GROUP_MEMBER_ADD;
        if (lower.contains("remove member")) return AuditAction.GROUP_MEMBER_REMOVE;
        if (lower.contains("add group") || lower.contains("create group")) return AuditAction.GROUP_CREATE;
        if (lower.contains("delete group")) return AuditAction.GROUP_DELETE;
        return AuditAction.LDAP_CHANGE; // generic fallback
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private String resolveEndpoint(DirectoryConnection dc) {
        String endpoint = dc.getGraphEndpoint();
        if (endpoint == null || endpoint.isBlank()) endpoint = "https://graph.microsoft.com";
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private String extractDeltaToken(String deltaLink) {
        int idx = deltaLink.indexOf("$deltatoken=");
        if (idx >= 0) return deltaLink.substring(idx + "$deltatoken=".length());
        return deltaLink; // store full URL as fallback
    }

    private static String text(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return val != null && !val.isNull() ? val.asText() : "";
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return val != null && !val.isNull() ? val.asText() : null;
    }

    // ── Result DTO ───────────────────────────────────────────────────────────

    public record SyncResult(int usersSynced, int groupsSynced, int membershipsSynced, int auditEvents) {}
}
