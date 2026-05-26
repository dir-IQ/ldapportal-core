// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateProfileRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 500) String targetOuDn,
        @NotEmpty List<@NotBlank String> objectClassNames,
        @NotBlank @Size(max = 100) String rdnAttribute,
        boolean showDnField,
        boolean enabled,
        boolean selfRegistrationAllowed,
        Integer passwordLength,
        Boolean passwordUppercase,
        Boolean passwordLowercase,
        Boolean passwordDigits,
        Boolean passwordSpecial,
        @Size(max = 50) String passwordSpecialChars,
        Boolean emailPasswordToUser,
        boolean autoIncludeGroups,
        boolean excludeAutoIncludes,
        List<UUID> additionalProfileIds,
        List<@Valid AttributeConfigEntry> attributeConfigs,
        List<@Valid GroupAssignmentEntry> groupAssignments) {

    public record AttributeConfigEntry(
            @NotBlank @Size(max = 100) String attributeName,
            @Size(max = 255) String customLabel,
            @NotBlank String inputType,
            boolean requiredOnCreate,
            boolean editableOnCreate,
            boolean editableOnUpdate,
            boolean selfServiceEdit,
            boolean selfRegistrationEdit,
            @Size(max = 500) String defaultValue,
            @Size(max = 500) String computedExpression,
            @Size(max = 500) String validationRegex,
            @Size(max = 255) String validationMessage,
            String allowedValues,
            Integer minLength,
            Integer maxLength,
            @Size(max = 100) String sectionName,
            Integer columnSpan,
            boolean hidden,
            @Size(max = 100) String registrationSectionName,
            Integer registrationColumnSpan,
            @Size(max = 100) String selfServiceSectionName,
            Integer selfServiceColumnSpan) {
    }

    public record GroupAssignmentEntry(
            @NotBlank @Size(max = 500) String groupDn,
            @Size(max = 50) String memberAttribute) {
    }
}
