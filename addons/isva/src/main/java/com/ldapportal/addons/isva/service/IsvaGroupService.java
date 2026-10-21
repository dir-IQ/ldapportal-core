// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.IsvaGroupRegistry;
import com.ldapportal.addons.isva.dto.IsvaGroupRegistrations;
import com.ldapportal.addons.isva.dto.IsvaGroupStatus;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.provisioning.AddStep;
import com.ldapportal.core.provisioning.DeleteStep;
import com.ldapportal.core.provisioning.PlanExecutor;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryObjectClassDefaults;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Importing an existing group into IVIA — what {@code pdadmin group import}
 * does — by writing the group's secGroup registration entry in the IVIA
 * group container (see {@link IsvaGroupRegistry}). The group itself is not
 * changed. Refusals surface as {@link IsvaAccountRefusalException}s with a
 * {@code code}, like the account verbs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IsvaGroupService {

    private final DirectoryConnectionRepository dirRepo;
    private final VendorIntegrationIsvaConfigRepository configRepo;
    private final IsvaGroupRegistry registry;
    private final PlanExecutor planExecutor;
    private final LdapConnectionFactory connectionFactory;
    private final AuditService auditService;

    /** Whether the group is imported, and what an import would be called. */
    public IsvaGroupStatus status(UUID directoryId, String groupDn) {
        Ctx ctx = loadContext(directoryId);
        String container = ctx.cfg().effectiveGroupContainerDn();
        if (container == null) {
            return new IsvaGroupStatus(false, null, false, null);
        }
        Optional<String> registration = registry.findRegistrationDn(ctx.dir(), container, groupDn);
        return registration
                .map(dn -> new IsvaGroupStatus(true, dn, true, null))
                .orElseGet(() -> new IsvaGroupStatus(false, null, true,
                        readGroup(ctx.dir(), groupDn).cn()));
    }

    /**
     * Which of {@code groupDns} are imported, in one search of the group
     * container. A directory without an active IVIA integration answers
     * {@code enabled=false} rather than refusing, so a list page can call it
     * unconditionally. {@code visible} drops DNs the caller may not see.
     */
    public IsvaGroupRegistrations registrations(UUID directoryId, List<String> groupDns,
                                                Predicate<String> visible) {
        DirectoryConnection dir = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Directory not found: " + directoryId));
        Optional<VendorIntegrationIsvaConfig> cfg = configRepo.findById(directoryId)
                .filter(VendorIntegrationIsvaConfig::isEnabled);
        if (cfg.isEmpty()) {
            return new IsvaGroupRegistrations(false, false, List.of());
        }
        String container = cfg.get().effectiveGroupContainerDn();
        if (container == null) {
            return new IsvaGroupRegistrations(true, false, List.of());
        }
        Map<String, String> registered = registry.registeredGroups(dir, container);
        List<String> out = new ArrayList<>();
        for (String dn : groupDns) {
            if (dn != null && registered.containsKey(IsvaGroupRegistry.normalize(dn)) && visible.test(dn)) {
                out.add(dn);
            }
        }
        return new IsvaGroupRegistrations(true, true, out);
    }

    /**
     * Imports the group: writes its secGroup registration entry named
     * {@code iviaName} (blank → the group's {@code cn}).
     */
    public IsvaGroupStatus importGroup(UUID directoryId, String groupDn, String iviaName,
                                       AuthPrincipal principal) {
        Ctx ctx = loadContext(directoryId);
        String container = ctx.cfg().effectiveGroupContainerDn();
        if (container == null) {
            throw refuse(HttpStatus.CONFLICT, "ivia_no_group_container",
                    "This directory's IVIA config has no management DIT, so there's nowhere to "
                            + "import groups to — set the management DIT (or a group container) first");
        }
        GroupEntry group = readGroup(ctx.dir(), groupDn);
        if (!group.isGroup()) {
            throw new IllegalArgumentException(groupDn + " is not a group, so it can't be imported into IVIA");
        }
        if (registry.findRegistrationDn(ctx.dir(), container, groupDn).isPresent()) {
            throw refuse(HttpStatus.CONFLICT, "ivia_group_already_imported",
                    "Group " + groupDn + " is already imported into IVIA");
        }
        String name = iviaName == null || iviaName.isBlank() ? group.cn() : iviaName.trim();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group " + groupDn + " has no cn; give the IVIA group a name");
        }
        String registrationDn = IsvaGroupRegistry.registrationDn(container, name);
        if (registry.exists(ctx.dir(), registrationDn)) {
            throw refuse(HttpStatus.CONFLICT, "ivia_group_name_taken",
                    "IVIA already has a group named \"" + name + "\" — choose another name");
        }

        AddStep step = IsvaGroupRegistry.importStep(ctx.cfg(), container, groupDn, name);
        try {
            planExecutor.execute(ctx.dir(), step);
        } catch (LdapOperationException ex) {
            if (ex.getCause() instanceof LDAPException ldap
                    && ldap.getResultCode() == ResultCode.ENTRY_ALREADY_EXISTS) {
                throw refuse(HttpStatus.CONFLICT, "ivia_group_name_taken",
                        "IVIA already has a group named \"" + name + "\" — choose another name");
            }
            throw ex;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "ivia");
        detail.put("ivia_op", "group_import");
        detail.put("iviaGroupName", name);
        detail.put("registrationDn", step.targetDn());
        auditService.record(principal, directoryId, AuditAction.GROUP_UPDATE, groupDn, detail);
        log.info("Imported group {} into IVIA as {} ({})", groupDn, name, step.targetDn());
        return new IsvaGroupStatus(true, step.targetDn(), true, null);
    }

    /**
     * Removes the group from IVIA: deletes its secGroup registration entry,
     * as {@code pdadmin group delete} without {@code -registry} does. The
     * group itself is not changed.
     */
    public IsvaGroupStatus removeGroup(UUID directoryId, String groupDn, AuthPrincipal principal) {
        Ctx ctx = loadContext(directoryId);
        String container = ctx.cfg().effectiveGroupContainerDn();
        if (container == null) {
            throw refuse(HttpStatus.CONFLICT, "ivia_no_group_container",
                    "This directory's IVIA config has no management DIT, so no group can be "
                            + "imported into IVIA from here");
        }
        String registrationDn = registry.findRegistrationDn(ctx.dir(), container, groupDn)
                .orElseThrow(() -> refuse(HttpStatus.CONFLICT, "ivia_group_not_imported",
                        "Group " + groupDn + " isn't imported into IVIA"));
        try {
            planExecutor.execute(ctx.dir(), DeleteStep.of(registrationDn));
        } catch (LdapOperationException ex) {
            // Already gone (removed meanwhile, e.g. with pdadmin): the state we wanted.
            if (!(ex.getCause() instanceof LDAPException ldap
                    && ldap.getResultCode() == ResultCode.NO_SUCH_OBJECT)) {
                throw ex;
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "ivia");
        detail.put("ivia_op", "group_remove");
        detail.put("registrationDn", registrationDn);
        auditService.record(principal, directoryId, AuditAction.GROUP_UPDATE, groupDn, detail);
        log.info("Removed group {} from IVIA ({})", groupDn, registrationDn);
        return new IsvaGroupStatus(false, null, true, readGroupCnOrNull(ctx.dir(), groupDn));
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** The group's cn for the next import's default name; null when it can't be read. */
    private String readGroupCnOrNull(DirectoryConnection dir, String groupDn) {
        try {
            return readGroup(dir, groupDn).cn();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record Ctx(DirectoryConnection dir, VendorIntegrationIsvaConfig cfg) {}

    private record GroupEntry(String cn, boolean isGroup) {}

    private Ctx loadContext(UUID directoryId) {
        DirectoryConnection dir = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Directory not found: " + directoryId));
        VendorIntegrationIsvaConfig cfg = configRepo.findById(directoryId)
                .filter(VendorIntegrationIsvaConfig::isEnabled)
                .orElseThrow(() -> refuse(HttpStatus.CONFLICT, "ivia_directory_disabled",
                        "Directory [" + dir.getDisplayName()
                                + "] has no active IVIA integration — enable IVIA on the directory first"));
        return new Ctx(dir, cfg);
    }

    /** The group's cn and whether it carries one of the directory's group object classes; 404 when missing. */
    private GroupEntry readGroup(DirectoryConnection dir, String groupDn) {
        SearchResultEntry entry = connectionFactory.withConnection(dir, conn -> {
            try {
                return conn.getEntry(groupDn, "cn", "objectClass");
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return null;
                }
                throw new LdapOperationException("Failed to read group " + groupDn, e);
            }
        });
        if (entry == null) {
            throw new ResourceNotFoundException("Group not found: " + groupDn);
        }
        List<String> groupClasses = DirectoryObjectClassDefaults.effectiveGroupObjectClasses(dir).stream()
                .map(c -> c.toLowerCase(Locale.ROOT)).toList();
        String[] classes = entry.getAttributeValues("objectClass");
        boolean isGroup = classes != null && java.util.Arrays.stream(classes)
                .anyMatch(c -> groupClasses.contains(c.toLowerCase(Locale.ROOT)));
        return new GroupEntry(entry.getAttributeValue("cn"), isGroup);
    }

    private static IsvaAccountRefusalException refuse(HttpStatus status, String code, String message) {
        return new IsvaAccountRefusalException(status, code, message);
    }
}
