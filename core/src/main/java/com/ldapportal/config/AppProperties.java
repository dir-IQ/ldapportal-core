// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed access to the {@code app.*} configuration namespace defined in
 * {@code application.yml}.  Validated at startup so missing/blank required
 * values fail fast with a meaningful error message.
 */
@ConfigurationProperties(prefix = "app")
@Validated
@Getter
@Setter
public class AppProperties {

    @Valid
    private Encryption encryption = new Encryption();

    @Valid
    private Bootstrap bootstrap = new Bootstrap();

    @Valid
    private Jwt jwt = new Jwt();

    @Valid
    private Cookie cookie = new Cookie();

    @Valid
    private Approval approval = new Approval();

    @Valid
    private Auth auth = new Auth();

    /**
     * Public URL path prefix the browser sees in front of the app, e.g.
     * {@code /idm} when a reverse proxy exposes it under a path rather than
     * at the origin root. Empty (the default) means the app is served at the
     * root. The prefix is applied to browser-facing paths only: the session
     * and preferences cookie {@code Path} attributes and the OIDC redirect
     * URI. The backend keeps serving {@code /api/v1}; the proxy is expected
     * to strip the prefix before forwarding.
     *
     * <p>Leave this unset behind a standard WebSEAL junction: WebSEAL strips
     * the junction name on the way in and rewrites cookie {@code Path}
     * attributes to include it on the way out, so the backend must not add
     * it a second time. Set it for proxies that do not rewrite cookie paths
     * (e.g. an nginx ingress with a path rewrite).</p>
     */
    private String publicBasePath = "";

    /** Normalised form: {@code ""} for the root, otherwise {@code /prefix} (leading slash, no trailing). */
    public String getPublicBasePath() {
        return normalizeBasePath(publicBasePath);
    }

    /**
     * Prefix a root-relative path with the public base path, e.g.
     * {@code publicPath("/api/v1")} → {@code /idm/api/v1}, or {@code /api/v1}
     * when no prefix is configured.
     */
    public String publicPath(String rootRelativePath) {
        String base = getPublicBasePath();
        if (rootRelativePath == null || rootRelativePath.isBlank() || "/".equals(rootRelativePath)) {
            return base.isEmpty() ? "/" : base + "/";
        }
        String rel = rootRelativePath.startsWith("/") ? rootRelativePath : "/" + rootRelativePath;
        return base + rel;
    }

    /**
     * Accepts operator input leniently — {@code idm}, {@code /idm}, {@code /idm/},
     * {@code //idm//} — and yields {@code /idm}; blank or {@code /} yields {@code ""}.
     */
    static String normalizeBasePath(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("/{2,}", "/");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "";
        return s.startsWith("/") ? s : "/" + s;
    }

    // ── Nested config classes ─────────────────────────────────────────────────

    @Getter
    @Setter
    public static class Encryption {
        /** Base64-encoded 32-byte AES-256 key.  Loaded from ENCRYPTION_KEY env var. */
        @NotBlank
        private String key;
    }

    @Getter
    @Setter
    public static class Bootstrap {
        @Valid
        private Superadmin superadmin = new Superadmin();

        /**
         * Optional path to a declarative bootstrap config file (YAML) reconciled
         * at startup by {@code BootstrapConfigReconciler}. Loaded from the
         * {@code BOOTSTRAP_CONFIG_FILE} env var; unset/blank disables it (the
         * default), so existing deployments are unaffected.
         */
        private String configFile;

        @Getter
        @Setter
        public static class Superadmin {
            @NotBlank
            private String username;
            @NotBlank
            private String password;
        }
    }

    @Getter
    @Setter
    public static class Jwt {
        /** Base64-encoded long random secret.  Loaded from JWT_SECRET env var. */
        @NotBlank
        private String secret;

        @Positive
        private int expiryMinutes = 60;
    }

    @Getter
    @Setter
    public static class Cookie {
        /**
         * Whether the JWT cookie is sent with the {@code Secure} attribute.
         * Set to {@code false} only in local development (plain HTTP).
         * Defaults to {@code true}.
         */
        private boolean secure = true;
    }

    @Getter
    @Setter
    public static class Approval {
        /**
         * When true, approval requests submitted by superadmins are
         * auto-approved. Config-only (not a DB/UI setting) so a superadmin
         * cannot grant themselves the bypass. Defaults to {@code false}.
         */
        private boolean superadminBypass = false;
    }

    @Getter
    @Setter
    public static class Auth {
        @Valid
        private Oidc oidc = new Oidc();
        @Valid
        private Webseal webseal = new Webseal();

        @Getter
        @Setter
        public static class Oidc {
            /**
             * Whether the OIDC enable toggle and config block are shown in
             * the Authentication settings tab. Does not change OIDC behavior
             * — enablement still comes from the persisted enabledAuthTypes.
             * Defaults to {@code false} (hidden).
             */
            private boolean uiVisible = false;
        }

        @Getter
        @Setter
        public static class Webseal {
            /** Same as {@link Oidc#uiVisible} for WebSEAL. Defaults to {@code true} (shown). */
            private boolean uiVisible = true;
        }
    }
}
