// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.dto.playbook.*;
import com.ldapportal.dto.playbook.CreatePlaybookRequest.StepEntry;
import com.ldapportal.entity.*;
import com.ldapportal.entity.enums.*;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class LifecyclePlaybookService {

    private final LifecyclePlaybookRepository playbookRepo;
    private final PlaybookStepRepository stepRepo;
    private final PlaybookExecutionRepository executionRepo;
    private final DirectoryConnectionRepository dirRepo;
    private final ProvisioningProfileRepository profileRepo;
    private final LdapUserService ldapUserService;
    private final LdapGroupService ldapGroupService;
    private final AuditService auditService;
    private final ApprovalNotificationService notificationService;
    private final ApprovalWorkflowService approvalService;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PlaybookResponse> list(UUID directoryId) {
        return playbookRepo.findAllByDirectoryIdOrderByNameAsc(directoryId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PlaybookResponse> listEnabled(UUID directoryId) {
        return playbookRepo.findAllByDirectoryIdAndEnabledTrue(directoryId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PlaybookResponse get(UUID directoryId, UUID playbookId) {
        return toResponse(requirePlaybook(directoryId, playbookId));
    }

    @Transactional
    public PlaybookResponse create(UUID directoryId, CreatePlaybookRequest req) {
        DirectoryConnection dir = requireDirectory(directoryId);
        if (playbookRepo.existsByDirectoryIdAndName(directoryId, req.name())) {
            throw new ConflictException("Playbook [" + req.name() + "] already exists");
        }

        LifecyclePlaybook pb = new LifecyclePlaybook();
        pb.setDirectory(dir);
        applyFields(pb, req.name(), req.description(), req.type(), req.profileId(),
                req.requireApproval(), req.enabled());
        pb = playbookRepo.save(pb);

        saveSteps(pb, req.steps());
        return toResponse(pb);
    }

    @Transactional
    public PlaybookResponse update(UUID directoryId, UUID playbookId, UpdatePlaybookRequest req) {
        LifecyclePlaybook pb = requirePlaybook(directoryId, playbookId);
        if (!pb.getName().equals(req.name()) &&
                playbookRepo.existsByDirectoryIdAndName(directoryId, req.name())) {
            throw new ConflictException("Playbook [" + req.name() + "] already exists");
        }

        applyFields(pb, req.name(), req.description(), req.type(), req.profileId(),
                req.requireApproval(), req.enabled());
        pb = playbookRepo.save(pb);

        stepRepo.deleteAllByPlaybookId(playbookId);
        stepRepo.flush();
        saveSteps(pb, req.steps());
        return toResponse(pb);
    }

    @Transactional
    public void delete(UUID directoryId, UUID playbookId) {
        LifecyclePlaybook pb = requirePlaybook(directoryId, playbookId);
        playbookRepo.delete(pb);
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PlaybookPreviewResponse preview(UUID directoryId, UUID playbookId,
                                            String targetDn, AuthPrincipal principal) {
        // Scope check matches executeImmediate: an admin must not be
        // able to preview a playbook against a DN outside their
        // profile target OU. Without this an operator could probe the
        // existence of arbitrary DNs and read step descriptions that
        // reveal which attributes / groups would be touched on entries
        // they have no permission to manage.
        permissionService.requireDnWithinScope(principal, directoryId, targetDn);

        LifecyclePlaybook pb = requirePlaybook(directoryId, playbookId);
        List<PlaybookStep> steps = stepRepo.findAllByPlaybookIdOrderByStepOrderAsc(playbookId);

        List<PlaybookPreviewResponse.StepPreview> previews = new ArrayList<>();
        for (PlaybookStep step : steps) {
            String desc = describeStep(step);
            boolean reversible = step.getAction() != PlaybookStepAction.DELETE;
            previews.add(new PlaybookPreviewResponse.StepPreview(
                    step.getStepOrder(), step.getAction().name(), desc,
                    reversible, step.isContinueOnError()));
        }

        return new PlaybookPreviewResponse(targetDn, previews);
    }

    // ── Execute ───────────────────────────────────────────────────────────────

    @Transactional
    public PlaybookExecutionResponse execute(UUID directoryId, UUID playbookId,
                                              String targetDn, AuthPrincipal principal) {
        LifecyclePlaybook pb = requirePlaybook(directoryId, playbookId);

        // Enforce the requireApproval flag (C3 fix)
        if (pb.isRequireApproval()) {
            UUID profileId = pb.getProfile() != null ? pb.getProfile().getId() : null;
            Map<String, Object> payload = Map.of(
                    "playbookId", playbookId.toString(),
                    "playbookName", pb.getName(),
                    "targetDn", targetDn);
            PendingApproval pa = approvalService.submitForApproval(
                    directoryId, profileId, principal,
                    ApprovalRequestType.PLAYBOOK_EXECUTE, payload);
            log.info("Playbook execution submitted for approval: playbook={}, target={}, approvalId={}",
                    pb.getName(), targetDn, pa.getId());
            return PlaybookExecutionResponse.pending(pb.getName(), pa.getId());
        }

        return executeImmediate(directoryId, playbookId, targetDn, principal);
    }

    /**
     * Executes a playbook immediately, bypassing the approval check.
     * Called directly by {@link ApprovalWorkflowService} when a PLAYBOOK_EXECUTE
     * approval request is approved.
     */
    @Transactional
    PlaybookExecutionResponse executeImmediate(UUID directoryId, UUID playbookId,
                                               String targetDn, AuthPrincipal principal) {
        LifecyclePlaybook pb = requirePlaybook(directoryId, playbookId);
        permissionService.requireDnWithinScope(principal, directoryId, targetDn);
        DirectoryConnection dc = requireDirectory(directoryId);
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException("This feature is not supported for Entra ID directories");
        }
        List<PlaybookStep> steps = stepRepo.findAllByPlaybookIdOrderByStepOrderAsc(playbookId);

        PlaybookExecution exec = new PlaybookExecution();
        exec.setPlaybook(pb);
        exec.setTargetDn(targetDn);
        exec.setExecutedBy(principal.id());
        exec.setStatus(PlaybookExecutionStatus.SUCCESS);

        ArrayNode results = objectMapper.createArrayNode();
        boolean hadFailure = false;

        for (PlaybookStep step : steps) {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("stepOrder", step.getStepOrder());
            result.put("action", step.getAction().name());

            try {
                // Capture previous state for rollback
                String previousState = capturePreviousState(dc, targetDn, step);
                result.put("previousState", previousState);

                executeStep(dc, targetDn, step);
                result.put("status", "SUCCESS");
            } catch (Exception e) {
                result.put("status", "FAILED");
                result.put("error", e.getMessage());
                hadFailure = true;

                if (!step.isContinueOnError()) {
                    // Mark remaining steps as skipped
                    for (int i = steps.indexOf(step) + 1; i < steps.size(); i++) {
                        ObjectNode skipped = objectMapper.createObjectNode();
                        skipped.put("stepOrder", steps.get(i).getStepOrder());
                        skipped.put("action", steps.get(i).getAction().name());
                        skipped.put("status", "SKIPPED");
                        results.add(skipped);
                    }
                    break;
                }
            }
            results.add(result);
        }

        exec.setStatus(hadFailure ? PlaybookExecutionStatus.PARTIAL : PlaybookExecutionStatus.SUCCESS);
        exec.setCompletedAt(OffsetDateTime.now());
        try {
            exec.setStepResults(objectMapper.writeValueAsString(results));
        } catch (JsonProcessingException e) {
            exec.setStepResults("[]");
        }

        exec = executionRepo.save(exec);

        auditService.record(principal, directoryId, AuditAction.PLAYBOOK_EXECUTED, targetDn,
                Map.of("playbookId", playbookId.toString(), "playbookName", pb.getName(),
                        "status", exec.getStatus().name()));

        return PlaybookExecutionResponse.from(exec, pb.getName());
    }

    // ── Rollback ──────────────────────────────────────────────────────────────

    @Transactional
    public PlaybookExecutionResponse rollback(UUID executionId, AuthPrincipal principal) {
        PlaybookExecution exec = executionRepo.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("PlaybookExecution", executionId));

        if (exec.getStatus() != PlaybookExecutionStatus.PARTIAL &&
                exec.getStatus() != PlaybookExecutionStatus.SUCCESS) {
            throw new IllegalStateException("Can only rollback executions with PARTIAL or SUCCESS status");
        }

        DirectoryConnection dc = requireDirectory(exec.getPlaybook().getDirectory().getId());
        String targetDn = exec.getTargetDn();

        try {
            JsonNode stepResults = objectMapper.readTree(exec.getStepResults());
            // Iterate in reverse, rolling back successful steps
            List<JsonNode> reversed = new ArrayList<>();
            for (JsonNode node : stepResults) reversed.add(node);
            Collections.reverse(reversed);

            for (JsonNode stepResult : reversed) {
                if (!"SUCCESS".equals(stepResult.path("status").asText())) continue;

                String action = stepResult.path("action").asText();
                String previousState = stepResult.path("previousState").asText("");

                try {
                    rollbackStep(dc, targetDn, action, previousState);
                } catch (Exception e) {
                    log.warn("Rollback step failed for execution {} action {}: {}",
                            executionId, action, e.getMessage());
                }
            }

            exec.setStatus(PlaybookExecutionStatus.ROLLED_BACK);
            executionRepo.save(exec);

            auditService.record(principal, exec.getPlaybook().getDirectory().getId(),
                    AuditAction.PLAYBOOK_ROLLED_BACK, targetDn,
                    Map.of("executionId", executionId.toString(),
                            "playbookName", exec.getPlaybook().getName()));

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse step results for rollback", e);
        }

        return PlaybookExecutionResponse.from(exec, exec.getPlaybook().getName());
    }

    // ── Execution history ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PlaybookExecutionResponse> listExecutions(UUID directoryId, UUID playbookId) {
        LifecyclePlaybook pb = requirePlaybook(directoryId, playbookId);
        return executionRepo.findTop20ByPlaybookIdOrderByStartedAtDesc(playbookId)
                .stream()
                .map(e -> PlaybookExecutionResponse.from(e, pb.getName()))
                .toList();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void executeStep(DirectoryConnection dc, String targetDn, PlaybookStep step) {
        JsonNode params = parseParams(step.getParameters());

        switch (step.getAction()) {
            case ADD_TO_GROUP -> ldapGroupService.addMember(dc,
                    params.path("groupDn").asText(),
                    params.path("memberAttribute").asText("member"),
                    targetDn);
            case REMOVE_FROM_GROUP -> ldapGroupService.removeMember(dc,
                    params.path("groupDn").asText(),
                    params.path("memberAttribute").asText("member"),
                    targetDn);
            case REMOVE_ALL_GROUPS -> {
                LdapUser user = ldapUserService.getUser(dc, targetDn);
                for (String groupDn : user.getMemberOf()) {
                    try {
                        ldapGroupService.removeMember(dc, groupDn, "member", targetDn);
                    } catch (Exception e) {
                        // Try uniqueMember as fallback
                        try {
                            ldapGroupService.removeMember(dc, groupDn, "uniqueMember", targetDn);
                        } catch (Exception ignored) {
                            log.warn("Could not remove {} from group {}", targetDn, groupDn);
                        }
                    }
                }
            }
            case SET_ATTRIBUTE -> {
                String attrName = params.path("attributeName").asText();
                List<String> values = new ArrayList<>();
                JsonNode valNode = params.path("values");
                if (valNode.isArray()) {
                    for (JsonNode v : valNode) values.add(v.asText());
                } else {
                    values.add(valNode.asText());
                }
                ldapUserService.updateUser(dc, targetDn, List.of(
                        new Modification(ModificationType.REPLACE, attrName,
                                values.toArray(new String[0]))));
            }
            case REMOVE_ATTRIBUTE -> {
                String attrName = params.path("attributeName").asText();
                ldapUserService.updateUser(dc, targetDn, List.of(
                        new Modification(ModificationType.DELETE, attrName)));
            }
            case MOVE_OU -> ldapUserService.moveUser(dc, targetDn, params.path("targetDn").asText());
            case DISABLE -> ldapUserService.disableUser(dc, targetDn);
            case ENABLE -> ldapUserService.enableUser(dc, targetDn);
            case DELETE -> ldapUserService.deleteUser(dc, targetDn);
            case NOTIFY -> {
                String recipients = params.path("recipients").asText();
                String subject = params.path("subject").asText("Lifecycle Playbook Notification");
                String body = params.path("body").asText("");
                // Resolve ${user.dn} placeholder in body
                body = body.replace("${user.dn}", targetDn);
                if (!recipients.isBlank()) {
                    for (String recipient : recipients.split(",")) {
                        String addr = recipient.trim();
                        if (addr.equals("${user.mail}")) {
                            LdapUser user = ldapUserService.getUser(dc, targetDn);
                            addr = user.getFirstValue("mail");
                            if (addr == null || addr.isBlank()) continue;
                        }
                        notificationService.sendGenericEmail(addr, subject, body);
                    }
                }
            }
        }
    }

    /**
     * Captures the previous state needed to reverse this step.
     * Returns a JSON string stored in the execution record.
     */
    private String capturePreviousState(DirectoryConnection dc, String targetDn, PlaybookStep step) {
        try {
            JsonNode params = parseParams(step.getParameters());

            return switch (step.getAction()) {
                case ADD_TO_GROUP -> ""; // reverse: just remove from group
                case REMOVE_FROM_GROUP -> ""; // reverse: just add back
                case REMOVE_ALL_GROUPS -> {
                    // Capture current group memberships
                    LdapUser user = ldapUserService.getUser(dc, targetDn);
                    yield objectMapper.writeValueAsString(user.getMemberOf());
                }
                case SET_ATTRIBUTE -> {
                    // Capture current attribute values
                    LdapUser user = ldapUserService.getUser(dc, targetDn);
                    String attrName = params.path("attributeName").asText();
                    List<String> current = user.getValues(attrName);
                    yield objectMapper.writeValueAsString(current);
                }
                case REMOVE_ATTRIBUTE -> {
                    LdapUser user = ldapUserService.getUser(dc, targetDn);
                    String attrName = params.path("attributeName").asText();
                    List<String> current = user.getValues(attrName);
                    yield objectMapper.writeValueAsString(current);
                }
                case MOVE_OU -> {
                    // Capture current parent DN
                    int commaIdx = targetDn.indexOf(',');
                    yield commaIdx > 0 ? targetDn.substring(commaIdx + 1) : "";
                }
                case DISABLE, ENABLE -> {
                    // Check if account is currently locked/disabled
                    LdapUser user = ldapUserService.getUser(dc, targetDn);
                    boolean locked = user.getFirstValue("pwdAccountLockedTime") != null
                            || "TRUE".equalsIgnoreCase(user.getFirstValue("loginDisabled"));
                    yield locked ? "disabled" : "enabled";
                }
                case DELETE -> "non-reversible";
                case NOTIFY -> ""; // notifications can't be undone
            };
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Reverses a single step using the captured previous state.
     */
    private void rollbackStep(DirectoryConnection dc, String targetDn,
                               String actionStr, String previousState) {
        PlaybookStepAction action = PlaybookStepAction.valueOf(actionStr);

        switch (action) {
            case ADD_TO_GROUP -> {
                // Reverse: remove from group — but we need to know which group
                // The step result's previousState is empty for ADD_TO_GROUP,
                // but we can read it from the step parameters stored in the execution
                log.info("Rollback ADD_TO_GROUP not possible without params in previous state");
            }
            case REMOVE_FROM_GROUP -> {
                log.info("Rollback REMOVE_FROM_GROUP not possible without params in previous state");
            }
            case REMOVE_ALL_GROUPS -> {
                // Re-add to all groups that were captured
                if (previousState != null && !previousState.isBlank()) {
                    try {
                        List<String> groups = objectMapper.readValue(previousState,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                        for (String groupDn : groups) {
                            try {
                                ldapGroupService.addMember(dc, groupDn, "member", targetDn);
                            } catch (Exception e) {
                                try {
                                    ldapGroupService.addMember(dc, groupDn, "uniqueMember", targetDn);
                                } catch (Exception ignored) { }
                            }
                        }
                    } catch (JsonProcessingException ignored) { }
                }
            }
            case SET_ATTRIBUTE, REMOVE_ATTRIBUTE -> {
                // Restore previous attribute values
                if (previousState != null && !previousState.isBlank()) {
                    try {
                        List<String> values = objectMapper.readValue(previousState,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                        // We need the attribute name — extract from step results context
                        // For now, log a warning; proper rollback needs the original params
                        log.warn("Attribute rollback requires original step params — manual intervention needed");
                    } catch (JsonProcessingException ignored) { }
                }
            }
            case MOVE_OU -> {
                if (previousState != null && !previousState.isBlank()) {
                    // Move back to original parent
                    // The targetDn may have changed — reconstruct from RDN + previous parent
                    ldapUserService.moveUser(dc, targetDn, previousState);
                }
            }
            case DISABLE -> {
                if ("enabled".equals(previousState)) {
                    ldapUserService.enableUser(dc, targetDn);
                }
            }
            case ENABLE -> {
                if ("disabled".equals(previousState)) {
                    ldapUserService.disableUser(dc, targetDn);
                }
            }
            case DELETE -> log.warn("Cannot rollback DELETE action for {}", targetDn);
            case NOTIFY -> { /* Notifications cannot be undone */ }
        }
    }

    private String describeStep(PlaybookStep step) {
        JsonNode params = parseParams(step.getParameters());
        return switch (step.getAction()) {
            case ADD_TO_GROUP -> "Add to group " + params.path("groupDn").asText();
            case REMOVE_FROM_GROUP -> "Remove from group " + params.path("groupDn").asText();
            case REMOVE_ALL_GROUPS -> "Remove from all groups";
            case SET_ATTRIBUTE -> "Set " + params.path("attributeName").asText() +
                    " = " + params.path("values").toString();
            case REMOVE_ATTRIBUTE -> "Remove attribute " + params.path("attributeName").asText();
            case MOVE_OU -> "Move to " + params.path("targetDn").asText();
            case DISABLE -> "Disable account";
            case ENABLE -> "Enable account";
            case DELETE -> "Delete account (IRREVERSIBLE)";
            case NOTIFY -> "Send notification to " + params.path("recipients").asText();
        };
    }

    private JsonNode parseParams(String json) {
        try {
            return objectMapper.readTree(json != null ? json : "{}");
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private void applyFields(LifecyclePlaybook pb, String name, String description,
                              String type, UUID profileId, boolean requireApproval, boolean enabled) {
        pb.setName(name);
        pb.setDescription(description);
        pb.setType(PlaybookType.valueOf(type));
        pb.setRequireApproval(requireApproval);
        pb.setEnabled(enabled);
        if (profileId != null) {
            pb.setProfile(profileRepo.findById(profileId).orElse(null));
        } else {
            pb.setProfile(null);
        }
    }

    private void saveSteps(LifecyclePlaybook pb, List<StepEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        for (int i = 0; i < entries.size(); i++) {
            StepEntry e = entries.get(i);
            PlaybookStep s = new PlaybookStep();
            s.setPlaybook(pb);
            s.setStepOrder(i);
            s.setAction(PlaybookStepAction.valueOf(e.action()));
            s.setParameters(e.parameters() != null ? e.parameters() : "{}");
            s.setContinueOnError(e.continueOnError());
            stepRepo.save(s);
        }
    }

    private PlaybookResponse toResponse(LifecyclePlaybook pb) {
        List<PlaybookStep> steps = stepRepo.findAllByPlaybookIdOrderByStepOrderAsc(pb.getId());
        return PlaybookResponse.from(pb, steps);
    }

    private LifecyclePlaybook requirePlaybook(UUID directoryId, UUID playbookId) {
        return playbookRepo.findByIdAndDirectoryId(playbookId, directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("LifecyclePlaybook", playbookId));
    }

    private DirectoryConnection requireDirectory(UUID directoryId) {
        return dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
    }
}
