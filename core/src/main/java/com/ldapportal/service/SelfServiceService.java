// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProfileAttributeConfig;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.RegistrationRequest;
import com.ldapportal.entity.enums.ApprovalRequestType;
import com.ldapportal.entity.enums.RegistrationStatus;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ProfileAttributeConfigRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.ldapportal.repository.RegistrationRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SelfServiceService {

    private final DirectoryConnectionRepository dirRepo;
    private final ProvisioningProfileRepository profileRepo;
    private final ProfileAttributeConfigRepository attrConfigRepo;
    private final RegistrationRequestRepository registrationRepo;
    private final LdapUserService ldapUserService;
    private final LdapGroupService ldapGroupService;
    private final LdapConnectionFactory connectionFactory;
    private final ProvisioningProfileService profileService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final ApprovalNotificationService notificationService;
    private final ObjectMapper objectMapper;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── Profile template ─────────────────────────────────────────────────────

    /**
     * Returns the attribute configs for the self-service user's resolved profile,
     * filtered to self-service visible fields.
     */
    @Transactional(readOnly = true)
    public SelfServiceTemplateResponse getTemplate(AuthPrincipal principal) {
        requireSelfService(principal);
        DirectoryConnection dc = requireDirectory(principal.directoryId());
        ProvisioningProfile profile = profileService.resolveProfileForDn(dc.getId(), principal.dn())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No provisioning profile found for your account"));

        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profile.getId());

        List<SelfServiceFieldConfig> fields = configs.stream()
                .filter(c -> !c.isHidden() && c.isSelfServiceEdit())
                .sorted(Comparator.comparingInt(ProfileAttributeConfig::getSelfServiceDisplayOrder))
                .map(c -> new SelfServiceFieldConfig(
                        c.getAttributeName(),
                        c.getCustomLabel() != null ? c.getCustomLabel() : humanize(c.getAttributeName()),
                        c.getInputType().name(),
                        c.isSelfServiceEdit(),
                        c.isRequiredOnCreate(),
                        c.getValidationRegex(),
                        c.getValidationMessage(),
                        c.getAllowedValues(),
                        c.getMinLength(),
                        c.getMaxLength(),
                        c.getSelfServiceSectionName() != null ? c.getSelfServiceSectionName() : c.getSectionName(),
                        c.getSelfServiceColumnSpan() != null ? c.getSelfServiceColumnSpan() : c.getColumnSpan(),
                        c.getSelfServiceDisplayOrder() != null ? c.getSelfServiceDisplayOrder() : c.getDisplayOrder()))
                .toList();

        return new SelfServiceTemplateResponse(profile.getId(), profile.getName(), fields);
    }

    // ── Profile read ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SelfServiceProfileResponse getProfile(AuthPrincipal principal) {
        requireSelfService(principal);
        DirectoryConnection dc = requireDirectory(principal.directoryId());

        // Get all attribute names from the profile config
        ProvisioningProfile profile = profileService.resolveProfileForDn(dc.getId(), principal.dn())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No provisioning profile found for your account"));
        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profile.getId());

        String[] attrNames = configs.stream()
                .filter(c -> !c.isHidden())
                .map(ProfileAttributeConfig::getAttributeName)
                .toArray(String[]::new);

        // Fetch the LDAP entry
        List<LdapUser> users = ldapUserService.searchUsers(
                dc, "(objectClass=*)", principal.dn(), 1, attrNames);
        if (users.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LDAP entry not found");
        }
        LdapUser user = users.get(0);

        // Build response
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (String attr : attrNames) {
            List<String> vals = user.getValues(attr);
            attributes.put(attr, vals != null ? vals : List.of());
        }

        return new SelfServiceProfileResponse(
                user.getDn(),
                user.getDisplayName() != null ? user.getDisplayName() : principal.username(),
                attributes);
    }

    // ── Profile update ───────────────────────────────────────────────────────

    @Transactional
    public void updateProfile(AuthPrincipal principal, Map<String, List<String>> updates) {
        requireSelfService(principal);
        DirectoryConnection dc = requireDirectory(principal.directoryId());

        ProvisioningProfile profile = profileService.resolveProfileForDn(dc.getId(), principal.dn())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No provisioning profile found for your account"));

        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profile.getId());

        // Build set of allowed attribute names
        Set<String> allowedAttrs = configs.stream()
                .filter(ProfileAttributeConfig::isSelfServiceEdit)
                .map(ProfileAttributeConfig::getAttributeName)
                .collect(Collectors.toSet());

        // Validate: only self-service-editable attributes
        for (String attr : updates.keySet()) {
            if (!allowedAttrs.contains(attr)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Attribute [" + attr + "] is not editable in self-service mode");
            }
        }

        // Validate values against profile config rules
        Map<String, ProfileAttributeConfig> configMap = configs.stream()
                .collect(Collectors.toMap(ProfileAttributeConfig::getAttributeName, c -> c));
        for (Map.Entry<String, List<String>> entry : updates.entrySet()) {
            ProfileAttributeConfig config = configMap.get(entry.getKey());
            if (config == null) continue;
            String value = entry.getValue() != null && !entry.getValue().isEmpty()
                    ? entry.getValue().get(0) : null;
            validateAttributeValue(config, value);
        }

        // Apply modifications
        List<Modification> mods = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : updates.entrySet()) {
            List<String> vals = entry.getValue();
            if (vals == null || vals.isEmpty() || (vals.size() == 1 && vals.get(0).isEmpty())) {
                mods.add(new Modification(ModificationType.DELETE, entry.getKey()));
            } else {
                mods.add(new Modification(ModificationType.REPLACE,
                        entry.getKey(), vals.toArray(new String[0])));
            }
        }

        if (!mods.isEmpty()) {
            ldapUserService.updateUser(dc, principal.dn(), mods);
        }
    }

    // ── Password change ──────────────────────────────────────────────────────

    public void changePassword(AuthPrincipal principal, String currentPassword, String newPassword) {
        requireSelfService(principal);
        DirectoryConnection dc = requireDirectory(principal.directoryId());

        // Verify current password by bind-as-user
        try (LDAPConnection conn = connectionFactory.openUnboundConnection(dc)) {
            BindResult result = conn.bind(new SimpleBindRequest(principal.dn(), currentPassword));
            if (result.getResultCode() != ResultCode.SUCCESS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        // Set new password via service account
        ldapUserService.resetPassword(dc, principal.dn(), newPassword);
    }

    // ── Groups ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SelfServiceGroupResponse> getGroups(AuthPrincipal principal) {
        requireSelfService(principal);
        DirectoryConnection dc = requireDirectory(principal.directoryId());

        // Search for groups containing this user as a member
        // Escape the DN value to prevent LDAP filter injection
        String escapedDn = Filter.encodeValue(principal.dn());
        String memberFilter = "(|(member=" + escapedDn + ")"
                + "(uniqueMember=" + escapedDn + "))";
        List<LdapGroup> groups = ldapGroupService.searchGroups(
                dc, memberFilter, dc.getBaseDn(), "cn", "description");

        return groups.stream()
                .map(g -> new SelfServiceGroupResponse(
                        g.getDn(),
                        g.getCn() != null ? g.getCn() : g.getDn(),
                        g.getDescription()))
                .toList();
    }

    // ── Registration: list directories & profiles ────────────────────────────

    @Transactional(readOnly = true)
    public List<RegistrationDirectoryResponse> listRegistrationDirectories() {
        return dirRepo.findAll().stream()
                .filter(dc -> dc.isEnabled() && dc.isSelfServiceEnabled())
                .map(dc -> new RegistrationDirectoryResponse(dc.getId(), dc.getDisplayName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RegistrationProfileResponse> listRegistrationProfiles(UUID directoryId) {
        return profileRepo.findAllByDirectoryIdAndEnabledTrue(directoryId).stream()
                .filter(ProvisioningProfile::isSelfRegistrationAllowed)
                .map(p -> new RegistrationProfileResponse(p.getId(), p.getName(), p.getDescription()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SelfServiceFieldConfig> getRegistrationForm(UUID profileId) {
        ProvisioningProfile profile = profileRepo.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", profileId));
        if (!profile.isSelfRegistrationAllowed()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile does not allow self-registration");
        }

        return attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId).stream()
                .filter(c -> c.isSelfRegistrationEdit() && !c.isHidden()
                        && !"HIDDEN_FIXED".equals(c.getInputType().name()))
                .sorted(Comparator.comparing(ProfileAttributeConfig::getRegistrationDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new SelfServiceFieldConfig(
                        c.getAttributeName(),
                        c.getCustomLabel() != null ? c.getCustomLabel() : humanize(c.getAttributeName()),
                        c.getInputType().name(),
                        true,
                        c.isRequiredOnCreate(),
                        c.getValidationRegex(),
                        c.getValidationMessage(),
                        c.getAllowedValues(),
                        c.getMinLength(),
                        c.getMaxLength(),
                        c.getRegistrationSectionName() != null ? c.getRegistrationSectionName() : c.getSectionName(),
                        c.getRegistrationColumnSpan() != null ? c.getRegistrationColumnSpan() : c.getColumnSpan(),
                        c.getRegistrationDisplayOrder() != null ? c.getRegistrationDisplayOrder() : c.getDisplayOrder()))
                .toList();
    }

    // ── Registration: submit ─────────────────────────────────────────────────

    @Transactional
    public RegistrationSubmitResponse submitRegistration(RegistrationSubmitRequest req) {
        ProvisioningProfile profile = profileRepo.findById(req.profileId())
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", req.profileId()));
        if (!profile.isSelfRegistrationAllowed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Self-registration is not allowed for this profile");
        }

        DirectoryConnection dc = dirRepo.findById(profile.getDirectory().getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "DirectoryConnection", profile.getDirectory().getId()));
        if (!dc.isSelfServiceEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Self-service is not enabled for this directory");
        }

        // Check for duplicate pending registration
        if (registrationRepo.existsByEmailAndProfileIdAndStatusIn(
                req.email(), profile.getId(),
                List.of(RegistrationStatus.PENDING_VERIFICATION, RegistrationStatus.PENDING_APPROVAL))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A registration request for this email is already pending");
        }

        // Validate attributes against profile config
        profileService.validateAttributes(profile.getId(), req.attributes());

        // Create registration request
        RegistrationRequest regReq = new RegistrationRequest();
        regReq.setDirectoryId(dc.getId());
        regReq.setProfileId(profile.getId());
        regReq.setEmail(req.email());
        regReq.setJustification(req.justification());
        regReq.setStatus(RegistrationStatus.PENDING_VERIFICATION);

        try {
            regReq.setAttributes(objectMapper.writeValueAsString(req.attributes()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize attributes", e);
        }

        // Generate verification token
        String token = generateVerificationToken();
        regReq.setVerificationToken(token);
        regReq.setVerificationExpires(OffsetDateTime.now().plusHours(24));

        regReq = registrationRepo.save(regReq);

        // TODO: Send verification email via ApprovalNotificationService or EmailService
        log.info("Registration request {} created for email {}", regReq.getId(), req.email());
        log.debug("Verification token generated for request {}", regReq.getId());

        return new RegistrationSubmitResponse(regReq.getId(), "PENDING_VERIFICATION");
    }

    // ── Registration: verify email ───────────────────────────────────────────

    @Transactional
    public RegistrationVerifyResponse verifyEmail(String token) {
        RegistrationRequest regReq = registrationRepo.findByVerificationToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid or expired verification token"));

        if (regReq.isEmailVerified()) {
            return new RegistrationVerifyResponse("ALREADY_VERIFIED", regReq.getId());
        }

        if (regReq.getVerificationExpires() != null
                && regReq.getVerificationExpires().isBefore(OffsetDateTime.now())) {
            regReq.setStatus(RegistrationStatus.EXPIRED);
            regReq.setVerificationToken(null);
            registrationRepo.save(regReq);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Verification token has expired");
        }

        regReq.setEmailVerified(true);
        regReq.setVerificationToken(null);

        // Check if approval is required
        boolean approvalRequired = profileService.isApprovalRequired(regReq.getProfileId());
        if (approvalRequired) {
            regReq.setStatus(RegistrationStatus.PENDING_APPROVAL);

            // Create a synthetic principal for the registrant
            UUID syntheticId = UUID.nameUUIDFromBytes(
                    regReq.getEmail().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            AuthPrincipal requester = new AuthPrincipal(
                    com.ldapportal.auth.PrincipalType.SELF_SERVICE,
                    syntheticId, regReq.getEmail());

            Map<String, Object> payload = Map.of(
                    "registrationRequestId", regReq.getId().toString(),
                    "email", regReq.getEmail(),
                    "profileId", regReq.getProfileId().toString());

            com.ldapportal.entity.PendingApproval pa =
                    approvalWorkflowService.submitForApproval(
                            regReq.getDirectoryId(),
                            regReq.getProfileId(),
                            requester,
                            ApprovalRequestType.SELF_REGISTRATION,
                            payload);

            regReq.setPendingApprovalId(pa.getId());
        } else {
            regReq.setStatus(RegistrationStatus.APPROVED);
            // Auto-provision immediately
            provisionRegisteredUser(regReq);
        }

        registrationRepo.save(regReq);
        return new RegistrationVerifyResponse(regReq.getStatus().name(), regReq.getId());
    }

    // ── Registration: check status ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public RegistrationStatusResponse getRegistrationStatus(UUID requestId, String email) {
        RegistrationRequest regReq = registrationRepo.findByIdAndEmail(requestId, email)
                .orElseThrow(() -> new ResourceNotFoundException("RegistrationRequest", requestId));

        return new RegistrationStatusResponse(
                regReq.getId(),
                regReq.getEmail(),
                regReq.getStatus().name(),
                regReq.isEmailVerified(),
                regReq.getCreatedAt(),
                regReq.getUpdatedAt());
    }

    // ── Provisioning helper ──────────────────────────────────────────────────

    /**
     * Called when a registration is approved (either auto-approved or via approval workflow).
     */
    public void provisionRegisteredUser(RegistrationRequest regReq) {
        try {
            Map<String, List<String>> attributes = objectMapper.readValue(
                    regReq.getAttributes(),
                    objectMapper.getTypeFactory().constructMapType(
                            LinkedHashMap.class,
                            objectMapper.getTypeFactory().constructType(String.class),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

            // Apply defaults and computed values from profile
            profileService.applyDefaults(regReq.getProfileId(), attributes);

            ProvisioningProfile profile = profileRepo.findById(regReq.getProfileId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "ProvisioningProfile", regReq.getProfileId()));
            DirectoryConnection dc = dirRepo.findById(regReq.getDirectoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "DirectoryConnection", regReq.getDirectoryId()));

            // Build the DN
            String rdnValue = attributes.getOrDefault(profile.getRdnAttribute(), List.of())
                    .stream().findFirst().orElse(null);
            if (rdnValue == null || rdnValue.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "RDN attribute [" + profile.getRdnAttribute() + "] is required");
            }
            // Use UnboundID RDN to properly escape special DN characters
            String dn = new RDN(profile.getRdnAttribute(), rdnValue) + "," + profile.getTargetOuDn();

            // Add objectClasses
            attributes.put("objectClass", profile.getObjectClassNames());

            // Create LDAP entry via service account
            Map<String, List<String>> ldapAttrs = new LinkedHashMap<>(attributes);
            ldapUserService.createUser(dc, dn, ldapAttrs);

            log.info("Provisioned self-registered user {} in profile {}",
                    dn, profile.getName());

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize registration attributes", e);
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record SelfServiceTemplateResponse(
            UUID profileId,
            String profileName,
            List<SelfServiceFieldConfig> fields) {}

    public record SelfServiceFieldConfig(
            String attributeName,
            String label,
            String inputType,
            boolean editable,
            boolean required,
            String validationRegex,
            String validationMessage,
            String allowedValues,
            Integer minLength,
            Integer maxLength,
            String sectionName,
            int columnSpan,
            int displayOrder) {}

    public record SelfServiceProfileResponse(
            String dn,
            String displayName,
            Map<String, List<String>> attributes) {}

    public record SelfServiceGroupResponse(
            String dn,
            String name,
            String description) {}

    public record RegistrationDirectoryResponse(UUID id, String displayName) {}

    public record RegistrationProfileResponse(UUID id, String name, String description) {}

    public record RegistrationSubmitRequest(
            UUID profileId,
            String email,
            String justification,
            Map<String, List<String>> attributes) {}

    public record RegistrationSubmitResponse(UUID requestId, String status) {}

    public record RegistrationVerifyResponse(String status, UUID requestId) {}

    public record RegistrationStatusResponse(
            UUID requestId,
            String email,
            String status,
            boolean emailVerified,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    // ── Private helpers ──────────────────────────────────────────────────────

    private void requireSelfService(AuthPrincipal principal) {
        if (!principal.isSelfService()) {
            throw new AccessDeniedException("Self-service access required");
        }
    }

    private DirectoryConnection requireDirectory(UUID directoryId) {
        return dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
    }

    private void validateAttributeValue(ProfileAttributeConfig config, String value) {
        if (config.isRequiredOnCreate() && (value == null || value.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Attribute [" + config.getAttributeName() + "] is required");
        }
        if (value == null || value.isBlank()) return;

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
        if (config.getValidationRegex() != null && !config.getValidationRegex().isBlank()) {
            if (!value.matches(config.getValidationRegex())) {
                String msg = config.getValidationMessage() != null
                        ? config.getValidationMessage()
                        : "Attribute [" + config.getAttributeName() + "] does not match the required format";
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
            }
        }
    }

    private static String humanize(String attributeName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < attributeName.length(); i++) {
            char c = attributeName.charAt(i);
            if (i == 0) {
                sb.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                sb.append(' ').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String generateVerificationToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
