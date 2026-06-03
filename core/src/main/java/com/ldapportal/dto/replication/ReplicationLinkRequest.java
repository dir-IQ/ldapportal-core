// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReplicationCaptureMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
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
 *
 * <p>The {@code reconcile*} fields configure periodic source↔target
 * reconciliation (off by default). When {@code reconcileEnabled} is true
 * the service requires {@code reconcileFirstRunAt} and
 * {@code reconcileIntervalSecs} (≥ 3600). {@code reconcileMode} and
 * {@code reconcileDeleteAction} default to {@code REVIEW} when omitted.
 *
 * <p>The {@code captureMode} / {@code changelog*} / {@code excludeFilter}
 * fields configure changelog-driven capture (default {@code APP_INTERCEPT}).
 * When {@code CHANGELOG}, the service requires {@code changelogFormat}
 * ({@code DSEE_CHANGELOG} in v1) and defaults {@code changelogBaseDn} to
 * {@code cn=changelog}; for {@code APP_INTERCEPT} the changelog fields are
 * nulled out. {@code excludeFilter}, if present, must be a parseable RFC 4515
 * filter and applies to either capture mode.
 */
public record ReplicationLinkRequest(
        @NotBlank @Size(max = 255) String displayName,
        @NotNull UUID sourceDirectoryId,
        @NotNull UUID targetDirectoryId,
        @Size(max = 500) String sourceBaseDn,
        @Size(max = 500) String targetBaseDn,
        boolean enabled,
        boolean autoCreateOnMissing,
        @Valid List<AttributeMappingRequest> attributeMappings,
        boolean reconcileEnabled,
        ReconcileMode reconcileMode,
        OffsetDateTime reconcileFirstRunAt,
        Integer reconcileIntervalSecs,
        ReconcileDeleteAction reconcileDeleteAction,
        ReplicationCaptureMode captureMode,
        ChangelogFormat changelogFormat,
        @Size(max = 500) String changelogBaseDn,
        @Size(max = 2000) String excludeFilter) {

    public record AttributeMappingRequest(
            @NotBlank @Size(max = 255) String sourceAttr,
            @NotBlank @Size(max = 255) String targetAttr,
            @Size(max = 2000) String valueTemplate) {}

    /**
     * Back-compat constructor for callers (and tests) predating changelog
     * capture — defaults capture to {@code APP_INTERCEPT} with no changelog
     * config or exclude filter.
     */
    public ReplicationLinkRequest(String displayName, UUID sourceDirectoryId, UUID targetDirectoryId,
                                  String sourceBaseDn, String targetBaseDn, boolean enabled,
                                  boolean autoCreateOnMissing, List<AttributeMappingRequest> attributeMappings,
                                  boolean reconcileEnabled, ReconcileMode reconcileMode,
                                  OffsetDateTime reconcileFirstRunAt, Integer reconcileIntervalSecs,
                                  ReconcileDeleteAction reconcileDeleteAction) {
        this(displayName, sourceDirectoryId, targetDirectoryId, sourceBaseDn, targetBaseDn, enabled,
                autoCreateOnMissing, attributeMappings, reconcileEnabled, reconcileMode, reconcileFirstRunAt,
                reconcileIntervalSecs, reconcileDeleteAction, null, null, null, null);
    }

    /**
     * Back-compat constructor for callers (and tests) predating the
     * reconciliation fields — defaults reconciliation to disabled and capture
     * to {@code APP_INTERCEPT}.
     */
    public ReplicationLinkRequest(String displayName, UUID sourceDirectoryId, UUID targetDirectoryId,
                                  String sourceBaseDn, String targetBaseDn, boolean enabled,
                                  boolean autoCreateOnMissing, List<AttributeMappingRequest> attributeMappings) {
        this(displayName, sourceDirectoryId, targetDirectoryId, sourceBaseDn, targetBaseDn, enabled,
                autoCreateOnMissing, attributeMappings, false, null, null, null, null);
    }
}
