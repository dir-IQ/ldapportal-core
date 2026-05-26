// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import com.ldapportal.entity.ProfileAttributeConfig;
import com.ldapportal.entity.ProfileGroupAssignment;
import com.ldapportal.entity.ProvisioningProfile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ProfileResponse(
        UUID id,
        UUID directoryId,
        String directoryName,
        String name,
        String description,
        String targetOuDn,
        List<String> objectClassNames,
        String rdnAttribute,
        boolean showDnField,
        boolean enabled,
        boolean selfRegistrationAllowed,
        int passwordLength,
        boolean passwordUppercase,
        boolean passwordLowercase,
        boolean passwordDigits,
        boolean passwordSpecial,
        String passwordSpecialChars,
        boolean emailPasswordToUser,
        boolean autoIncludeGroups,
        boolean excludeAutoIncludes,
        List<AdditionalProfileEntry> additionalProfiles,
        List<AttributeConfigEntry> attributeConfigs,
        List<GroupAssignmentEntry> groupAssignments,
        List<GroupAssignmentEntry> effectiveGroupAssignments,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record AdditionalProfileEntry(UUID id, String name) {}


    public record AttributeConfigEntry(
            UUID id,
            String attributeName,
            String customLabel,
            String inputType,
            boolean requiredOnCreate,
            boolean editableOnCreate,
            boolean editableOnUpdate,
            boolean selfServiceEdit,
            boolean selfRegistrationEdit,
            String defaultValue,
            String computedExpression,
            String validationRegex,
            String validationMessage,
            String allowedValues,
            Integer minLength,
            Integer maxLength,
            String sectionName,
            int columnSpan,
            int displayOrder,
            boolean hidden,
            String registrationSectionName,
            Integer registrationColumnSpan,
            Integer registrationDisplayOrder,
            String selfServiceSectionName,
            Integer selfServiceColumnSpan,
            Integer selfServiceDisplayOrder) {

        public static AttributeConfigEntry from(ProfileAttributeConfig c) {
            return new AttributeConfigEntry(
                    c.getId(),
                    c.getAttributeName(),
                    c.getCustomLabel(),
                    c.getInputType().name(),
                    c.isRequiredOnCreate(),
                    c.isEditableOnCreate(),
                    c.isEditableOnUpdate(),
                    c.isSelfServiceEdit(),
                    c.isSelfRegistrationEdit(),
                    c.getDefaultValue(),
                    c.getComputedExpression(),
                    c.getValidationRegex(),
                    c.getValidationMessage(),
                    c.getAllowedValues(),
                    c.getMinLength(),
                    c.getMaxLength(),
                    c.getSectionName(),
                    c.getColumnSpan(),
                    c.getDisplayOrder(),
                    c.isHidden(),
                    c.getRegistrationSectionName(),
                    c.getRegistrationColumnSpan(),
                    c.getRegistrationDisplayOrder(),
                    c.getSelfServiceSectionName(),
                    c.getSelfServiceColumnSpan(),
                    c.getSelfServiceDisplayOrder());
        }
    }

    public record GroupAssignmentEntry(
            UUID id,
            String groupDn,
            String memberAttribute,
            int displayOrder) {

        public static GroupAssignmentEntry from(ProfileGroupAssignment g) {
            return new GroupAssignmentEntry(
                    g.getId(),
                    g.getGroupDn(),
                    g.getMemberAttribute(),
                    g.getDisplayOrder());
        }
    }

    public static ProfileResponse from(ProvisioningProfile p,
                                        List<ProfileAttributeConfig> configs,
                                        List<ProfileGroupAssignment> groups,
                                        List<AdditionalProfileEntry> additionalProfiles,
                                        List<GroupAssignmentEntry> effectiveGroupAssignments) {
        return new ProfileResponse(
                p.getId(),
                p.getDirectory().getId(),
                p.getDirectory().getDisplayName(),
                p.getName(),
                p.getDescription(),
                p.getTargetOuDn(),
                List.copyOf(p.getObjectClassNames()),
                p.getRdnAttribute(),
                p.isShowDnField(),
                p.isEnabled(),
                p.isSelfRegistrationAllowed(),
                p.getPasswordLength(),
                p.isPasswordUppercase(),
                p.isPasswordLowercase(),
                p.isPasswordDigits(),
                p.isPasswordSpecial(),
                p.getPasswordSpecialChars(),
                p.isEmailPasswordToUser(),
                p.isAutoIncludeGroups(),
                p.isExcludeAutoIncludes(),
                additionalProfiles,
                configs.stream().map(AttributeConfigEntry::from).toList(),
                groups.stream().map(GroupAssignmentEntry::from).toList(),
                effectiveGroupAssignments,
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
