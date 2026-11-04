// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /directories/{dirId}/profiles/import}.
 *
 * @param document        the exported profile file
 * @param mode            create a new profile, or replace an existing profile's form
 * @param name            {@link Mode#CREATE}: the new profile's name (default: the file's)
 * @param targetProfileId {@link Mode#REPLACE_FORM}: the profile in this directory whose form is replaced
 * @param rewriteBaseDn   {@link Mode#CREATE}: rewrite DN values from one base DN to another
 * @param baseDnFrom      base DN to rewrite from (default: the file's source base DN)
 * @param baseDnTo        base DN to rewrite to (default: this directory's base DN)
 * @param force           {@link Mode#CREATE}: create even if the target OU or groups don't exist yet
 * @param dryRun          only work out what would happen: DN changes and warnings, nothing saved
 */
public record ProfileImportRequest(
        @NotNull @Valid ProfileExportDocument document,
        @NotNull Mode mode,
        @Size(max = 255) String name,
        UUID targetProfileId,
        boolean rewriteBaseDn,
        @Size(max = 500) String baseDnFrom,
        @Size(max = 500) String baseDnTo,
        boolean force,
        boolean dryRun) {

    public enum Mode {
        /** Create a new, disabled profile from the file. */
        CREATE,
        /** Replace an existing profile's user form (fields and DN field layout) with the file's. */
        REPLACE_FORM
    }
}
