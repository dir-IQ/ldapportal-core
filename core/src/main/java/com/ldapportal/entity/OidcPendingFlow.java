// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Persistent store for a single in-flight OIDC Authorization Code + PKCE flow.
 *
 * <p>Keyed by the random {@code state} parameter the client sends to the IdP;
 * consumed exactly once on the callback. Backed by a table instead of an
 * in-memory map so state survives app restarts and works correctly when the
 * app is horizontally scaled.</p>
 */
@Entity
@Table(name = "oidc_pending_flows")
@Getter
@Setter
@NoArgsConstructor
public class OidcPendingFlow {

    @Id
    @Column(name = "state")
    private String state;

    @Column(name = "nonce", nullable = false)
    private String nonce;

    @Column(name = "code_verifier", nullable = false)
    private String codeVerifier;

    @Column(name = "redirect_uri", nullable = false)
    private String redirectUri;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
