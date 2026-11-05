// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import com.ldapportal.dto.profile.CreateProfileRequest.AttributeConfigEntry;
import com.ldapportal.dto.profile.CreateProfileRequest.GroupAssignmentEntry;
import com.ldapportal.entity.enums.ApproverMode;
import com.ldapportal.entity.enums.ExpiryAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A provisioning profile as a portable JSON file: exported from one directory
 * or install, imported into another. Everything is referenced by name, never
 * by an install's internal id, and nothing tied to a person travels:
 * approvers and the escalation account are left out, and an imported profile
 * always starts disabled.
 *
 * <p>The field names are the file format. Change them only together with
 * {@link #FORMAT_VERSION}, and keep reading older versions.
 *
 * @param formatVersion the format this file follows ({@link #FORMAT_VERSION})
 * @param kind          always {@link #KIND}, so another JSON file is refused up front
 * @param exportedAt    when it was exported (informational)
 * @param source        where it came from (informational, plus the base DN a rewrite starts from)
 * @param profile       the profile itself
 */
public record ProfileExportDocument(
        int formatVersion,
        String kind,
        OffsetDateTime exportedAt,
        Source source,
        @NotNull @Valid ProfileDefinition profile) {

    public static final int FORMAT_VERSION = 1;
    public static final String KIND = "ldapportal.provisioning-profile";

    /**
     * @param directoryName the source directory's display name
     * @param baseDn        the source directory's base DN, the default "from" of an import's base-DN rewrite
     * @param profileName   the profile's name in the source
     */
    public record Source(String directoryName, String baseDn, String profileName) {
    }

    /**
     * The profile's settings, as on the create form. {@code additionalProfileNames}
     * name profiles of the same directory, resolved by name on import.
     */
    public record ProfileDefinition(
            @NotBlank String name,
            String description,
            String themeColor,
            @NotBlank String targetUserDn,
            String targetGroupDn,
            @NotEmpty List<@NotBlank String> objectClassNames,
            String rdnAttribute,
            boolean showDnField,
            String dnTemplate,
            Integer dnColumnSpan,
            String dnSectionName,
            Integer dnDisplayOrder,
            boolean selfRegistrationAllowed,
            Integer passwordLength,
            Boolean passwordUppercase,
            Boolean passwordLowercase,
            Boolean passwordDigits,
            Boolean passwordSpecial,
            String passwordSpecialChars,
            Boolean emailPasswordToUser,
            String passwordDisposition,
            boolean autoIncludeGroups,
            boolean excludeAutoIncludes,
            List<String> additionalProfileNames,
            List<@Valid AttributeConfigEntry> attributeConfigs,
            List<@Valid GroupAssignmentEntry> groupAssignments,
            Lifecycle lifecycle,
            Approval approval) {
    }

    /** The lifecycle policy, or {@code null} when the profile has none. */
    public record Lifecycle(
            Integer expiresAfterDays,
            Integer maxRenewals,
            Integer renewalDays,
            ExpiryAction onExpiryAction,
            String onExpiryMoveDn,
            boolean onExpiryRemoveGroups,
            boolean onExpiryNotify,
            Integer warningDaysBefore) {
    }

    /** The approval settings without approvers, or {@code null} when the profile has none. */
    public record Approval(
            boolean requireApproval,
            ApproverMode approverMode,
            String approverGroupDn,
            Integer autoEscalateDays) {
    }
}
