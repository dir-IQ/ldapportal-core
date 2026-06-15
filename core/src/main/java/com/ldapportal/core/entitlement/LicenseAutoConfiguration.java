// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;

/**
 * Wires up the license / entitlement beans for Phase 6. Picks the
 * {@link LicenseProvider} implementation by inspecting
 * {@code ldapportal.license.path}:
 *
 * <ul>
 *   <li>If the property is set and the file at that path is readable,
 *       register a {@link FileLicenseProvider} (signed JWT).</li>
 *   <li>Otherwise fall back to {@link CommunityEditionLicenseProvider}
 *       — the community baseline (no entitlements). This applies to
 *       <em>both</em> distributions: an unconfigured commercial install
 *       runs with community entitlements until a signed license is
 *       installed, rather than silently unlocking everything.</li>
 * </ul>
 *
 * <p>The public Ed25519 key is loaded from a classpath resource named
 * by {@code ldapportal.license.public-key-resource}, defaulting to
 * {@code license/license-public-key.pem} (the production trust anchor,
 * shipped in {@code core/src/main/resources/license/}). The {@code dev}
 * profile overrides this to the checked-in dev key so a locally-minted
 * dev license verifies — see {@code application-dev.yml}.</p>
 *
 * <p>Registered with {@code @ConditionalOnMissingBean} so tests can
 * stub their own {@link LicenseProvider} / {@link EntitlementService}
 * without having to undo any of this.</p>
 */
@AutoConfiguration
@Slf4j
public class LicenseAutoConfiguration {

    static final String DEFAULT_PUBLIC_KEY_RESOURCE = "license/license-public-key.pem";

    /**
     * Fully qualified name of the marker class shipped with the ee
     * jar. Used at startup to detect whether we're running the
     * commercial (core+ee) or community (core-only) distribution
     * when no license file is configured. Don't inline — keep this
     * as a stable named constant since renames break the detection.
     */
    static final String EE_MARKER_CLASS = "com.ldapportal.ee.EeEditionMarker";

    /**
     * Production license verifier. Loads the public key once at
     * startup; Ed25519 key ops are cheap but there's no reason to do
     * the PEM parse more than once.
     */
    @Bean
    @ConditionalOnMissingBean
    public LicenseVerifier licenseVerifier(org.springframework.core.env.Environment env) {
        String resource = env.getProperty(
                "ldapportal.license.public-key-resource", DEFAULT_PUBLIC_KEY_RESOURCE);
        try {
            String pem = new String(
                    new ClassPathResource(resource).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            PublicKey publicKey = LicenseVerifier.parseEd25519PublicKey(pem);
            return new LicenseVerifier(publicKey);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load license public key from classpath:" + resource, e);
        }
    }

    /**
     * License provider. File-based when a license path is configured
     * and points at a readable file; settings-derived otherwise. The
     * chosen base provider is wrapped in an
     * {@link AddonProbingLicenseProvider} so any registered
     * {@link AddonProbe} beans (from {@code addons/*} modules) get
     * to contribute entitlements regardless of which base was picked.
     */
    @Bean
    @ConditionalOnMissingBean
    public LicenseProvider licenseProvider(
            LicenseVerifier verifier,
            List<AddonProbe> addonProbes,
            org.springframework.core.env.Environment env) {

        LicenseProvider base = chooseBaseProvider(verifier, env);
        if (addonProbes != null && !addonProbes.isEmpty()) {
            log.info("Detected {} addon probe(s); decorating base license provider", addonProbes.size());
        }
        return new AddonProbingLicenseProvider(base, addonProbes);
    }

    /**
     * Pick the base provider by inspecting {@code ldapportal.license.path}.
     * Extracted from {@link #licenseProvider} so the addon-probe
     * decoration is the same one-liner regardless of which base is in play.
     */
    private LicenseProvider chooseBaseProvider(
            LicenseVerifier verifier,
            org.springframework.core.env.Environment env) {

        String pathStr = env.getProperty("ldapportal.license.path");
        if (pathStr != null && !pathStr.isBlank()) {
            Path licensePath = Path.of(pathStr);
            if (Files.isReadable(licensePath)) {
                log.info("License file configured at {} — using FileLicenseProvider", licensePath);
                return new FileLicenseProvider(licensePath, verifier);
            }
            // Property set but the file isn't usable — this is almost
            // always a misconfiguration (wrong path, wrong permissions).
            // Fail loud rather than silently falling back, which would
            // mask the operational problem.
            throw new IllegalStateException(
                    "ldapportal.license.path=" + licensePath
                    + " is not a readable file. Either fix the path/permissions "
                    + "or unset the property to run with community baseline entitlements.");
        }

        // No license file → community baseline (no entitlements) for BOTH
        // distributions. A commercial install must present a signed license
        // to unlock enterprise features; an unconfigured commercial deploy no
        // longer silently grants everything. The EE-marker check only affects
        // the log message (the /me isCommunityDistribution probe uses the same
        // signal independently).
        if (ClassUtils.isPresent(EE_MARKER_CLASS, getClass().getClassLoader())) {
            log.warn("Commercial distribution but no license file configured "
                    + "(ldapportal.license.path is unset) — running with community baseline "
                    + "entitlements. Install a signed license file to unlock enterprise "
                    + "features (GOVERNANCE, HR_SYNC, etc.).");
        } else {
            log.info("ee classes absent from classpath — running as community edition.");
        }
        return new CommunityEditionLicenseProvider();
    }

    @Bean
    @ConditionalOnMissingBean(EntitlementService.class)
    public EntitlementService entitlementService(LicenseProvider licenseProvider) {
        return new LicenseBackedEntitlementService(licenseProvider);
    }
}
