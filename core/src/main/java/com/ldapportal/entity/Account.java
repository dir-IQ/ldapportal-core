// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Unified account entity for all application users (superadmins and admins).
 * Maps to the {@code accounts} table introduced in V14.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Optimistic-lock counter (§4.4) — surfaced as the resource ETag and
     * checked against {@code If-Match} so a concurrent admin edit (e.g. an IaC
     * apply racing a UI change) fails rather than silently clobbers. Distinct
     * from {@link #credentialsVersion}, which invalidates issued JWTs.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "display_name")
    private String displayName;

    @Column
    private String email;

    /** Application-level role: SUPERADMIN or ADMIN. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountRole role;

    /**
     * Authentication mechanism: LOCAL (bcrypt password), LDAP (bind against the
     * LDAP auth server configured in application_settings), OIDC (external
     * identity provider), or WEBSEAL (IBM WebSEAL / ISVA header trust). All four
     * values are set and branched on at authentication time.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 10)
    private AccountType authType = AccountType.LOCAL;

    /** bcrypt hash; NULL for LDAP accounts or accounts pending first-login setup. */
    @Column(name = "password_hash")
    private String passwordHash;

    /** Distinguished name in the LDAP directory (LDAP auth_type only). */
    @Column(name = "ldap_dn", length = 1000)
    private String ldapDn;

    @Column(nullable = false)
    private boolean active = true;

    // UI customizations (theme, density, ...) moved out of the account row into
    // the per-account user_preferences document (V22) — the single home for
    // everything a user can customize in the UI.

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * Last ID token issued to this account by the OIDC IdP. Overwritten on
     * every OIDC login and cleared on logout. Used as {@code id_token_hint}
     * on RP-initiated logout so the IdP can terminate the exact session
     * without prompting the user.
     */
    @Column(name = "oidc_id_token", columnDefinition = "TEXT")
    private String oidcIdToken;

    /**
     * Encrypted OIDC refresh token, when the IdP issues one (typically only
     * with the {@code offline_access} scope). Used on logout to hit the
     * IdP's revocation endpoint so a disabled / offboarded account can't
     * silently re-authenticate by replaying a saved browser session.
     */
    @Column(name = "oidc_refresh_token_enc", columnDefinition = "TEXT")
    private String oidcRefreshTokenEnc;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Monotonic counter bumped on every credential-changing op
     * (password reset, password change, authType switch). Embedded in
     * the issued JWT as the {@code cv} claim and re-checked by
     * {@link com.ldapportal.auth.JwtAuthenticationFilter} on every
     * request, so a token issued before a credential change is
     * rejected as soon as the next request lands. Defaults to 1 so
     * tokens issued by callers that don't (yet) carry a {@code cv}
     * claim are rejected on first contact — i.e. an op deploy forces
     * one re-login.
     */
    @Column(name = "credentials_version", nullable = false)
    private Long credentialsVersion = 1L;
}
