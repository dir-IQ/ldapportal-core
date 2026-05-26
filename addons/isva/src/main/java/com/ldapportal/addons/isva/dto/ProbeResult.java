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
 * @param warnings            human-readable diagnostics. Empty list
 *                            on a perfectly-healthy probe.
 */
public record ProbeResult(
        boolean reachable,
        boolean sampleSecUserFound,
        List<String> warnings) {

    public ProbeResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
