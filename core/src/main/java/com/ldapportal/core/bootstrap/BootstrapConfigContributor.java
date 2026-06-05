// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SPI for applying addon-owned sections of the declarative bootstrap config
 * file (see {@code com.ldapportal.service.BootstrapConfigReconciler} and
 * Phase 2 of {@code docs/plans/2026-06-05-iac-automation-design.md}).
 *
 * <p>Core reconciles the vendor-agnostic sections it owns — {@code directories}
 * and {@code admins} — itself. Anything vendor-specific (e.g. the {@code isva}
 * section) is contributed by an addon that registers a bean implementing this
 * interface; the reconciler invokes every contributor with the parsed config
 * tree <em>after</em> the core sections are applied (so a vendor section may
 * reference a directory the core just created). When no addon is on the
 * classpath there are no contributors and those sections are simply ignored —
 * keeping core agnostic and community builds working unchanged.</p>
 *
 * <p>Implementations should read only their own top-level section, validate it,
 * and apply it through the same idempotent service paths the REST API uses.
 * Throwing aborts startup (fail-fast on a bad config), consistent with the rest
 * of the reconciler.</p>
 */
public interface BootstrapConfigContributor {

    /**
     * Apply this contributor's section(s) of the bootstrap config.
     *
     * @param root the parsed (and {@code ${ENV}}-interpolated) config document;
     *             a contributor picks its own section via {@code root.path("...")}
     */
    void contribute(JsonNode root);
}
