// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.bootstrap;

import java.util.Map;

/**
 * SPI for contributing addon-owned sections to the declarative config
 * <em>export</em> — the inverse of {@link BootstrapConfigContributor}.
 *
 * <p>{@code com.ldapportal.service.ConfigExportService} serializes a live
 * install into the same YAML shape the {@code BootstrapConfigReconciler}
 * consumes. Core exports the vendor-agnostic sections it owns
 * ({@code directories}, {@code admins}); anything vendor-specific (e.g. the
 * {@code isva} section) is contributed by an addon that registers a bean
 * implementing this interface. The export service invokes every contributor
 * with the mutable root map <em>after</em> the core sections are populated, so
 * a vendor section is appended in a stable position.</p>
 *
 * <p>Implementations add their own top-level section keyed by name (e.g.
 * {@code root.put("isva", ...)}). To keep the emitted YAML free of type tags,
 * values placed into the map must be plain {@link String}/{@link Number}/
 * {@link Boolean}/{@link java.util.List}/{@link Map} instances — convert enums
 * and records with a Jackson {@code ObjectMapper} first. When no addon is on
 * the classpath there are no contributors and those sections are simply
 * absent, mirroring how the reconciler ignores them on a community build.</p>
 */
public interface ConfigExportContributor {

    /**
     * Append this contributor's section(s) to the export document.
     *
     * @param root the mutable, ordered root map the export service serializes
     *             to YAML; a contributor adds its own top-level section(s)
     */
    void export(Map<String, Object> root);
}
