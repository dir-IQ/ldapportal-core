// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import java.util.List;

/**
 * Result of POST /api/v1/directories/{id}/isva-config/probe.
 * Returned with HTTP 200 even when checks fail — the failures
 * are part of the probe report. Operators read them in the UI.
 *
 * @param reachable           true if the configured management DIT
 *                            base DN was found in the directory.
 *                            Inline mode: vacuously true (no
 *                            management DIT is configured).
 * @param sampleSecUserFound  true if at least one entry with
 *                            {@code objectClass: secUser} was
 *                            found under the configured base.
 *                            False on a fresh install before any
 *                            user has been created.
 * @param schemaValid         {@code TRUE} if every configured secUser
 *                            objectClass exists in the server schema
 *                            and (linked mode) the configured RDN
 *                            attribute is permitted by one of them;
 *                            {@code FALSE} if a check failed;
 *                            {@code null} if the server schema couldn't
 *                            be read to make the determination. Detail
 *                            is in {@code warnings}.
 * @param warnings            human-readable diagnostics. Empty list
 *                            on a perfectly-healthy probe.
 */
public record ProbeResult(
        boolean reachable,
        boolean sampleSecUserFound,
        Boolean schemaValid,
        List<String> warnings) {

    public ProbeResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
