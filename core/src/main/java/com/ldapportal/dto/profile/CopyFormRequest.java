// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /directories/{dirId}/profiles/{profileId}/copy-form}:
 * the profiles (in any directory) whose user form is replaced by the source
 * profile's.
 */
public record CopyFormRequest(
        @NotEmpty @Size(max = 50) List<@NotNull UUID> targetProfileIds) {
}
