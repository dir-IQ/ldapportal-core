// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.core.governance.MembershipGate;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.ldap.validation.DnValidator;
import com.ldapportal.ldap.validation.LdapAttributeValidator;
import com.ldapportal.ldap.validation.NamingAttributes;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.dto.csv.BulkDeletePreviewResult;
import com.ldapportal.dto.csv.BulkDeletePreviewRow;
import com.ldapportal.dto.csv.BulkDeleteRequest;
import com.ldapportal.dto.csv.BulkDeleteResult;
import com.ldapportal.dto.csv.BulkDeleteRowResult;
import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.dto.csv.BulkImportRequest;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.CsvColumnMappingDto;
import com.ldapportal.dto.ldap.AttributeModification;
import com.ldapportal.dto.ldap.BulkAttributeUpdateRequest;
import com.ldapportal.dto.ldap.BulkAttributeUpdateResult;
import com.ldapportal.dto.ldap.CreateEntryRequest;
import com.ldapportal.dto.ldap.LdapEntryResponse;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.dto.ldap.MoveUserRequest;
import com.ldapportal.dto.ldap.UpdateEntryRequest;
import com.ldapportal.core.provisioning.ProvisioningRefusedException;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalRequestType;
import com.ldapportal.entity.CsvMappingTemplate;
import com.ldapportal.entity.CsvMappingTemplateEntry;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.InputType;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapBrowseService;
import com.ldapportal.ldap.LdapBrowseService.BrowseResult;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapSchemaService;
import com.ldapportal.ldap.LdapSchemaService.AttributeTypeInfo;
import com.ldapportal.ldap.LdapSchemaService.ObjectClassAttributes;
import com.ldapportal.ldap.LdapSchemaService.SchemaListItem;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Permission-checked façade over the raw LDAP services.
 *
 * <p>This service is the single entry-point for all LDAP directory operations
 * in the REST layer.  Each method:</p>
 * <ol>
 *   <li>Loads the {@link DirectoryConnection} and verifies it is enabled.</li>
 *   <li>Enforces branch access (dimension 3) for entry-level operations.</li>
 *   <li>Delegates to the underlying LDAP service.</li>
 *   <li>Fires an async audit event via {@link AuditService} for write ops.</li>
 * </ol>
 *
 * <p>Feature permission checks (dimensions 1, 2, 4) are enforced by the
 * {@link com.ldapportal.auth.FeaturePermissionAspect} via
 * {@link com.ldapportal.auth.RequiresFeature} annotations on the calling
 * controller methods. Directory-access checks for read-only operations
 * (which carry no feature annotation) are performed here directly.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LdapOperationService {

    private final DirectoryConnectionRepository dirRepo;
    private final PermissionService             permissionService;
    private final LdapBrowseService             browseService;
    private final LdapUserService               userService;
    private final LdapGroupService              groupService;
    private final LdapSchemaService             schemaService;
    private final AuditService                  auditService;
    private final BulkUserService               bulkUserService;
    private final BulkGroupService              bulkGroupService;
    private final CsvMappingTemplateService     csvTemplateService;
    private final MembershipGate                membershipGate;
    // Lazy: ProvisioningProfileService isn't on the hot path of this
    // service, and lazy resolution keeps the constructor graph
    // unchanged if the service is ever swapped for a stub in tests.
    private final org.springframework.beans.factory.ObjectProvider<ProvisioningProfileService> profileServiceProvider;
    // Lazy: ApprovalWorkflowService constructor-injects this service, so a
    // direct dependency would form a cycle. Only the batch membership path
    // needs it, and it resolves through the provider on demand.
    private final org.springframework.beans.factory.ObjectProvider<ApprovalWorkflowService> approvalServiceProvider;

    // ── Browse ────────────────────────────────────────────────────────────────

    public BrowseResult browse(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireBaseDnWithinScope(principal, directoryId, dn);
        return browseService.browse(dc, dn);
    }

    /**
     * Returns whether an entry exists at {@code dn}. Used by bulk-import
     * flows to validate the parent DN before submitting the CSV.
     */
    public boolean entryExists(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireBaseDnWithinScope(principal, directoryId, dn);
        return browseService.entryExists(dc, dn);
    }

    /**
     * Creates a single missing parent container at {@code dn} so a bulk
     * import can proceed. The objectClass is inferred from the leftmost RDN
     * (ou/cn → organizationalUnit, o → organization). Audited under
     * {@link AuditAction#USER_CREATE} since this is a directory-write op
     * preceding a user import. The DN must lie within the caller's scope
     * and the immediate parent must already exist (we don't recurse).
     */
    public void createContainer(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        browseService.createContainer(dc, dn);
        auditService.record(principal, directoryId, AuditAction.USER_CREATE, dn,
                Map.of("kind", "container"));
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    public List<SchemaListItem> getObjectClassNames(UUID directoryId, AuthPrincipal principal) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getObjectClassNames(dc);
    }

    public List<AttributeTypeInfo> getAttributeTypeNames(UUID directoryId, AuthPrincipal principal) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getAttributeTypeNames(dc);
    }

    public ObjectClassAttributes getObjectClassAttributes(UUID directoryId, AuthPrincipal principal,
                                                          String objectClass) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getAttributesForObjectClass(dc, objectClass);
    }

    public ObjectClassAttributes getObjectClassAttributesBulk(UUID directoryId, AuthPrincipal principal,
                                                              List<String> objectClasses) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getAttributesForObjectClasses(dc, objectClasses);
    }

    public AttributeTypeInfo getAttributeTypeInfo(UUID directoryId, AuthPrincipal principal,
                                                   String attributeName) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getAttributeTypeInfo(dc, attributeName);
    }

    // ── Users — read ──────────────────────────────────────────────────────────

    public List<LdapEntryResponse> searchUsers(UUID directoryId, AuthPrincipal principal,
                                               String filter, String baseDn,
                                               int limit, String[] attributes) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        // Default to person entries (covers person, organizationalPerson, inetOrgPerson)
        String effectiveFilter = (filter == null || filter.isBlank())
                ? "(objectClass=person)" : filter;
        // Resolve the LDAP search base(s). For admins picking "All"
        // profiles (no explicit baseDn) this fans out across the
        // union of their authorized OUs instead of returning every
        // entry under the directory root.
        List<String> bases = permissionService.resolveSearchBaseDns(principal, directoryId, baseDn);
        if (bases.size() == 1) {
            return userService.searchUsers(dc, effectiveFilter, bases.get(0), limit, attributes)
                    .stream().map(LdapEntryResponse::from).toList();
        }
        List<LdapEntryResponse> merged = new ArrayList<>(limit);
        Set<String> seen = new HashSet<>();
        for (String base : bases) {
            int remaining = limit - merged.size();
            if (remaining <= 0) break;
            for (var u : userService.searchUsers(dc, effectiveFilter, base, remaining, attributes)) {
                if (seen.add(u.getDn().toLowerCase(Locale.ROOT))) {
                    merged.add(LdapEntryResponse.from(u));
                    if (merged.size() >= limit) break;
                }
            }
        }
        return merged;
    }

    public LdapEntryResponse getUser(UUID directoryId, AuthPrincipal principal,
                                     String dn, String[] attributes) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        return LdapEntryResponse.from(userService.getUser(dc, dn, attributes));
    }

    // ── Users — write ─────────────────────────────────────────────────────────

    public LdapEntryResponse createUser(UUID directoryId, AuthPrincipal principal,
                                        CreateEntryRequest req) {
        return createUser(directoryId, principal, req, null);
    }

    /**
     * Profile-aware overload. {@code profileId} is threaded through to
     * the interceptor chain via {@link UserCreatePayload}. Callers that
     * resolved the profile from the request's DN (UserController.create,
     * ApprovalWorkflowService.executeUserCreate) pass the profile id so
     * downstream interceptors (ISVA, future addons) and the audit
     * detail layer can use it.
     */
    public LdapEntryResponse createUser(UUID directoryId, AuthPrincipal principal,
                                        CreateEntryRequest req, UUID profileId) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, req.dn());
        DnValidator.requireValidDn(req.dn(), dc.getDirectoryType());

        // Naming consistency: every AVA in the DN's leading RDN must be among
        // the entry's attribute values or vendors other than AD reject the add
        // with a naming violation. Merged (not rejected) so operator-overridden
        // and multi-valued RDNs (o=0001+cn=…) land correctly for every caller,
        // and merged *before* profile validation so the naming values are
        // validated like any other.
        req = new CreateEntryRequest(req.dn(),
                NamingAttributes.mergeRdnValues(req.dn(), req.attributes(), dc.getDirectoryType()));

        // Enforce the matched profile's attribute rules (required/length/regex/
        // allowed-values) on the admin create path, mirroring the self-service
        // path. Defaults/computed values are already applied by the caller.
        ProvisioningProfileService createProfileSvc = profileServiceProvider.getIfAvailable();
        // Callers that already resolved the profile pass it through; for any
        // caller that didn't (e.g. the no-profile overload), resolve it from
        // the target DN so validation runs regardless of entry point. This is
        // the same resolution the controller/approval paths use, so an
        // unprofiled OU still resolves to null and is correctly not validated.
        if (createProfileSvc != null && profileId == null) {
            profileId = createProfileSvc.resolveProfileForDn(directoryId, req.dn())
                    .map(p -> p.getId()).orElse(null);
        }
        if (createProfileSvc != null && profileId != null) {
            createProfileSvc.validateAttributes(profileId, req.attributes());
        }
        // Syntax layer (DN-valued / email / boolean attributes), directory-type
        // aware. Runs even for an unprofiled DN — well-known attributes (manager,
        // mail, …) are still shape-checked. Profiled DN_LOOKUP/BOOLEAN fields are
        // resolved from the matched profile's input types.
        Map<String, InputType> inputTypes = createProfileSvc == null
                ? Map.of() : createProfileSvc.inputTypesForProfile(profileId);
        LdapAttributeValidator.validateSyntax(dc.getDirectoryType(), req.attributes(), inputTypes);

        userService.createUser(dc, req.dn(), req.attributes(), profileId);
        LdapEntryResponse result = LdapEntryResponse.from(userService.getUser(dc, req.dn()));
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("attributes", req.attributes().keySet());
        if (profileId != null) detail.put("profileId", profileId.toString());
        auditService.record(principal, directoryId, AuditAction.USER_CREATE, req.dn(), detail);
        return result;
    }

    public LdapEntryResponse updateUser(UUID directoryId, AuthPrincipal principal,
                                        String dn, UpdateEntryRequest req) {
        return updateUser(directoryId, principal, dn, req, null);
    }

    /**
     * Conditional-update overload. {@code ifUnmodifiedSince} carries the
     * {@code modifyTimestamp} the client loaded (the
     * {@code If-Unmodified-Since-LDAP} header from the inline-edit table);
     * when present and the entry has changed since, the update is refused
     * with 412 so the caller reloads instead of overwriting blind.
     */
    public LdapEntryResponse updateUser(UUID directoryId, AuthPrincipal principal,
                                        String dn, UpdateEntryRequest req,
                                        String ifUnmodifiedSince) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        if (ifUnmodifiedSince != null && !ifUnmodifiedSince.isBlank()) {
            requireUnmodifiedSince(
                    userService.getUser(dc, dn, "modifyTimestamp"), dn, ifUnmodifiedSince);
        }

        // Enforce the matched profile's value rules (length/regex/allowed) on
        // the attributes being modified. Required-on-create is intentionally
        // NOT enforced here — an attribute absent from this update is not a
        // missing-required error.
        ProvisioningProfileService updateProfileSvc = profileServiceProvider.getIfAvailable();
        Map<String, List<String>> modifiedValues = modifiedAttributeValues(req);
        Map<String, InputType> inputTypes = Map.of();
        if (updateProfileSvc != null) {
            UUID profileId = updateProfileSvc.resolveProfileForDn(directoryId, dn)
                    .map(p -> p.getId()).orElse(null);
            if (profileId != null) {
                updateProfileSvc.validateModification(
                        profileId, modifiedAttributeNames(req), modifiedValues);
                inputTypes = updateProfileSvc.inputTypesForProfile(profileId);
            }
        }
        // Syntax layer on the modified attributes only — an attribute absent from
        // this update is not re-checked, so existing looser data is never touched.
        LdapAttributeValidator.validateSyntax(dc.getDirectoryType(), modifiedValues, inputTypes);

        List<Modification> mods = toModifications(req);
        userService.updateUser(dc, dn, mods);
        // "*" + modifyTimestamp: the operational timestamp isn't returned by
        // default, but conditional callers (the inline-edit table) need the
        // fresh value to keep guarding their next save.
        LdapEntryResponse result = LdapEntryResponse.from(
                userService.getUser(dc, dn, "*", "modifyTimestamp"));
        auditService.record(principal, directoryId, AuditAction.USER_UPDATE, dn,
                Map.of("modifiedAttributes", req.modifications().stream()
                        .map(AttributeModification::attribute).toList()));
        return result;
    }

    public LdapEntryResponse updateGroup(UUID directoryId, AuthPrincipal principal,
                                         String dn, UpdateEntryRequest req) {
        return updateGroup(directoryId, principal, dn, req, null);
    }

    /** Conditional-update overload — see {@link #updateUser(UUID, AuthPrincipal, String, UpdateEntryRequest, String)}. */
    public LdapEntryResponse updateGroup(UUID directoryId, AuthPrincipal principal,
                                         String dn, UpdateEntryRequest req,
                                         String ifUnmodifiedSince) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        if (ifUnmodifiedSince != null && !ifUnmodifiedSince.isBlank()) {
            requireUnmodifiedSince(
                    groupService.getGroup(dc, dn, "modifyTimestamp"), dn, ifUnmodifiedSince);
        }
        LdapAttributeValidator.validateSyntax(
                dc.getDirectoryType(), modifiedAttributeValues(req), Map.of());

        List<Modification> mods = toModifications(req);
        groupService.updateGroup(dc, dn, mods);
        LdapEntryResponse result = LdapEntryResponse.from(
                groupService.getGroup(dc, dn, "*", "modifyTimestamp"));
        auditService.record(principal, directoryId, AuditAction.GROUP_UPDATE, dn,
                Map.of("modifiedAttributes", req.modifications().stream()
                        .map(AttributeModification::attribute).toList()));
        return result;
    }

    /**
     * The pre-write half of the optimistic-concurrency check: compare the
     * entry's current {@code modifyTimestamp} against the one the client
     * loaded. An entry that doesn't expose {@code modifyTimestamp} skips the
     * check — degrading to today's last-write-wins is better than blocking
     * every save on a directory that withholds operational attributes.
     */
    private static void requireUnmodifiedSince(com.ldapportal.ldap.model.LdapEntry current,
                                               String dn, String expected) {
        String actual = current.getFirstValue("modifytimestamp");
        if (actual == null || actual.isBlank()) {
            return;
        }
        if (!actual.trim().equalsIgnoreCase(expected.trim())) {
            throw new com.ldapportal.exception.PreconditionFailedException(
                    "Entry [" + dn + "] changed since it was loaded (modifyTimestamp now "
                            + actual + ", was " + expected
                            + "). Reload the row and reapply the edit.");
        }
    }

    public BulkAttributeUpdateResult bulkUpdateAttributes(UUID directoryId, AuthPrincipal principal,
                                                           BulkAttributeUpdateRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        req.dns().forEach(dn -> permissionService.requireDnWithinScope(principal, directoryId, dn));

        List<Modification> mods = req.modifications().stream()
                .map(m -> new Modification(
                        toModType(m.operation()),
                        m.attribute(),
                        m.values() == null ? new String[0]
                                : m.values().toArray(new String[0])))
                .toList();

        // The same modifications apply to every DN, so syntax-check the written
        // values once up front (well-known attributes only — a bulk update spans
        // many entries with potentially different profiles). A malformed value
        // fails the whole request before any entry is touched.
        Map<String, List<String>> modifiedValues = new java.util.LinkedHashMap<>();
        for (AttributeModification m : req.modifications()) {
            if (m.operation() != AttributeModification.Operation.DELETE
                    && m.values() != null && !m.values().isEmpty()) {
                modifiedValues.put(m.attribute(), m.values());
            }
        }
        LdapAttributeValidator.validateSyntax(dc.getDirectoryType(), modifiedValues, Map.of());

        int updated = 0;
        List<BulkAttributeUpdateResult.BulkUpdateError> failures = new ArrayList<>();

        for (String dn : req.dns()) {
            try {
                userService.updateUser(dc, dn, mods);
                updated++;
            } catch (Exception e) {
                log.warn("Bulk attribute update failed for DN {}: {}", dn, e.getMessage());
                failures.add(new BulkAttributeUpdateResult.BulkUpdateError(dn, e.getMessage()));
            }
        }

        auditService.record(principal, directoryId, AuditAction.BULK_ATTRIBUTE_UPDATE, null,
                Map.of("totalDns", req.dns().size(),
                        "updated", updated,
                        "errors", failures.size(),
                        "modifiedAttributes", req.modifications().stream()
                                .map(AttributeModification::attribute).toList()));

        return new BulkAttributeUpdateResult(updated, failures.size(), failures);
    }

    public void deleteUser(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        UUID profileId = deleteResolvedUser(dc, directoryId, principal, dn);

        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        if (profileId != null) detail.put("profileId", profileId.toString());
        auditService.record(principal, directoryId, AuditAction.USER_DELETE, dn,
                detail.isEmpty() ? null : detail);
    }

    /**
     * Deletes one already-scope-checked user and returns the resolved profile
     * id (or null). Resolves the matching profile BEFORE the delete so we can
     * clean up profile-assigned group memberships afterwards — the interceptor
     * chain may turn a delete into a soft-disable (ISVA's default policy), and
     * either way the user's profile group memberships should be removed so
     * reports based on group membership don't see ghost members. Records no
     * audit itself: the single-delete path emits one record per user, while
     * the bulk path emits a single summary record for the whole batch.
     */
    private UUID deleteResolvedUser(DirectoryConnection dc, UUID directoryId,
                                    AuthPrincipal principal, String dn) {
        ProvisioningProfileService ps = profileServiceProvider.getIfAvailable();
        UUID profileId = ps == null ? null
                : ps.resolveProfileForDn(directoryId, dn).map(p -> p.getId()).orElse(null);

        userService.deleteUser(dc, dn, profileId);

        if (ps != null && profileId != null) {
            try {
                ps.removeUserFromProfileGroups(directoryId, profileId, dn, principal);
            } catch (Exception e) {
                // Don't fail the delete for a group-cleanup hiccup —
                // user is gone (or soft-disabled); log + continue.
                log.warn("Failed to clean up profile group memberships for {}: {}",
                        dn, e.getMessage());
            }
        }
        return profileId;
    }

    public void enableUser(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        userService.enableUser(dc, dn);
        auditService.record(principal, directoryId, AuditAction.USER_ENABLE, dn, null);
    }

    public void disableUser(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        userService.disableUser(dc, dn);
        auditService.record(principal, directoryId, AuditAction.USER_DISABLE, dn, null);
    }

    public void moveUser(UUID directoryId, AuthPrincipal principal,
                         String dn, MoveUserRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        permissionService.requireDnWithinScope(principal, directoryId, req.newParentDn());
        DnValidator.requireValidDn(req.newParentDn(), dc.getDirectoryType());

        userService.moveUser(dc, dn, req.newParentDn());
        auditService.record(principal, directoryId, AuditAction.USER_MOVE, dn,
                Map.of("newParentDn", req.newParentDn()));
    }

    public void resetPassword(UUID directoryId, AuthPrincipal principal,
                              String dn, String newPassword) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        // Resolve the profile so an interceptor can exempt a FORCE_OFF
        // entry (plain password write, no secUser stamping).
        ProvisioningProfileService ps = profileServiceProvider.getIfAvailable();
        UUID profileId = ps == null ? null
                : ps.resolveProfileForDn(directoryId, dn).map(p -> p.getId()).orElse(null);

        userService.resetPassword(dc, dn, newPassword, profileId);
        auditService.record(principal, directoryId, AuditAction.PASSWORD_RESET, dn, null);
    }

    // ── Groups — read ─────────────────────────────────────────────────────────

    public List<LdapEntryResponse> searchGroups(UUID directoryId, AuthPrincipal principal,
                                                String filter, String baseDn,
                                                int limit, String[] attributes) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);

        // Always intersect with the directory's configured (or vendor-default)
        // group object classes so non-group entries are excluded.
        String groupObjectClassFilter = com.ldapportal.entity.DirectoryObjectClassDefaults.orFilter(
                com.ldapportal.entity.DirectoryObjectClassDefaults.effectiveGroupObjectClasses(dc));
        String effectiveFilter;
        if (filter == null || filter.isBlank()) {
            effectiveFilter = groupObjectClassFilter;
        } else {
            effectiveFilter = "(&" + filter + groupObjectClassFilter + ")";
        }
        // Same fan-out story as searchUsers — see comment there.
        List<String> bases = permissionService.resolveSearchBaseDns(principal, directoryId, baseDn);
        if (bases.size() == 1) {
            return groupService.searchGroups(dc, effectiveFilter, bases.get(0), limit, attributes)
                    .stream().map(LdapEntryResponse::from).toList();
        }
        List<LdapEntryResponse> merged = new ArrayList<>(limit);
        Set<String> seen = new HashSet<>();
        for (String base : bases) {
            int remaining = limit - merged.size();
            if (remaining <= 0) break;
            for (var g : groupService.searchGroups(dc, effectiveFilter, base, remaining, attributes)) {
                if (seen.add(g.getDn().toLowerCase(Locale.ROOT))) {
                    merged.add(LdapEntryResponse.from(g));
                    if (merged.size() >= limit) break;
                }
            }
        }
        return merged;
    }

    public LdapEntryResponse getGroup(UUID directoryId, AuthPrincipal principal,
                                      String dn, String[] attributes) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        return LdapEntryResponse.from(groupService.getGroup(dc, dn, attributes));
    }

    public List<String> getGroupMembers(UUID directoryId, AuthPrincipal principal,
                                        String dn, String memberAttribute) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        return groupService.getMembers(dc, dn, memberAttribute);
    }

    // ── Groups — write ────────────────────────────────────────────────────────

    public LdapEntryResponse createGroup(UUID directoryId, AuthPrincipal principal,
                                         CreateEntryRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, req.dn());
        DnValidator.requireValidDn(req.dn(), dc.getDirectoryType());
        // Groups carry no profile; well-known attribute syntax (owner / member /
        // uniqueMember DNs) is still enforced before the write.
        LdapAttributeValidator.validateSyntax(dc.getDirectoryType(), req.attributes(), Map.of());

        groupService.createGroup(dc, req.dn(), req.attributes());
        LdapEntryResponse result = LdapEntryResponse.from(groupService.getGroup(dc, req.dn()));
        auditService.record(principal, directoryId, AuditAction.GROUP_CREATE, req.dn(),
                Map.of("attributes", req.attributes().keySet()));
        return result;
    }

    public void deleteGroup(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        groupService.deleteGroup(dc, dn);
        auditService.record(principal, directoryId, AuditAction.GROUP_DELETE, dn, null);
    }

    public void addGroupMember(UUID directoryId, AuthPrincipal principal,
                               String groupDn, String memberAttribute, String memberValue) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, groupDn);

        // SoD check — may throw SodViolationException (409) if BLOCK policy is violated
        membershipGate.checkMembership(directoryId, memberValue, groupDn, principal);

        // Resolve the member's profile (memberValue is a DN for
        // member/uniqueMember; a bare uid for memberUid resolves to
        // none → directory behaviour) so an interceptor can exempt a
        // FORCE_OFF member from vendor-specific membership routing.
        ProvisioningProfileService ps = profileServiceProvider.getIfAvailable();
        UUID memberProfileId = ps == null ? null
                : ps.resolveProfileForDn(directoryId, memberValue).map(p -> p.getId()).orElse(null);

        groupService.addMember(dc, groupDn, memberAttribute, memberValue, memberProfileId);
        auditService.record(principal, directoryId, AuditAction.GROUP_MEMBER_ADD, groupDn,
                Map.of("attribute", memberAttribute, "member", memberValue));
    }

    public void removeGroupMember(UUID directoryId, AuthPrincipal principal,
                                  String groupDn, String memberAttribute, String memberValue) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, groupDn);

        groupService.removeMember(dc, groupDn, memberAttribute, memberValue);
        auditService.record(principal, directoryId, AuditAction.GROUP_MEMBER_REMOVE, groupDn,
                Map.of("attribute", memberAttribute, "member", memberValue));
    }

    /**
     * Applies a batch of group-membership changes for a single member in one
     * request. Each change is attempted independently and classified into the
     * returned {@link MembershipChangeResult} — one failure never aborts the
     * rest. Because every per-change audit record runs on the request thread,
     * they share its correlation id, so the whole batch is traceable as one
     * operation in the audit log.
     *
     * <p>Scope: the batch is keyed on {@code memberDn}, so the caller must own
     * that user; per-group scope is still enforced inside the single-member
     * add/remove paths. Authorization failures on a target group abort the
     * whole batch (they are never softened into a per-item result).</p>
     *
     * <p>Ordering: removes run before adds so that clearing a soon-to-be-removed
     * membership first lets a Separation-of-Duties BLOCK policy accept an add
     * that would otherwise conflict with the membership being dropped.</p>
     *
     * <p>Approvals reuse {@link ApprovalRequestType#GROUP_MEMBER_ADD} per add,
     * exactly like the single-member endpoint; removes are never gated,
     * consistent with the rest of the member surface.</p>
     */
    public MembershipChangeResult applyMembershipChanges(
            UUID directoryId, AuthPrincipal principal,
            String memberDn, List<MembershipChangeRequest.Change> changes) {

        loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, memberDn);

        // Pre-flight authorization: every target group must be within the
        // admin's scope before any write happens. An out-of-scope group fails
        // the whole request up front (403) with zero partial application —
        // rather than aborting mid-batch after earlier items already persisted.
        for (MembershipChangeRequest.Change c : changes) {
            permissionService.requireDnWithinScope(principal, directoryId, c.groupDn());
        }

        List<MembershipChangeRequest.Change> ordered = new ArrayList<>(changes);
        ordered.sort(Comparator.comparingInt(
                c -> c.op() == MembershipChangeRequest.Op.REMOVE ? 0 : 1));

        ApprovalWorkflowService approvalService = approvalServiceProvider.getIfAvailable();
        List<MembershipChangeResult.Item> items = new ArrayList<>(ordered.size());
        for (MembershipChangeRequest.Change c : ordered) {
            items.add(applyMembershipChange(directoryId, principal, memberDn, c, approvalService));
        }
        return MembershipChangeResult.of(items);
    }

    private MembershipChangeResult.Item applyMembershipChange(
            UUID directoryId, AuthPrincipal principal, String memberDn,
            MembershipChangeRequest.Change c, ApprovalWorkflowService approvalService) {
        try {
            if (c.op() == MembershipChangeRequest.Op.REMOVE) {
                removeGroupMember(directoryId, principal, c.groupDn(), c.memberAttribute(), memberDn);
                return MembershipChangeResult.Item.applied(c);
            }

            // ADD — gate through the approval workflow first (reuses
            // GROUP_MEMBER_ADD). When no approver is configured the gate
            // returns empty and we apply immediately.
            if (approvalService != null) {
                Optional<PendingApproval> pending = approvalService.checkAndSubmitForApproval(
                        directoryId, c.groupDn(), principal, ApprovalRequestType.GROUP_MEMBER_ADD,
                        Map.of("groupDn", c.groupDn(),
                                "memberAttribute", c.memberAttribute(),
                                "memberValue", memberDn));
                if (pending.isPresent()) {
                    return MembershipChangeResult.Item.queued(c, pending.get().getId());
                }
            }

            addGroupMember(directoryId, principal, c.groupDn(), c.memberAttribute(), memberDn);
            return MembershipChangeResult.Item.applied(c);

        } catch (ProvisioningRefusedException e) {
            return MembershipChangeResult.Item.refused(c, e.getMessage());
        } catch (AccessDeniedException e) {
            // Defense-in-depth: applyMembershipChanges pre-validates every group
            // DN, so this is unreachable in the normal flow — but never soften an
            // authz failure into a per-item result. Re-throw so the request 403s.
            throw e;
        } catch (RuntimeException e) {
            // Idempotency: re-adding a member that already exists, or removing one
            // that isn't present, is a no-op — the desired membership state
            // already holds. Treat it as applied so a bulk op across users with
            // mixed current membership doesn't surface spurious failures.
            if (isIdempotentNoOp(c.op(), e)) {
                return MembershipChangeResult.Item.applied(c);
            }
            // A Separation-of-Duties BLOCK surfaces as a SodViolationException
            // from the governance addon, which core must not import (edition
            // boundary). Detect it by simple name so the UI gets a distinct
            // BLOCKED status without a core→addon dependency.
            if ("SodViolationException".equals(e.getClass().getSimpleName())) {
                return MembershipChangeResult.Item.blocked(c, e.getMessage());
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return MembershipChangeResult.Item.errored(c, msg);
        }
    }

    /**
     * True when the failure is the idempotent no-op for this op: adding a member
     * value that already exists ({@code ATTRIBUTE_OR_VALUE_EXISTS}) or removing a
     * value that isn't present ({@code NO_SUCH_ATTRIBUTE}). The LDAP result code
     * is read from the cause chain (the write wraps the UnboundID
     * {@link LDAPException} inside an {@code LdapOperationException}).
     */
    private static boolean isIdempotentNoOp(MembershipChangeRequest.Op op, Throwable failure) {
        ResultCode rc = ldapResultCode(failure);
        if (rc == null) {
            return false;
        }
        return switch (op) {
            case ADD    -> rc == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS;
            case REMOVE -> rc == ResultCode.NO_SUCH_ATTRIBUTE;
        };
    }

    /** Walks the cause chain for an UnboundID {@link LDAPException} and returns its result code, or null. */
    private static ResultCode ldapResultCode(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof LDAPException le) {
                return le.getResultCode();
            }
        }
        return null;
    }

    // ── Bulk import / export ──────────────────────────────────────────────────

    /**
     * Previews a bulk CSV import without writing to LDAP.
     * Resolves template settings and returns computed DNs for each row.
     */
    public BulkImportPreviewResult previewBulkImport(UUID directoryId, AuthPrincipal principal,
                                                      InputStream csvInput,
                                                      BulkImportRequest req) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        // Scope-check the parent DN the same way the actual import does
        // (bulkImportUsers below). Without this, an admin could preview
        // imports into OUs they don't own — even though the actual
        // import would 403, the preview leaks the OU's existence + the
        // import-validation behaviour for entries the admin can't
        // create.
        permissionService.requireDnWithinScope(principal, directoryId, req.parentDn());

        String targetKeyAttr = "uid";
        String dnSourceColumn = null;
        List<CsvColumnMappingDto> mappings = req.columnMappings() != null
                ? new ArrayList<>(req.columnMappings()) : new ArrayList<>();
        List<String> objectClasses = List.of();

        if (req.templateId() != null) {
            CsvMappingTemplate template =
                    csvTemplateService.loadTemplate(req.templateId(), directoryId, principal);
            List<CsvMappingTemplateEntry> entries =
                    csvTemplateService.loadEntries(req.templateId());
            targetKeyAttr = template.getTargetKeyAttribute();
            dnSourceColumn = template.getDnSourceColumn();
            if (mappings.isEmpty()) {
                mappings = entries.stream()
                        .map(e -> new CsvColumnMappingDto(
                                e.getCsvColumnName(), e.getLdapAttribute(), e.isIgnored()))
                        .toList();
            }
            if (template.getObjectClass() != null && !template.getObjectClass().isBlank()) {
                objectClasses = List.of(template.getObjectClass().split("\\s*,\\s*"));
            }
        }

        if (req.targetKeyAttribute() != null) targetKeyAttr = req.targetKeyAttribute();
        if (req.dnSourceColumn() != null) dnSourceColumn = req.dnSourceColumn();
        if (dnSourceColumn != null && dnSourceColumn.isBlank()) dnSourceColumn = null;

        boolean skipHeader = resolveSkipHeaderRow(req.skipHeaderRow(), req.templateId(), directoryId, principal);

        // Resolve the object classes' MUST attributes via schema so the preview
        // can flag rows missing values for any of them. Skip attributes that
        // are special-cased (objectClass itself) or unrelated to per-row CSV
        // input (the RDN/key attribute is reported via its own error path
        // when missing). The empty list short-circuits validation when no
        // template is in play.
        List<String> requiredAttrs = List.of();
        if (!objectClasses.isEmpty()) {
            var schema = schemaService.getAttributesForObjectClasses(dc, objectClasses);
            String keyAttr = targetKeyAttr;
            requiredAttrs = schema.required().stream()
                    .filter(a -> !a.equalsIgnoreCase("objectClass"))
                    .filter(a -> !a.equalsIgnoreCase(keyAttr))
                    .toList();
        }

        return bulkUserService.previewImport(
                csvInput, req.parentDn(), targetKeyAttr, mappings, skipHeader, requiredAttrs, dnSourceColumn);
    }

    /**
     * Imports users from a CSV stream into the LDAP directory.
     *
     * <p>Column mappings are resolved from the referenced template (if any),
     * then overridden by any fields set directly on {@code req}.</p>
     */
    public BulkImportResult bulkImportUsers(UUID directoryId, AuthPrincipal principal,
                                            InputStream csvInput,
                                            BulkImportRequest req) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, req.parentDn());

        // Defaults — may be overridden by template or request fields
        String            targetKeyAttr    = "uid";
        ConflictHandling  conflictHandling = ConflictHandling.SKIP;
        List<String>      objectClasses    = List.of();
        String            dnSourceColumn   = null;
        List<CsvColumnMappingDto> mappings = req.columnMappings() != null
                ? new ArrayList<>(req.columnMappings()) : new ArrayList<>();

        if (req.templateId() != null) {
            CsvMappingTemplate template =
                    csvTemplateService.loadTemplate(req.templateId(), directoryId, principal);
            List<CsvMappingTemplateEntry> entries =
                    csvTemplateService.loadEntries(req.templateId());
            targetKeyAttr    = template.getTargetKeyAttribute();
            conflictHandling = template.getConflictHandling();
            dnSourceColumn   = template.getDnSourceColumn();
            if (template.getObjectClass() != null && !template.getObjectClass().isBlank()) {
                objectClasses = List.of(template.getObjectClass().split(","));
            }
            // Template entries are used only when the request carries no ad-hoc mappings
            if (mappings.isEmpty()) {
                mappings = entries.stream()
                        .map(e -> new CsvColumnMappingDto(
                                e.getCsvColumnName(), e.getLdapAttribute(), e.isIgnored()))
                        .toList();
            }
        }

        // Request-level fields take final precedence
        if (req.targetKeyAttribute() != null)  targetKeyAttr    = req.targetKeyAttribute();
        if (req.conflictHandling()    != null)  conflictHandling = req.conflictHandling();
        if (req.dnSourceColumn()      != null)  dnSourceColumn   = req.dnSourceColumn();
        if (dnSourceColumn != null && dnSourceColumn.isBlank()) dnSourceColumn = null;

        boolean skipHeader = resolveSkipHeaderRow(req.skipHeaderRow(), req.templateId(), directoryId, principal);

        // Resolve the matching profile for the import's parent DN. All
        // rows of a bulk import land under the same parent OU, so the
        // profile is the same for every row — no per-row lookup needed.
        // Pass it as a ProfileContext so the bulk service applies the
        // profile's attribute defaults per row and its effective group
        // assignments after each successful create, matching the manual
        // UserController.create + approval-approved create paths.
        ProvisioningProfileService ps = profileServiceProvider.getIfAvailable();
        BulkUserService.ProfileContext profileContext = null;
        if (ps != null) {
            profileContext = ps.resolveProfileForDn(directoryId, req.parentDn())
                    .map(p -> new BulkUserService.ProfileContext(directoryId, p.getId(), principal))
                    .orElse(null);
        }

        BulkImportResult result = bulkUserService.importCsv(
                dc, csvInput, req.parentDn(), targetKeyAttr, conflictHandling, mappings,
                objectClasses, skipHeader, dnSourceColumn, profileContext);

        auditService.record(principal, directoryId, AuditAction.USER_CREATE, req.parentDn(),
                Map.of("operation", "bulkImport",
                       "created",   result.created(),
                       "updated",   result.updated(),
                       "skipped",   result.skipped(),
                       "errors",    result.errors()));
        return result;
    }

    /**
     * Exports directory users to a CSV {@code byte[]} with a header row.
     *
     * <p>When {@code templateId} is supplied and {@code attributes} is empty,
     * the attribute list is derived from the template's non-ignored entries.</p>
     */
    public byte[] bulkExportUsers(UUID directoryId, AuthPrincipal principal,
                                  String filter, String baseDn,
                                  List<String> attributes, UUID templateId) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);

        // Same fan-out story as searchUsers: admin with no explicit
        // baseDn (e.g. 'All' picker option) gets the union of their
        // authorized OUs, not the directory root. resolveSearchBaseDns
        // also clamps a strict-ancestor baseDn down to the authorized
        // OUs so the CSV doesn't leak entries outside scope.
        List<String> bases = permissionService.resolveSearchBaseDns(principal, directoryId, baseDn);

        List<String> effectiveAttrs = new ArrayList<>(attributes);
        if (effectiveAttrs.isEmpty() && templateId != null) {
            csvTemplateService.loadEntries(templateId).stream()
                    .filter(e -> !e.isIgnored() && e.getLdapAttribute() != null)
                    .map(CsvMappingTemplateEntry::getLdapAttribute)
                    .forEach(effectiveAttrs::add);
        }

        return bulkUserService.exportCsvFromBases(dc, filter, bases, effectiveAttrs);
    }

    // ── Bulk delete ───────────────────────────────────────────────────────────

    /** Per-request safety cap. A destructive op shouldn't take an unbounded
     *  file in one shot; over-cap requests are rejected with guidance to split. */
    static final int MAX_BULK_DELETE_ROWS = 500;

    /** Internal classification of one CSV row after resolution (no writes). */
    private record ResolvedDeleteRow(int rowNumber, String dn,
                                     BulkDeletePreviewRow.Disposition disposition, String note) {}

    /**
     * Dry-run preview for a bulk delete: resolves every CSV row to a target DN
     * and classifies what would happen on commit, without touching the
     * directory. This is the safety centrepiece — the frontend forces a preview
     * before the (typed-confirmed) commit.
     */
    public BulkDeletePreviewResult previewBulkDelete(UUID directoryId, AuthPrincipal principal,
                                                     InputStream csvInput,
                                                     BulkDeleteRequest req) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        validateBulkDeleteRequest(dc, principal, directoryId, req);

        List<BulkUserService.RawDeleteRow> rawRows = bulkUserService.parseDeleteRows(
                csvInput, resolveValueColumn(req), skipHeader(req.skipHeaderRow()));
        enforceBulkDeleteCap(rawRows.size());

        List<BulkDeletePreviewRow> rows = resolveBulkDelete(dc, directoryId, principal, rawRows, req)
                .stream()
                .map(r -> new BulkDeletePreviewRow(r.rowNumber(), r.dn(), r.disposition(), r.note()))
                .toList();
        return new BulkDeletePreviewResult(rows.size(), rows);
    }

    /**
     * Commits a bulk delete. Re-resolves the CSV (so the result reflects the
     * directory's current state, not a stale preview) and deletes every row
     * that resolves to a single in-scope, existing entry. Non-deletable rows
     * become SKIPPED (not found) or ERROR (out of scope / ambiguous / invalid).
     * One summary audit record is written for the whole batch, including the
     * list of DNs actually deleted.
     *
     * <p>No approval workflow — a deliberate product decision (mirrors single
     * delete). The destructive op is gated by the {@code bulk.delete} feature
     * plus the mandatory preview, typed confirmation, and row cap.</p>
     */
    public BulkDeleteResult bulkDeleteUsers(UUID directoryId, AuthPrincipal principal,
                                            InputStream csvInput,
                                            BulkDeleteRequest req) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        validateBulkDeleteRequest(dc, principal, directoryId, req);

        List<BulkUserService.RawDeleteRow> rawRows = bulkUserService.parseDeleteRows(
                csvInput, resolveValueColumn(req), skipHeader(req.skipHeaderRow()));
        enforceBulkDeleteCap(rawRows.size());

        List<ResolvedDeleteRow> resolved = resolveBulkDelete(dc, directoryId, principal, rawRows, req);

        List<BulkDeleteRowResult> rows = new ArrayList<>();
        List<String> deletedDns = new ArrayList<>();
        for (ResolvedDeleteRow r : resolved) {
            switch (r.disposition()) {
                case WILL_DELETE -> {
                    try {
                        deleteResolvedUser(dc, directoryId, principal, r.dn());
                        deletedDns.add(r.dn());
                        rows.add(BulkDeleteRowResult.deleted(r.rowNumber(), r.dn()));
                    } catch (ResourceNotFoundException nf) {
                        // Vanished between preview/resolve and delete — treat as a skip.
                        rows.add(BulkDeleteRowResult.skipped(r.rowNumber(), r.dn(), "Entry no longer exists"));
                    } catch (Exception e) {
                        log.warn("Bulk delete row {} failed [dn={}]: {}",
                                r.rowNumber(), r.dn(), e.getMessage());
                        rows.add(BulkDeleteRowResult.error(r.rowNumber(), r.dn(), e.getMessage()));
                    }
                }
                case NOT_FOUND -> rows.add(BulkDeleteRowResult.skipped(r.rowNumber(), r.dn(), r.note()));
                case OUT_OF_SCOPE, AMBIGUOUS, INVALID ->
                        rows.add(BulkDeleteRowResult.error(r.rowNumber(), r.dn(), r.note()));
            }
        }

        long deleted = deletedDns.size();
        long skipped = rows.stream().filter(x -> x.status() == BulkDeleteRowResult.Status.SKIPPED).count();
        long errors  = rows.stream().filter(x -> x.status() == BulkDeleteRowResult.Status.ERROR).count();

        log.info("Bulk delete complete: {} rows — deleted={}, skipped={}, errors={}",
                rows.size(), deleted, skipped, errors);

        // Single summary record for the batch (matches bulk import / bulk
        // attribute update). The deleted DNs are folded into the detail so the
        // audit trail names exactly what was removed.
        auditService.record(principal, directoryId, AuditAction.USER_DELETE, null,
                Map.of("operation", "bulkDelete",
                       "totalRows", rows.size(),
                       "deleted", deleted,
                       "skipped", skipped,
                       "errors", errors,
                       "deletedDns", deletedDns));

        return new BulkDeleteResult(rows.size(), deleted, skipped, errors, rows);
    }

    /**
     * Shared resolution used by both preview and commit so "what the preview
     * shows" is exactly "what commit deletes". For each raw row: pick the
     * target DN (DN mode = the cell; key mode = equality search), then
     * scope-check and existence-check to assign a {@link BulkDeletePreviewRow.Disposition}.
     * Scope is checked first (a structural DN check that performs no LDAP read)
     * so an out-of-scope DN is never probed for existence.
     */
    private List<ResolvedDeleteRow> resolveBulkDelete(DirectoryConnection dc, UUID directoryId,
                                                      AuthPrincipal principal,
                                                      List<BulkUserService.RawDeleteRow> rawRows,
                                                      BulkDeleteRequest req) {
        boolean dnMode = isDnMode(req);
        List<ResolvedDeleteRow> out = new ArrayList<>();
        for (BulkUserService.RawDeleteRow raw : rawRows) {
            int n = raw.rowNumber();
            String value = raw.value() == null ? null : raw.value().trim();
            if (value == null || value.isBlank()) {
                out.add(new ResolvedDeleteRow(n, null,
                        BulkDeletePreviewRow.Disposition.INVALID, "No value in the '" + resolveValueColumn(req) + "' column"));
                continue;
            }

            if (dnMode) {
                String dn = value;
                if (!withinScope(principal, directoryId, dn)) {
                    out.add(new ResolvedDeleteRow(n, dn,
                            BulkDeletePreviewRow.Disposition.OUT_OF_SCOPE, "Outside your authorized scope"));
                    continue;
                }
                boolean exists;
                try {
                    exists = userService.entryExists(dc, dn);
                } catch (Exception e) {
                    out.add(new ResolvedDeleteRow(n, dn,
                            BulkDeletePreviewRow.Disposition.INVALID, e.getMessage()));
                    continue;
                }
                out.add(exists
                        ? new ResolvedDeleteRow(n, dn, BulkDeletePreviewRow.Disposition.WILL_DELETE, null)
                        : new ResolvedDeleteRow(n, dn, BulkDeletePreviewRow.Disposition.NOT_FOUND, "No entry at this DN"));
            } else {
                List<String> dns;
                try {
                    dns = bulkUserService.resolveDnsByKey(dc, req.keyAttribute(), value, req.baseDn());
                } catch (Exception e) {
                    out.add(new ResolvedDeleteRow(n, null,
                            BulkDeletePreviewRow.Disposition.INVALID, e.getMessage()));
                    continue;
                }
                if (dns.isEmpty()) {
                    out.add(new ResolvedDeleteRow(n, null, BulkDeletePreviewRow.Disposition.NOT_FOUND,
                            "No entry where " + req.keyAttribute() + "=" + value));
                } else if (dns.size() > 1) {
                    out.add(new ResolvedDeleteRow(n, null, BulkDeletePreviewRow.Disposition.AMBIGUOUS,
                            req.keyAttribute() + "=" + value + " matches multiple entries"));
                } else {
                    String dn = dns.get(0);
                    out.add(withinScope(principal, directoryId, dn)
                            ? new ResolvedDeleteRow(n, dn, BulkDeletePreviewRow.Disposition.WILL_DELETE, null)
                            : new ResolvedDeleteRow(n, dn, BulkDeletePreviewRow.Disposition.OUT_OF_SCOPE, "Outside your authorized scope"));
                }
            }
        }
        return out;
    }

    private void validateBulkDeleteRequest(DirectoryConnection dc, AuthPrincipal principal,
                                           UUID directoryId, BulkDeleteRequest req) {
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException("Bulk delete is not supported for Entra ID directories");
        }
        if (!isDnMode(req)) {
            if (req.baseDn() == null || req.baseDn().isBlank()) {
                throw new IllegalArgumentException(
                        "baseDn is required when deleting by a key attribute");
            }
            // The search base must itself be within the caller's scope, so a
            // key-attribute lookup can't be aimed at OUs the admin doesn't own.
            permissionService.requireDnWithinScope(principal, directoryId, req.baseDn());
        }
    }

    private boolean isDnMode(BulkDeleteRequest req) {
        return req.keyAttribute() == null || req.keyAttribute().isBlank()
                || req.keyAttribute().equalsIgnoreCase("dn");
    }

    private String resolveValueColumn(BulkDeleteRequest req) {
        if (req.valueColumn() != null && !req.valueColumn().isBlank()) return req.valueColumn().trim();
        if (req.keyAttribute() != null && !req.keyAttribute().isBlank()) return req.keyAttribute().trim();
        return "dn";
    }

    private boolean skipHeader(Boolean flag) {
        return flag == null || flag; // default: first row is a header
    }

    private void enforceBulkDeleteCap(int rowCount) {
        if (rowCount > MAX_BULK_DELETE_ROWS) {
            throw new IllegalArgumentException(
                    "Bulk delete is limited to " + MAX_BULK_DELETE_ROWS + " rows per file; "
                    + "split the file and retry (" + rowCount + " rows submitted)");
        }
    }

    private boolean withinScope(AuthPrincipal principal, UUID directoryId, String dn) {
        try {
            permissionService.requireDnWithinScope(principal, directoryId, dn);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }

    // ── Bulk group import / export ───────────────────────────────────────────

    public BulkImportPreviewResult previewBulkGroupImport(UUID directoryId, AuthPrincipal principal,
                                                           InputStream csvInput,
                                                           BulkImportRequest req,
                                                           String objectClass,
                                                           String memberAttribute) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        // Same rationale as previewBulkImport above — keep the preview
        // gated by the same scope check the actual import enforces.
        permissionService.requireDnWithinScope(principal, directoryId, req.parentDn());

        List<CsvColumnMappingDto> mappings = req.columnMappings() != null
                ? new ArrayList<>(req.columnMappings()) : new ArrayList<>();

        if (req.templateId() != null) {
            List<CsvMappingTemplateEntry> entries =
                    csvTemplateService.loadEntries(req.templateId());
            if (mappings.isEmpty()) {
                mappings = entries.stream()
                        .map(e -> new CsvColumnMappingDto(
                                e.getCsvColumnName(), e.getLdapAttribute(), e.isIgnored()))
                        .toList();
            }
        }

        boolean skipHeader = resolveSkipHeaderRow(req.skipHeaderRow(), req.templateId(), directoryId, principal);

        // Resolve the group object class' MUST attributes for per-row preview
        // validation. Same shape as user import: drop objectClass and the cn
        // RDN attribute (cn is special-cased by the import path's
        // missing-key error). 'memberAttribute' tells the preview that a
        // CSV row's 'members' cell satisfies the member/uniqueMember/
        // memberUid requirement, depending on the chosen object class.
        List<String> requiredAttrs = List.of();
        String oc = (objectClass != null && !objectClass.isBlank()) ? objectClass : "groupOfNames";
        var schema = schemaService.getAttributesForObjectClasses(dc, List.of(oc));
        requiredAttrs = schema.required().stream()
                .filter(a -> !a.equalsIgnoreCase("objectClass"))
                .filter(a -> !a.equalsIgnoreCase("cn"))
                .toList();

        return bulkGroupService.previewImport(
                csvInput, req.parentDn(), mappings, skipHeader, requiredAttrs, memberAttribute);
    }

    public BulkImportResult bulkImportGroups(UUID directoryId, AuthPrincipal principal,
                                              InputStream csvInput,
                                              BulkImportRequest req,
                                              String memberAttribute,
                                              String objectClass) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, req.parentDn());

        ConflictHandling  conflictHandling = ConflictHandling.SKIP;
        List<String>      objectClasses    = (objectClass != null && !objectClass.isBlank())
                ? List.of(objectClass.split(",")) : List.of("groupOfNames");
        List<CsvColumnMappingDto> mappings = req.columnMappings() != null
                ? new ArrayList<>(req.columnMappings()) : new ArrayList<>();

        if (req.templateId() != null) {
            CsvMappingTemplate template =
                    csvTemplateService.loadTemplate(req.templateId(), directoryId, principal);
            List<CsvMappingTemplateEntry> entries =
                    csvTemplateService.loadEntries(req.templateId());
            conflictHandling = template.getConflictHandling();
            if (template.getObjectClass() != null && !template.getObjectClass().isBlank()) {
                objectClasses = List.of(template.getObjectClass().split(","));
            }
            if (mappings.isEmpty()) {
                mappings = entries.stream()
                        .map(e -> new CsvColumnMappingDto(
                                e.getCsvColumnName(), e.getLdapAttribute(), e.isIgnored()))
                        .toList();
            }
        }

        if (req.conflictHandling() != null) conflictHandling = req.conflictHandling();

        boolean skipHeader = resolveSkipHeaderRow(req.skipHeaderRow(), req.templateId(), directoryId, principal);

        String effectiveMemberAttr = (memberAttribute != null && !memberAttribute.isBlank())
                ? memberAttribute : "member";

        BulkImportResult result = bulkGroupService.importCsv(
                dc, csvInput, req.parentDn(), conflictHandling, mappings,
                objectClasses, effectiveMemberAttr, skipHeader);

        auditService.record(principal, directoryId, AuditAction.GROUP_BULK_IMPORT, req.parentDn(),
                Map.of("operation", "bulkGroupImport",
                       "created",   result.created(),
                       "updated",   result.updated(),
                       "skipped",   result.skipped(),
                       "errors",    result.errors()));
        return result;
    }

    public byte[] bulkExportGroups(UUID directoryId, AuthPrincipal principal,
                                    String filter, String baseDn,
                                    String memberAttribute,
                                    List<String> attributes) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);

        // See bulkExportUsers for the fan-out rationale.
        List<String> bases = permissionService.resolveSearchBaseDns(principal, directoryId, baseDn);

        return bulkGroupService.exportCsvFromBases(dc, filter, bases, memberAttribute, attributes);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean resolveSkipHeaderRow(Boolean requestValue, UUID templateId,
                                          UUID directoryId, AuthPrincipal principal) {
        if (requestValue != null) return requestValue;
        if (templateId != null) {
            CsvMappingTemplate template =
                    csvTemplateService.loadTemplate(templateId, directoryId, principal);
            return template.isSkipHeaderRow();
        }
        return true; // default: first row is headers
    }

    private DirectoryConnection loadDirectory(UUID directoryId, AuthPrincipal principal) {
        DirectoryConnection dc = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
        if (!dc.isEnabled()) {
            throw new ResourceNotFoundException("DirectoryConnection", directoryId);
        }
        return dc;
    }

    /**
     * Collects the attribute values being set (ADD/REPLACE with non-empty
     * values) from an update request, keyed by attribute name. DELETE
     * operations are excluded — removing values has no value-constraint to
     * validate.
     */
    /** All attribute names targeted by an update, regardless of operation. */
    private static java.util.Set<String> modifiedAttributeNames(UpdateEntryRequest req) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (AttributeModification m : req.modifications()) {
            names.add(m.attribute());
        }
        return names;
    }

    private static Map<String, List<String>> modifiedAttributeValues(UpdateEntryRequest req) {
        Map<String, List<String>> map = new java.util.LinkedHashMap<>();
        for (AttributeModification m : req.modifications()) {
            if (m.operation() != AttributeModification.Operation.DELETE
                    && m.values() != null && !m.values().isEmpty()) {
                map.put(m.attribute(), m.values());
            }
        }
        return map;
    }

    private List<Modification> toModifications(UpdateEntryRequest req) {
        return req.modifications().stream()
                .map(m -> new Modification(
                        toModType(m.operation()),
                        m.attribute(),
                        m.values() == null ? new String[0]
                                : m.values().toArray(new String[0])))
                .toList();
    }

    private ModificationType toModType(AttributeModification.Operation op) {
        return switch (op) {
            case ADD     -> ModificationType.ADD;
            case REPLACE -> ModificationType.REPLACE;
            case DELETE  -> ModificationType.DELETE;
        };
    }
}
