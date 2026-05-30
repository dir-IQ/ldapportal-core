// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Create / update payload for a replication link. Used by both
 * POST {@code /api/v1/superadmin/replication-links} and
 * PUT {@code /api/v1/superadmin/replication-links/{id}}.
 *
 * <p>{@code sourceBaseDn} / {@code targetBaseDn} are optional —
 * NULL pair means identity DN mapping (the design plan's default).
 * Validation in the service layer enforces "both NULL or both set"
 * mirroring the DB CHECK constraint.
 *
 * <p>{@code attributeMappings} can be empty for identity-pass-through
 * (the default — no rename, no value transform).
 */
public record ReplicationLinkRequest(
        @NotBlank @Size(max = 255) String displayName,
        @NotNull UUID sourceDirectoryId,
        @NotNull UUID targetDirectoryId,
        @Size(max = 500) String sourceBaseDn,
        @Size(max = 500) String targetBaseDn,
        boolean enabled,
        boolean autoCreateOnMissing,
        @Valid List<AttributeMappingRequest> attributeMappings) {

    public record AttributeMappingRequest(
            @NotBlank @Size(max = 255) String sourceAttr,
            @NotBlank @Size(max = 255) String targetAttr,
            @Size(max = 2000) String valueTemplate) {}
}
