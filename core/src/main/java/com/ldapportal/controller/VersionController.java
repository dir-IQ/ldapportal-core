// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns the running build's identifying metadata so the frontend can
 * detect deployment skew (its embedded build SHA vs. the backend's). The
 * endpoint is intentionally permitall — it carries no sensitive info,
 * has to be reachable before login (the frontend skew banner shows on
 * every route, including the login page), and should never block a real
 * user action if it 404s on an old backend.
 *
 * <pre>
 *   GET /api/v1/version → {"sha": "abc1234", "buildTime": "...", "version": "..."}
 * </pre>
 *
 * <p>If {@code spring-boot-maven-plugin}'s {@code build-info} goal isn't
 * wired (e.g. running from {@code ./mvnw spring-boot:run} in dev without
 * a packaged JAR), {@link BuildProperties} won't be on the classpath and
 * the autowire is optional — we degrade to a placeholder {@code "dev"}
 * SHA so the endpoint still responds and the frontend's skew check still
 * fires (and benignly mismatches against the dev frontend's SHA, which
 * is also "dev" if vite is running from a non-build context).</p>
 */
@RestController
@RequestMapping("/api/v1/version")
@RequiredArgsConstructor
public class VersionController {

    @Autowired(required = false)
    private BuildProperties buildProperties;

    @GetMapping
    public Map<String, String> version() {
        Map<String, String> out = new LinkedHashMap<>();
        if (buildProperties == null) {
            out.put("sha", "dev");
            out.put("version", "dev");
            return out;
        }
        // git.sha is injected via spring-boot-maven-plugin additionalProperties
        // — see parent pom.xml. Falls back to "unknown" in odd build setups
        // (e.g. release tarballs without .git).
        String sha = buildProperties.get("git.sha");
        out.put("sha", sha != null && !sha.isBlank() ? sha : "unknown");
        out.put("version", buildProperties.getVersion());
        if (buildProperties.getTime() != null) {
            out.put("buildTime", buildProperties.getTime().toString());
        }
        return out;
    }
}
