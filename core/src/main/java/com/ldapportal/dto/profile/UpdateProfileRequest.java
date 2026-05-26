// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import com.ldapportal.dto.profile.CreateProfileRequest.AttributeConfigEntry;
import com.ldapportal.dto.profile.CreateProfileRequest.GroupAssignmentEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateProfileRequest(
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
}
