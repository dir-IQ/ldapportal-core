// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates signed JWT tokens using HMAC-SHA-256.
 *
 * <p>Token claims:
 * <ul>
 *   <li>{@code sub}  — username</li>
 *   <li>{@code type} — {@link PrincipalType} name</li>
 *   <li>{@code aid}  — account UUID</li>
 *   <li>{@code cv}   — credentials version (admin / superadmin only).
 *       Bumped on password reset / change / authType switch; verified
 *       by {@link JwtAuthenticationFilter} so stale tokens are
 *       refused at the next request after a credential change.</li>
 *   <li>{@code iat}, {@code exp} — standard issued-at / expiry</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private static final String CLAIM_TYPE         = "type";
    private static final String CLAIM_ACCOUNT_ID   = "aid";
    private static final String CLAIM_DN           = "dn";
    private static final String CLAIM_DIRECTORY_ID = "did";
    private static final String CLAIM_CREDENTIALS_VERSION = "cv";

    private final AppProperties appProperties;

    /**
     * Issues a signed JWT for the given principal. Self-service tokens
     * (LDAP-backed; no {@code Account} row) skip the credentials-version
     * binding.
     *
     * @param credentialsVersion the value of the principal's
     *        {@code Account.credentialsVersion} at issue time; ignored
     *        for {@link PrincipalType#SELF_SERVICE}. Pass {@code null}
     *        to omit (legacy callers that don't have an Account).
     */
    public String issue(AuthPrincipal principal, Long credentialsVersion) {
        Instant now    = Instant.now();
        Instant expiry = now.plusSeconds(appProperties.getJwt().getExpiryMinutes() * 60L);

        var builder = Jwts.builder()
                .subject(principal.username())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_TYPE, principal.type().name())
                .claim(CLAIM_ACCOUNT_ID, principal.id().toString());

        if (credentialsVersion != null && principal.type() != PrincipalType.SELF_SERVICE) {
            builder.claim(CLAIM_CREDENTIALS_VERSION, credentialsVersion);
        }

        // Self-service tokens carry the user's LDAP DN and directory ID
        if (principal.dn() != null) {
            builder.claim(CLAIM_DN, principal.dn());
        }
        if (principal.directoryId() != null) {
            builder.claim(CLAIM_DIRECTORY_ID, principal.directoryId().toString());
        }

        return builder.signWith(signingKey()).compact();
    }

    /**
     * Backwards-compatible overload — omits the {@code cv} claim. Any
     * resulting token will fail the credentials-version check on the
     * next request, so callers that authenticate an admin account
     * should prefer the two-arg form.
     */
    public String issue(AuthPrincipal principal) {
        return issue(principal, null);
    }

    /**
     * Holds the result of parsing a JWT — the embedded principal plus
     * the {@code cv} claim (if present), so the filter can compare
     * against the live {@code Account.credentialsVersion}.
     */
    public record ParsedJwt(AuthPrincipal principal, Long credentialsVersion) {}

    /**
     * Parses and validates a JWT string, returning the embedded principal
     * and credentials-version claim.
     *
     * @throws JwtException if the token is expired, malformed, or has an invalid signature
     */
    public ParsedJwt parseFull(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        PrincipalType type = PrincipalType.valueOf(claims.get(CLAIM_TYPE, String.class));
        UUID          id   = UUID.fromString(claims.get(CLAIM_ACCOUNT_ID, String.class));
        // Jackson may deserialize small JSON ints as Integer; coerce
        // through Number so the comparison in the filter is reliable.
        Number cvNum = claims.get(CLAIM_CREDENTIALS_VERSION, Number.class);
        Long   cv    = cvNum != null ? cvNum.longValue() : null;

        if (type == PrincipalType.SELF_SERVICE) {
            String dn = claims.get(CLAIM_DN, String.class);
            String didStr = claims.get(CLAIM_DIRECTORY_ID, String.class);
            UUID directoryId = didStr != null ? UUID.fromString(didStr) : null;
            return new ParsedJwt(
                    new AuthPrincipal(type, id, claims.getSubject(), dn, directoryId), cv);
        }

        return new ParsedJwt(new AuthPrincipal(type, id, claims.getSubject()), cv);
    }

    /**
     * Backwards-compatible parse — returns just the principal. Prefer
     * {@link #parseFull} so the filter can check the credentials
     * version.
     */
    public AuthPrincipal parse(String token) {
        return parseFull(token).principal();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private SecretKey signingKey() {
        byte[] keyBytes = Base64.getDecoder().decode(appProperties.getJwt().getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
