// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.profile.*;
import com.ldapportal.dto.profile.CreateProfileRequest.AttributeConfigEntry;
import com.ldapportal.dto.profile.CreateProfileRequest.GroupAssignmentEntry;
import com.ldapportal.entity.*;
import com.ldapportal.entity.enums.ApproverMode;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ExpiryAction;
import com.ldapportal.entity.enums.InputType;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core CRUD and provisioning logic for provisioning profiles.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProvisioningProfileService {

    private final ProvisioningProfileRepository      profileRepo;
    private final ProfileAttributeConfigRepository   attrConfigRepo;
    private final ProfileGroupAssignmentRepository   groupAssignmentRepo;
    private final ProfileLifecyclePolicyRepository   lifecycleRepo;
    private final ProfileApprovalConfigRepository    approvalConfigRepo;
    private final ProfileApproverRepository          approverRepo;
    private final DirectoryConnectionRepository      dirRepo;
    private final AccountRepository                  accountRepo;
    private final AdminProfileRoleRepository         adminProfileRoleRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.ldapportal.core.entitlement.UsageLimitService usageLimitService;
    private final PasswordGeneratorService passwordGenerator;
    private final LdapUserService ldapUserService;
    private final LdapGroupService ldapGroupService;
    private final AuditService auditService;

    // ── Profile CRUD ──────────────────────────────────────────────────────────

    /**
     * Lists profiles in the given directory, filtered by what {@code principal}
     * is authorized to see. Superadmins get every profile; regular admins get
     * only the ones they have an {@link AdminProfileRole} on. Passing a
     * {@code null} principal bypasses filtering (used by internal callers that
     * have already performed their own authorization check).
     *
     * <p>Filtering here — not just in the UI — is what keeps the profile
     * picker on the Users and Groups pages honest: an admin who crafts the
     * request by hand still can't enumerate profiles they have no role on.</p>
     */
    @Transactional(readOnly = true)
    public List<ProfileResponse> list(UUID directoryId, AuthPrincipal principal) {
        requireDirectory(directoryId);
        List<ProvisioningProfile> all = profileRepo.findAllByDirectoryIdOrderByNameAsc(directoryId);
        if (principal == null || principal.isSuperadmin()) {
            return all.stream().map(this::toResponse).toList();
        }
        Set<UUID> authorizedProfileIds = adminProfileRoleRepo
                .findAllByAdminAccountIdAndProfileDirectoryId(principal.id(), directoryId)
                .stream()
                .map(r -> r.getProfile() != null ? r.getProfile().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return all.stream()
                .filter(p -> authorizedProfileIds.contains(p.getId()))
                .map(this::toResponse)
                .toList();
    }

    /** Backwards-compatible overload — unfiltered list (superadmin view). */
    @Transactional(readOnly = true)
    public List<ProfileResponse> list(UUID directoryId) {
        return list(directoryId, null);
    }

    @Transactional(readOnly = true)
    public List<ProfileResponse> listAll() {
        return profileRepo.findAllByOrderByDirectoryIdAscNameAsc()
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID profileId) {
        return toResponse(requireProfile(profileId));
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID directoryId, UUID profileId) {
        return toResponse(requireProfileInDirectory(directoryId, profileId));
    }

    @Transactional
    public ProfileResponse create(UUID directoryId, CreateProfileRequest req, AuthPrincipal principal) {
        DirectoryConnection dir = requireDirectory(directoryId);

        // License cap is per-directory — customers on tighter tiers get a
        // small number of profiles per directory rather than a global
        // total. PROFILES_PER_DIRECTORY maps directly to that.
        usageLimitService.requireWithinLimit(
                com.ldapportal.core.entitlement.LimitType.PROFILES_PER_DIRECTORY,
                profileRepo.countByDirectoryId(directoryId));

        if (profileRepo.existsByDirectoryIdAndName(directoryId, req.name())) {
            throw new ConflictException(
                    "Profile [" + req.name() + "] already exists in this directory");
        }

        ProvisioningProfile profile = new ProvisioningProfile();
        profile.setDirectory(dir);
        applyCommonFields(profile, req.name(), req.description(), req.targetOuDn(),
                req.objectClassNames(), req.rdnAttribute(), req.showDnField(),
                req.enabled(), req.selfRegistrationAllowed(),
                req.passwordLength(), req.passwordUppercase(), req.passwordLowercase(),
                req.passwordDigits(), req.passwordSpecial(), req.passwordSpecialChars(),
                req.emailPasswordToUser());
        profile.setAutoIncludeGroups(req.autoIncludeGroups());
        // Auto-include profiles should not also exclude auto-includes (nonsensical)
        profile.setExcludeAutoIncludes(req.autoIncludeGroups() ? false : req.excludeAutoIncludes());
        profile = profileRepo.save(profile);

        saveAdditionalProfiles(profile, req.additionalProfileIds());
        saveAttributeConfigs(profile, req.attributeConfigs());
        saveGroupAssignments(profile, req.groupAssignments());

        if (principal != null) {
            auditService.record(principal, directoryId, AuditAction.PROFILE_CREATE, null,
                    Map.of("profileId", profile.getId(), "name", profile.getName(),
                            "targetOuDn", profile.getTargetOuDn()));
        }

        return toResponse(profile);
    }

    /** Backwards-compatible overload — preserves the no-principal call sites. */
    @Transactional
    public ProfileResponse create(UUID directoryId, CreateProfileRequest req) {
        return create(directoryId, req, null);
    }

    @Transactional
    public ProfileResponse update(UUID directoryId, UUID profileId, UpdateProfileRequest req) {
        return update(directoryId, profileId, req, null);
    }

    @Transactional
    public ProfileResponse update(UUID directoryId, UUID profileId, UpdateProfileRequest req,
                                   AuthPrincipal principal) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);

        // Check name uniqueness if changed
        if (!profile.getName().equals(req.name()) &&
                profileRepo.existsByDirectoryIdAndName(profile.getDirectory().getId(), req.name())) {
            throw new ConflictException(
                    "Profile [" + req.name() + "] already exists in this directory");
        }

        applyCommonFields(profile, req.name(), req.description(), req.targetOuDn(),
                req.objectClassNames(), req.rdnAttribute(), req.showDnField(),
                req.enabled(), req.selfRegistrationAllowed(),
                req.passwordLength(), req.passwordUppercase(), req.passwordLowercase(),
                req.passwordDigits(), req.passwordSpecial(), req.passwordSpecialChars(),
                req.emailPasswordToUser());
        profile.setAutoIncludeGroups(req.autoIncludeGroups());
        // Auto-include profiles should not also exclude auto-includes (nonsensical)
        profile.setExcludeAutoIncludes(req.autoIncludeGroups() ? false : req.excludeAutoIncludes());
        profile = profileRepo.save(profile);

        // Replace additional profiles
        saveAdditionalProfiles(profile, req.additionalProfileIds());

        // Replace attribute configs
        attrConfigRepo.deleteAllByProfileId(profileId);
        attrConfigRepo.flush();
        saveAttributeConfigs(profile, req.attributeConfigs());

        // Replace group assignments
        groupAssignmentRepo.deleteAllByProfileId(profileId);
        groupAssignmentRepo.flush();
        saveGroupAssignments(profile, req.groupAssignments());

        if (principal != null) {
            auditService.record(principal, directoryId, AuditAction.PROFILE_UPDATE, null,
                    Map.of("profileId", profile.getId(), "name", profile.getName()));
        }

        return toResponse(profile);
    }

    @Transactional
    public void delete(UUID directoryId, UUID profileId) {
        delete(directoryId, profileId, null);
    }

    @Transactional
    public void delete(UUID directoryId, UUID profileId, AuthPrincipal principal) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);
        String name = profile.getName();
        profileRepo.delete(profile);
        if (principal != null) {
            auditService.record(principal, directoryId, AuditAction.PROFILE_DELETE, null,
                    Map.of("profileId", profileId, "name", name));
        }
    }

    @Transactional
    public ProfileResponse clone(UUID directoryId, UUID profileId, String newName) {
        return clone(directoryId, profileId, newName, null);
    }

    @Transactional
    public ProfileResponse clone(UUID directoryId, UUID profileId, String newName,
                                  AuthPrincipal principal) {
        ProvisioningProfile source = requireProfileInDirectory(directoryId, profileId);

        if (profileRepo.existsByDirectoryIdAndName(source.getDirectory().getId(), newName)) {
            throw new ConflictException(
                    "Profile [" + newName + "] already exists in this directory");
        }

        ProvisioningProfile copy = new ProvisioningProfile();
        copy.setDirectory(source.getDirectory());
        copy.setName(newName);
        copy.setDescription(source.getDescription());
        copy.setTargetOuDn(source.getTargetOuDn());
        copy.setObjectClassNames(new ArrayList<>(source.getObjectClassNames()));
        copy.setRdnAttribute(source.getRdnAttribute());
        copy.setShowDnField(source.isShowDnField());
        copy.setEnabled(false); // clones start disabled
        copy.setSelfRegistrationAllowed(false);
        copy.setPasswordLength(source.getPasswordLength());
        copy.setPasswordUppercase(source.isPasswordUppercase());
        copy.setPasswordLowercase(source.isPasswordLowercase());
        copy.setPasswordDigits(source.isPasswordDigits());
        copy.setPasswordSpecial(source.isPasswordSpecial());
        copy.setPasswordSpecialChars(source.getPasswordSpecialChars());
        copy.setEmailPasswordToUser(source.isEmailPasswordToUser());
        copy.setAutoIncludeGroups(false); // clones don't auto-include
        copy.setExcludeAutoIncludes(source.isExcludeAutoIncludes());
        copy = profileRepo.save(copy);

        // Clone additional profiles
        copy.setAdditionalProfiles(new HashSet<>(source.getAdditionalProfiles()));
        copy = profileRepo.save(copy);

        // Clone attribute configs
        List<ProfileAttributeConfig> sourceConfigs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        for (ProfileAttributeConfig sc : sourceConfigs) {
            ProfileAttributeConfig cc = new ProfileAttributeConfig();
            cc.setProfile(copy);
            cc.setAttributeName(sc.getAttributeName());
            cc.setCustomLabel(sc.getCustomLabel());
            cc.setInputType(sc.getInputType());
            cc.setRequiredOnCreate(sc.isRequiredOnCreate());
            cc.setEditableOnCreate(sc.isEditableOnCreate());
            cc.setEditableOnUpdate(sc.isEditableOnUpdate());
            cc.setSelfServiceEdit(sc.isSelfServiceEdit());
            cc.setDefaultValue(sc.getDefaultValue());
            cc.setComputedExpression(sc.getComputedExpression());
            cc.setValidationRegex(sc.getValidationRegex());
            cc.setValidationMessage(sc.getValidationMessage());
            cc.setAllowedValues(sc.getAllowedValues());
            cc.setMinLength(sc.getMinLength());
            cc.setMaxLength(sc.getMaxLength());
            cc.setSectionName(sc.getSectionName());
            cc.setColumnSpan(sc.getColumnSpan());
            cc.setDisplayOrder(sc.getDisplayOrder());
            cc.setHidden(sc.isHidden());
            attrConfigRepo.save(cc);
        }

        // Clone group assignments
        List<ProfileGroupAssignment> sourceGroups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        for (ProfileGroupAssignment sg : sourceGroups) {
            ProfileGroupAssignment cg = new ProfileGroupAssignment();
            cg.setProfile(copy);
            cg.setGroupDn(sg.getGroupDn());
            cg.setMemberAttribute(sg.getMemberAttribute());
            cg.setDisplayOrder(sg.getDisplayOrder());
            groupAssignmentRepo.save(cg);
        }

        if (principal != null) {
            auditService.record(principal, directoryId, AuditAction.PROFILE_CLONE, null,
                    Map.of("profileId", copy.getId(), "name", copy.getName(),
                            "sourceProfileId", source.getId(), "sourceName", source.getName()));
        }

        return toResponse(copy);
    }

    // ── Lifecycle Policy ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LifecyclePolicyResponse getLifecyclePolicy(UUID profileId) {
        requireProfile(profileId);
        ProfileLifecyclePolicy policy = lifecycleRepo.findByProfileId(profileId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No lifecycle policy for profile " + profileId));
        return LifecyclePolicyResponse.from(policy);
    }

    @Transactional
    public LifecyclePolicyResponse setLifecyclePolicy(UUID profileId, LifecyclePolicyRequest req) {
        ProvisioningProfile profile = requireProfile(profileId);
        ProfileLifecyclePolicy policy = lifecycleRepo.findByProfileId(profileId)
                .orElseGet(() -> {
                    ProfileLifecyclePolicy p = new ProfileLifecyclePolicy();
                    p.setProfile(profile);
                    return p;
                });

        policy.setExpiresAfterDays(req.expiresAfterDays());
        policy.setMaxRenewals(req.maxRenewals());
        policy.setRenewalDays(req.renewalDays());
        policy.setOnExpiryAction(req.onExpiryAction() != null ? req.onExpiryAction() : ExpiryAction.DISABLE);
        policy.setOnExpiryMoveDn(req.onExpiryMoveDn());
        policy.setOnExpiryRemoveGroups(req.onExpiryRemoveGroups());
        policy.setOnExpiryNotify(req.onExpiryNotify());
        policy.setWarningDaysBefore(req.warningDaysBefore());

        return LifecyclePolicyResponse.from(lifecycleRepo.save(policy));
    }

    @Transactional
    public void deleteLifecyclePolicy(UUID profileId) {
        requireProfile(profileId);
        lifecycleRepo.deleteByProfileId(profileId);
    }

    // ── Approval Config ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ApprovalConfigResponse getApprovalConfig(UUID profileId) {
        requireProfile(profileId);
        ProfileApprovalConfig config = approvalConfigRepo.findByProfileId(profileId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No approval config for profile " + profileId));
        return ApprovalConfigResponse.from(config);
    }

    @Transactional
    public ApprovalConfigResponse setApprovalConfig(UUID profileId, ApprovalConfigRequest req) {
        ProvisioningProfile profile = requireProfile(profileId);
        ProfileApprovalConfig config = approvalConfigRepo.findByProfileId(profileId)
                .orElseGet(() -> {
                    ProfileApprovalConfig c = new ProfileApprovalConfig();
                    c.setProfile(profile);
                    return c;
                });

        config.setRequireApproval(req.requireApproval());
        config.setApproverMode(req.approverMode() != null ? req.approverMode() : ApproverMode.DATABASE);
        config.setApproverGroupDn(req.approverGroupDn());
        config.setAutoEscalateDays(req.autoEscalateDays());
        if (req.escalationAccountId() != null) {
            config.setEscalationAccount(accountRepo.findById(req.escalationAccountId())
                    .orElseThrow(() -> new ResourceNotFoundException("Account", req.escalationAccountId())));
        } else {
            config.setEscalationAccount(null);
        }

        return ApprovalConfigResponse.from(approvalConfigRepo.save(config));
    }

    // ── Approvers ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProfileApproverResponse> getApprovers(UUID profileId) {
        requireProfile(profileId);
        return approverRepo.findAllByProfileIdWithAccount(profileId).stream()
                .map(pa -> new ProfileApproverResponse(
                        pa.getAdminAccount().getId(),
                        pa.getAdminAccount().getUsername(),
                        pa.getAdminAccount().getEmail()))
                .toList();
    }

    @Transactional
    public List<ProfileApproverResponse> setApprovers(UUID profileId, List<UUID> accountIds) {
        ProvisioningProfile profile = requireProfile(profileId);
        approverRepo.deleteAllByProfileId(profileId);
        approverRepo.flush();

        for (UUID accountId : accountIds) {
            Account account = accountRepo.findById(accountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
            ProfileApprover pa = new ProfileApprover();
            pa.setProfile(profile);
            pa.setAdminAccount(account);
            approverRepo.save(pa);
        }

        return approverRepo.findAllByProfileIdWithAccount(profileId).stream()
                .map(pa -> new ProfileApproverResponse(
                        pa.getAdminAccount().getId(),
                        pa.getAdminAccount().getUsername(),
                        pa.getAdminAccount().getEmail()))
                .toList();
    }

    // ── Profile resolution ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<ProvisioningProfile> resolveProfileForDn(UUID directoryId, String dn) {
        List<ProvisioningProfile> profiles =
                profileRepo.findAllByDirectoryIdAndEnabledTrue(directoryId);
        String dnLower = dn.toLowerCase();

        // Find profiles whose target OU is a suffix of the DN; most specific match wins
        return profiles.stream()
                .filter(p -> dnLower.endsWith(p.getTargetOuDn().toLowerCase()))
                .max(Comparator.comparingInt(p -> p.getTargetOuDn().length()));
    }

    @Transactional(readOnly = true)
    public boolean isApprovalRequired(UUID profileId) {
        return approvalConfigRepo.findByProfileId(profileId)
                .map(ProfileApprovalConfig::isRequireApproval)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isApprover(UUID profileId, UUID accountId) {
        return approverRepo.existsByProfileIdAndAdminAccountId(profileId, accountId);
    }

    // ── Provisioning helpers ──────────────────────────────────────────────────

    /**
     * Evaluates computed expressions by tokenizing into variable references
     * ({@code ${attr}}), quoted string literals, concatenation operators (+),
     * and literal text.  No regex used for the concatenation handling.
     */
    public String evaluateExpression(String expression, Map<String, List<String>> attributes) {
        if (expression == null || expression.isBlank()) return null;

        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = expression.length();
        while (i < len) {
            char c = expression.charAt(i);
            if (c == '$' && i + 1 < len && expression.charAt(i + 1) == '{') {
                // Variable reference: ${attrName}
                int end = expression.indexOf('}', i + 2);
                if (end == -1) break;
                String attrName = expression.substring(i + 2, end);
                List<String> values = attributes.get(attrName);
                result.append((values != null && !values.isEmpty()) ? values.get(0) : "");
                i = end + 1;
            } else if (c == '+') {
                // Concatenation operator — skip it
                i++;
            } else if (c == '"' || c == '\'') {
                // Quoted string literal
                int end = expression.indexOf(c, i + 1);
                if (end == -1) break;
                result.append(expression, i + 1, end);
                i = end + 1;
            } else {
                // Literal text (dots, @domain, etc.)
                int j = i;
                while (j < len) {
                    char ch = expression.charAt(j);
                    if (ch == '$' || ch == '+' || ch == '"' || ch == '\'') break;
                    j++;
                }
                result.append(expression, i, j);
                i = j;
            }
        }
        return result.toString();
    }

    /**
     * Validates attribute values against profile attribute configs.
     */
    @Transactional(readOnly = true)
    public void validateAttributes(UUID profileId, Map<String, List<String>> attributes) {
        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);

        for (ProfileAttributeConfig config : configs) {
            List<String> values = attributes.get(config.getAttributeName());
            String value = (values != null && !values.isEmpty()) ? values.get(0) : null;

            // Required check
            if (config.isRequiredOnCreate() && (value == null || value.isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Attribute [" + config.getAttributeName() + "] is required");
            }

            if (value == null || value.isBlank()) continue;

            // Length checks
            if (config.getMinLength() != null && value.length() < config.getMinLength()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Attribute [" + config.getAttributeName() + "] must be at least "
                                + config.getMinLength() + " characters");
            }
            if (config.getMaxLength() != null && value.length() > config.getMaxLength()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Attribute [" + config.getAttributeName() + "] must be at most "
                                + config.getMaxLength() + " characters");
            }

            // Regex check
            if (config.getValidationRegex() != null && !config.getValidationRegex().isBlank()) {
                if (!value.matches(config.getValidationRegex())) {
                    String msg = config.getValidationMessage() != null
                            ? config.getValidationMessage()
                            : "Attribute [" + config.getAttributeName() + "] does not match the required format";
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
                }
            }

            // Allowed values check
            if (config.getAllowedValues() != null && !config.getAllowedValues().isBlank()) {
                try {
                    List<String> allowed = objectMapper.readValue(config.getAllowedValues(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    if (!allowed.contains(value)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Attribute [" + config.getAttributeName()
                                        + "] value is not in the allowed values list");
                    }
                } catch (ResponseStatusException rse) {
                    throw rse;
                } catch (Exception e) {
                    log.warn("Failed to parse allowed values JSON for attribute [{}]: {}",
                            config.getAttributeName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Applies defaults, computed expressions, and fixed values to the attribute map.
     */
    @Transactional(readOnly = true)
    public void applyDefaults(UUID profileId, Map<String, List<String>> attributes) {
        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);

        for (ProfileAttributeConfig config : configs) {
            List<String> values = attributes.get(config.getAttributeName());
            boolean hasValue = values != null && !values.isEmpty()
                    && values.get(0) != null && !values.get(0).isBlank();

            // Apply HIDDEN_FIXED values always
            if (config.getInputType() == InputType.HIDDEN_FIXED && config.getDefaultValue() != null) {
                attributes.put(config.getAttributeName(), List.of(config.getDefaultValue()));
                continue;
            }

            // Apply computed expressions (always re-evaluate to ensure correctness,
            // since computed values are read-only on the frontend)
            if (config.getComputedExpression() != null
                    && !config.getComputedExpression().isBlank()) {
                String computed = evaluateExpression(config.getComputedExpression(), attributes);
                if (computed != null && !computed.isBlank()) {
                    attributes.put(config.getAttributeName(), List.of(computed));
                    continue;
                }
            }

            // Apply static default
            if (!hasValue && config.getDefaultValue() != null && !config.getDefaultValue().isBlank()) {
                attributes.put(config.getAttributeName(), List.of(config.getDefaultValue()));
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String generatePassword(UUID profileId) {
        ProvisioningProfile profile = requireProfile(profileId);
        return passwordGenerator.generate(profile);
    }

    @Transactional(readOnly = true)
    public ProvisioningProfile getEntity(UUID profileId) {
        return requireProfile(profileId);
    }

    private ProfileResponse toResponse(ProvisioningProfile profile) {
        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profile.getId());
        List<ProfileGroupAssignment> groups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profile.getId());

        // Additional profiles
        List<ProfileResponse.AdditionalProfileEntry> additionalEntries = profile.getAdditionalProfiles()
                .stream()
                .map(p -> new ProfileResponse.AdditionalProfileEntry(p.getId(), p.getName()))
                .sorted(Comparator.comparing(ProfileResponse.AdditionalProfileEntry::name))
                .toList();

        // Effective group set: own + additional + auto-include (unless excluded)
        List<ProfileResponse.GroupAssignmentEntry> effectiveGroups = computeEffectiveGroups(profile, groups);

        return ProfileResponse.from(profile, configs, groups, additionalEntries, effectiveGroups);
    }

    private ProvisioningProfile requireProfile(UUID profileId) {
        return profileRepo.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", profileId));
    }

    private ProvisioningProfile requireProfileInDirectory(UUID directoryId, UUID profileId) {
        return profileRepo.findByIdAndDirectoryId(profileId, directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", profileId));
    }

    private DirectoryConnection requireDirectory(UUID directoryId) {
        return dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
    }

    private void applyCommonFields(ProvisioningProfile profile, String name, String description,
                                    String targetOuDn, List<String> objectClassNames,
                                    String rdnAttribute, boolean showDnField,
                                    boolean enabled, boolean selfRegistrationAllowed,
                                    Integer passwordLength, Boolean passwordUppercase,
                                    Boolean passwordLowercase, Boolean passwordDigits,
                                    Boolean passwordSpecial, String passwordSpecialChars,
                                    Boolean emailPasswordToUser) {
        profile.setName(name);
        profile.setDescription(description);
        profile.setTargetOuDn(targetOuDn);
        profile.setObjectClassNames(new ArrayList<>(objectClassNames));
        profile.setRdnAttribute(rdnAttribute);
        profile.setShowDnField(showDnField);
        profile.setEnabled(enabled);
        profile.setSelfRegistrationAllowed(selfRegistrationAllowed);
        if (passwordLength != null)       profile.setPasswordLength(passwordLength);
        if (passwordUppercase != null)    profile.setPasswordUppercase(passwordUppercase);
        if (passwordLowercase != null)    profile.setPasswordLowercase(passwordLowercase);
        if (passwordDigits != null)       profile.setPasswordDigits(passwordDigits);
        if (passwordSpecial != null)      profile.setPasswordSpecial(passwordSpecial);
        if (passwordSpecialChars != null) profile.setPasswordSpecialChars(passwordSpecialChars);
        if (emailPasswordToUser != null)  profile.setEmailPasswordToUser(emailPasswordToUser);
    }

    private void saveAttributeConfigs(ProvisioningProfile profile,
                                       List<AttributeConfigEntry> entries) {
        if (entries == null || entries.isEmpty()) return;

        // Validate: required attributes cannot be hidden (unless they have a computed expression)
        for (AttributeConfigEntry e : entries) {
            if (e.requiredOnCreate() && e.hidden()
                    && (e.computedExpression() == null || e.computedExpression().isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Required attribute '" + e.attributeName() + "' cannot be hidden unless it has a computed expression");
            }
        }

        // Validate: emailPasswordToUser requires a 'mail' attribute marked as required
        if (profile.isEmailPasswordToUser()) {
            boolean hasMailRequired = entries.stream().anyMatch(
                    e -> e.attributeName().equalsIgnoreCase("mail") && e.requiredOnCreate());
            if (!hasMailRequired) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Email password to user requires a 'mail' attribute marked as required");
            }
        }

        for (int i = 0; i < entries.size(); i++) {
            AttributeConfigEntry e = entries.get(i);
            ProfileAttributeConfig c = new ProfileAttributeConfig();
            c.setProfile(profile);
            c.setAttributeName(e.attributeName());
            c.setCustomLabel(e.customLabel());
            c.setInputType(InputType.valueOf(e.inputType()));
            c.setRequiredOnCreate(e.requiredOnCreate());
            c.setEditableOnCreate(e.editableOnCreate());
            c.setEditableOnUpdate(e.editableOnUpdate());
            c.setSelfServiceEdit(e.selfServiceEdit());
            c.setSelfRegistrationEdit(e.selfRegistrationEdit());
            c.setDefaultValue(e.defaultValue());
            c.setComputedExpression(e.computedExpression());
            c.setValidationRegex(e.validationRegex());
            c.setValidationMessage(e.validationMessage());
            c.setAllowedValues(e.allowedValues());
            c.setMinLength(e.minLength());
            c.setMaxLength(e.maxLength());
            c.setSectionName(e.sectionName());
            c.setColumnSpan(e.columnSpan() != null ? e.columnSpan() : 6);
            c.setDisplayOrder(i);
            c.setHidden(e.hidden());
            c.setRegistrationSectionName(e.registrationSectionName());
            c.setRegistrationColumnSpan(e.registrationColumnSpan());
            c.setRegistrationDisplayOrder(e.registrationColumnSpan() != null ? i : null);
            c.setSelfServiceSectionName(e.selfServiceSectionName());
            c.setSelfServiceColumnSpan(e.selfServiceColumnSpan());
            c.setSelfServiceDisplayOrder(e.selfServiceColumnSpan() != null ? i : null);
            attrConfigRepo.save(c);
        }
    }

    private void saveGroupAssignments(ProvisioningProfile profile,
                                       List<GroupAssignmentEntry> entries) {
        if (entries == null || entries.isEmpty()) return;

        for (int i = 0; i < entries.size(); i++) {
            GroupAssignmentEntry e = entries.get(i);
            ProfileGroupAssignment g = new ProfileGroupAssignment();
            g.setProfile(profile);
            g.setGroupDn(e.groupDn());
            g.setMemberAttribute(e.memberAttribute() != null ? e.memberAttribute() : "member");
            g.setDisplayOrder(i);
            groupAssignmentRepo.save(g);
        }
    }

    private void saveAdditionalProfiles(ProvisioningProfile profile, List<UUID> additionalProfileIds) {
        Set<ProvisioningProfile> additionals = new HashSet<>();
        // Auto-include profiles must not have additional profiles to prevent cascading group membership
        if (additionalProfileIds != null && !profile.isAutoIncludeGroups()) {
            for (UUID apId : additionalProfileIds) {
                if (apId.equals(profile.getId())) continue; // skip self-reference
                ProvisioningProfile ap = profileRepo.findById(apId)
                        .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", apId));
                additionals.add(ap);
            }
        }
        profile.setAdditionalProfiles(additionals);
        profileRepo.save(profile);
    }

    // ── Effective group computation ──────────────────────────────────────────

    /**
     * Computes the effective group set for a profile: own groups + additional
     * profiles' groups + auto-include profiles' groups (unless excluded).
     */
    private List<ProfileResponse.GroupAssignmentEntry> computeEffectiveGroups(
            ProvisioningProfile profile,
            List<ProfileGroupAssignment> ownGroups) {

        // Use groupDn as dedup key, preserving first occurrence
        Map<String, ProfileResponse.GroupAssignmentEntry> seen = new LinkedHashMap<>();

        // 1. Own groups
        for (ProfileGroupAssignment g : ownGroups) {
            seen.putIfAbsent(g.getGroupDn(),
                    ProfileResponse.GroupAssignmentEntry.from(g));
        }

        // 2. Explicit additional profiles
        for (ProvisioningProfile ap : profile.getAdditionalProfiles()) {
            for (ProfileGroupAssignment g :
                    groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(ap.getId())) {
                seen.putIfAbsent(g.getGroupDn(),
                        ProfileResponse.GroupAssignmentEntry.from(g));
            }
        }

        // 3. Auto-include profiles (unless this profile opts out)
        if (!profile.isExcludeAutoIncludes()) {
            List<ProvisioningProfile> autoIncludes =
                    profileRepo.findAllByDirectoryIdAndAutoIncludeGroupsTrue(
                            profile.getDirectory().getId());
            for (ProvisioningProfile ai : autoIncludes) {
                if (ai.getId().equals(profile.getId())) continue; // skip self
                for (ProfileGroupAssignment g :
                        groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(ai.getId())) {
                    seen.putIfAbsent(g.getGroupDn(),
                            ProfileResponse.GroupAssignmentEntry.from(g));
                }
            }
        }

        return List.copyOf(seen.values());
    }

    // ── Group change evaluation and application ──────────────────────────────

    /**
     * Evaluates group membership changes for all users provisioned under this
     * profile by comparing their current group memberships against the
     * effective group set.
     */
    @Transactional(readOnly = true)
    public GroupChangePreview evaluateGroupChanges(UUID directoryId, UUID profileId) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);
        DirectoryConnection dc = requireDirectory(directoryId);

        List<ProfileGroupAssignment> ownGroups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        List<ProfileResponse.GroupAssignmentEntry> effective =
                computeEffectiveGroups(profile, ownGroups);

        // Build a map of groupDn -> memberAttribute for the effective set
        Map<String, String> effectiveMap = new LinkedHashMap<>();
        for (ProfileResponse.GroupAssignmentEntry g : effective) {
            effectiveMap.put(g.groupDn(), g.memberAttribute());
        }

        // Search LDAP for users under the profile's target OU
        List<LdapUser> users = ldapUserService.searchUsers(
                dc, "(objectClass=*)", profile.getTargetOuDn(), "dn");

        List<GroupChangePreview.UserGroupChange> changes = new ArrayList<>();

        for (LdapUser user : users) {
            String userDn = user.getDn();
            List<String> currentMemberships = user.getMemberOf();
            Set<String> currentSet = currentMemberships.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());

            List<GroupChangePreview.GroupChange> toAdd = new ArrayList<>();
            List<GroupChangePreview.GroupChange> toRemove = new ArrayList<>();

            // Groups to add: in effective set but user is not a member
            for (var entry : effectiveMap.entrySet()) {
                if (!currentSet.contains(entry.getKey().toLowerCase())) {
                    toAdd.add(new GroupChangePreview.GroupChange(
                            entry.getKey(), entry.getValue()));
                }
            }

            // We only add groups, never remove — removing would require tracking
            // which groups were originally assigned by profiles vs manually added.
            // Future enhancement: track profile-managed group memberships.

            if (!toAdd.isEmpty()) {
                changes.add(new GroupChangePreview.UserGroupChange(
                        userDn, toAdd, toRemove));
            }
        }

        return new GroupChangePreview(changes, changes.size());
    }

    /**
     * Applies the group membership changes previewed by
     * {@link #evaluateGroupChanges(UUID, UUID)}.
     */
    @Transactional
    public GroupChangePreview applyGroupChanges(UUID directoryId, UUID profileId,
                                                 AuthPrincipal principal) {
        GroupChangePreview preview = evaluateGroupChanges(directoryId, profileId);
        DirectoryConnection dc = requireDirectory(directoryId);

        for (GroupChangePreview.UserGroupChange change : preview.changes()) {
            for (GroupChangePreview.GroupChange add : change.groupsToAdd()) {
                try {
                    ldapGroupService.addMember(dc, add.groupDn(),
                            add.memberAttribute(), change.userDn());
                    auditService.record(principal, directoryId,
                            AuditAction.GROUP_MEMBER_ADD, add.groupDn(),
                            Map.of("attribute", add.memberAttribute(),
                                    "member", change.userDn(),
                                    "source", "additional_profiles"));
                } catch (Exception e) {
                    log.warn("Failed to add {} to group {}: {}",
                            change.userDn(), add.groupDn(), e.getMessage());
                }
            }
            for (GroupChangePreview.GroupChange remove : change.groupsToRemove()) {
                try {
                    ldapGroupService.removeMember(dc, remove.groupDn(),
                            remove.memberAttribute(), change.userDn());
                    auditService.record(principal, directoryId,
                            AuditAction.GROUP_MEMBER_REMOVE, remove.groupDn(),
                            Map.of("attribute", remove.memberAttribute(),
                                    "member", change.userDn(),
                                    "source", "additional_profiles"));
                } catch (Exception e) {
                    log.warn("Failed to remove {} from group {}: {}",
                            change.userDn(), remove.groupDn(), e.getMessage());
                }
            }
        }

        return preview;
    }

    /**
     * Applies only the selected group membership changes.
     */
    @Transactional
    public int applySelectiveGroupChanges(UUID directoryId,
                                           SelectiveGroupChangeRequest request,
                                           AuthPrincipal principal) {
        DirectoryConnection dc = requireDirectory(directoryId);
        int applied = 0;

        for (SelectiveGroupChangeRequest.GroupMembershipEntry entry : request.entries()) {
            try {
                ldapGroupService.addMember(dc, entry.groupDn(),
                        entry.memberAttribute(), entry.userDn());
                auditService.record(principal, directoryId,
                        AuditAction.GROUP_MEMBER_ADD, entry.groupDn(),
                        Map.of("attribute", entry.memberAttribute(),
                                "member", entry.userDn(),
                                "source", "compliance_check"));
                applied++;
            } catch (Exception e) {
                log.warn("Failed to add {} to group {}: {}",
                        entry.userDn(), entry.groupDn(), e.getMessage());
            }
        }
        return applied;
    }

    /**
     * Removes {@code userDn} from every group in the profile's effective
     * group set (own groups + additional profiles' groups + auto-include
     * profiles' groups, unless excluded). Called by user-delete paths so
     * that group memberships granted by this profile are cleaned up when
     * the user entry leaves LDAP.
     *
     * <p>Failures on individual group removes are logged at {@code DEBUG}
     * (not {@code WARN}): many entries won't be members of every group in
     * the effective set, and most LDAP servers raise
     * {@code NO_SUCH_ATTRIBUTE} / {@code NO_SUCH_OBJECT} in that case. Each
     * successful remove emits a {@code GROUP_MEMBER_REMOVE} audit row with
     * {@code source = "profile_delete"} so reports can distinguish
     * provisioning-driven removals from operator-initiated ones.</p>
     */
    @Transactional
    public int removeUserFromProfileGroups(UUID directoryId, UUID profileId,
                                            String userDn, AuthPrincipal principal) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);
        DirectoryConnection dc = requireDirectory(directoryId);

        List<ProfileGroupAssignment> ownGroups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        List<ProfileResponse.GroupAssignmentEntry> effective =
                computeEffectiveGroups(profile, ownGroups);

        int removed = 0;
        for (ProfileResponse.GroupAssignmentEntry g : effective) {
            try {
                ldapGroupService.removeMember(dc, g.groupDn(), g.memberAttribute(), userDn);
                auditService.record(principal, directoryId,
                        AuditAction.GROUP_MEMBER_REMOVE, g.groupDn(),
                        Map.of("attribute", g.memberAttribute(),
                                "member", userDn,
                                "source", "profile_delete"));
                removed++;
            } catch (Exception e) {
                // Debug not warn: many entries won't be members of every
                // group in the effective set, and most LDAP servers raise
                // NO_SUCH_ATTRIBUTE / NO_SUCH_OBJECT in that case.
                log.debug("Skipping group {} during profile-delete cleanup for {}: {}",
                        g.groupDn(), userDn, e.getMessage());
            }
        }
        return removed;
    }

    /**
     * Adds {@code userDn} to every group in the profile's effective group
     * set (own groups + additional profiles' groups + auto-include profiles'
     * groups, unless excluded). Called by user-create paths so that group
     * assignments declared on the profile are applied immediately after the
     * user entry lands in LDAP — previously this was done client-side by
     * the UI (and not at all by the bulk-import / approval-approved / API
     * flows).
     *
     * <p>Failures on individual group adds are logged but do not abort the
     * call; the user still exists and other group adds still run. Each
     * successful add emits a {@code GROUP_MEMBER_ADD} audit row with
     * {@code source = "profile_create"} so reports can distinguish
     * provisioning-driven additions from operator-initiated ones.</p>
     */
    @Transactional
    public int applyGroupAssignmentsToUser(UUID directoryId, UUID profileId,
                                            String userDn, AuthPrincipal principal) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);
        DirectoryConnection dc = requireDirectory(directoryId);

        List<ProfileGroupAssignment> ownGroups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        List<ProfileResponse.GroupAssignmentEntry> effective =
                computeEffectiveGroups(profile, ownGroups);

        int added = 0;
        for (ProfileResponse.GroupAssignmentEntry g : effective) {
            try {
                ldapGroupService.addMember(dc, g.groupDn(), g.memberAttribute(), userDn);
                auditService.record(principal, directoryId,
                        AuditAction.GROUP_MEMBER_ADD, g.groupDn(),
                        Map.of("attribute", g.memberAttribute(),
                                "member", userDn,
                                "source", "profile_create"));
                added++;
            } catch (Exception e) {
                log.warn("Failed to add {} to profile group {} on create: {}",
                        userDn, g.groupDn(), e.getMessage());
            }
        }
        return added;
    }
}
