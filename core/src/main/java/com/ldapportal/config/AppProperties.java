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
