// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies signed license JWTs and maps their claims into the core
 * {@link License} record. The public Ed25519 key used for verification
 * is supplied at construction time — normally loaded from the PEM file
 * at {@code core/src/main/resources/license/license-public-key.pem}.
 *
 * <h2>JWT claim schema</h2>
 * <pre>
 *   sub       (string, required) — customer UUID
 *   iat       (numeric, required) — issued-at, seconds since epoch
 *   exp       (numeric, required) — expiration, seconds since epoch
 *   edition   (string, required)  — one of COMMUNITY / TEAM / BUSINESS / ENTERPRISE
 *   addOns    (array of string, optional) — Entitlement enum names
 *   limits    (object, optional) — { "<LimitType name>": &lt;long&gt;, ... }
 * </pre>
 *
 * <h2>Validation outcomes</h2>
 * <ul>
 *   <li>Signature invalid, malformed JWT, or required claim missing →
 *       {@link LicenseInvalidException} (the file is rejected; callers
 *       decide whether that means hard-fail or fall-back).</li>
 *   <li>Past expiration but within {@link #graceDuration} of it →
 *       verification succeeds and the returned License carries the real
 *       expiration, but {@link #isExpired(License, Instant)} returns
 *       true so the startup banner can warn. Dashboards and license-
 *       status views use this to show a nag banner.</li>
 *   <li>Past expiration AND past grace → verification still succeeds
 *       (the License is structurally valid) but both
 *       {@link #isExpired} and {@link #isPastGrace} are true. Phase 6
 *       stops there and logs a warning; a later pass can wire that up
 *       to a hard boot failure.</li>
 * </ul>
 *
 * <p>Signature check is strict — invalid signatures are never
 * silently ignored. The no-license fallback
 * ({@code CommunityEditionLicenseProvider}, community baseline) is how
 * an install runs without a license file; it does not involve this
 * verifier.</p>
 */
@Slf4j
public class LicenseVerifier {

    /**
     * Default grace period — 30 days past {@code exp} during which the
     * license is reported as expired-but-tolerated. Matches
     * {@code docs/core-ee-refactor-plan.md} Phase 6.
     */
    public static final Duration DEFAULT_GRACE = Duration.ofDays(30);

    private final PublicKey publicKey;
    private final Duration graceDuration;

    public LicenseVerifier(PublicKey publicKey) {
        this(publicKey, DEFAULT_GRACE);
    }

    public LicenseVerifier(PublicKey publicKey, Duration graceDuration) {
        this.publicKey = publicKey;
        this.graceDuration = graceDuration;
    }

    /**
     * Parse the PEM-encoded Ed25519 public key text into a
     * {@link PublicKey} suitable for passing to
     * {@link #LicenseVerifier(PublicKey)}.
     */
    public static PublicKey parseEd25519PublicKey(String pemText) {
        try {
            String base64 = pemText
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ed25519 public key PEM", e);
        }
    }

    /**
     * Verify the signature of {@code jwt} against this verifier's public
     * key and decode its claims into a {@link License}. Throws
     * {@link LicenseInvalidException} if the signature is wrong, the JWT
     * is malformed, or a required claim is missing. Does <em>not</em>
     * reject expired licenses — the expiry is carried on the returned
     * License and evaluated separately by
     * {@link #isExpired(License, Instant)} /
     * {@link #isPastGrace(License, Instant)}.
     */
    public License verify(String jwt) {
        Claims c;
        try {
            c = Jwts.parser()
                    .verifyWith(publicKey)
                    .clockSkewSeconds(60) // tolerate one minute of drift
                    .requireIssuer("ldapadmin")
                    .requireAudience("ldapadmin")
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            // JJWT rejects expired tokens by default, but our grace-period
            // logic is the whole point of returning the License and letting
            // callers classify the state. The signature has already been
            // verified at this point — expiration is the *only* reason the
            // parse raised — so grabbing the claims off the exception is
            // safe. isExpired/isPastGrace will then classify appropriately.
            c = e.getClaims();
        } catch (JwtException e) {
            throw new LicenseInvalidException(
                    "License JWT failed verification: " + e.getMessage(), e);
        }

        // Required claims
        UUID customerId = parseUuid(c.getSubject(), "sub");
        Edition edition = parseEdition(requireString(c, "edition"));
        Instant issuedAt = c.getIssuedAt() == null ? null : c.getIssuedAt().toInstant();
        Instant expiresAt = c.getExpiration() == null ? null : c.getExpiration().toInstant();
        if (issuedAt == null) {
            throw new LicenseInvalidException("License JWT missing required 'iat' claim");
        }
        if (expiresAt == null) {
            throw new LicenseInvalidException("License JWT missing required 'exp' claim");
        }

        // Optional claims
        Set<Entitlement> addOns = parseAddOns(c);
        Map<LimitType, Long> limits = parseLimits(c);

        return new License(
                customerId,
                edition,
                addOns,
                limits,
                issuedAt,
                expiresAt,
                jwt // store the signed token for diagnostics / re-check
        );
    }

    /** True if {@code now} is past the license's {@code expiresAt}. */
    public boolean isExpired(License license, Instant now) {
        return license.isExpired(now);
    }

    /**
     * True if {@code now} is past {@code expiresAt + graceDuration}. At
     * this point the license should be treated as effectively invalid;
     * callers decide whether to hard-fail, degrade, or warn loudly.
     */
    public boolean isPastGrace(License license, Instant now) {
        if (license.expiresAt().equals(Instant.MAX)) return false;
        return now.isAfter(license.expiresAt().plus(graceDuration));
    }

    public Duration graceDuration() {
        return graceDuration;
    }

    // ── claim parsing helpers ────────────────────────────────────────────────

    private static String requireString(Claims c, String key) {
        Object v = c.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new LicenseInvalidException("License JWT missing required '" + key + "' claim");
        }
        return s;
    }

    private static UUID parseUuid(String s, String claim) {
        if (s == null || s.isBlank()) {
            throw new LicenseInvalidException("License JWT missing required '" + claim + "' claim");
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new LicenseInvalidException(
                    "License JWT '" + claim + "' is not a valid UUID: " + s);
        }
    }

    private static Edition parseEdition(String s) {
        try {
            return Edition.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new LicenseInvalidException(
                    "License JWT 'edition' is not a recognised Edition: " + s);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Entitlement> parseAddOns(Claims c) {
        Object raw = c.get("addOns");
        if (raw == null) return Set.of();
        if (!(raw instanceof List<?> list)) {
            throw new LicenseInvalidException(
                    "License JWT 'addOns' must be an array of strings");
        }
        Set<Entitlement> result = EnumSet.noneOf(Entitlement.class);
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new LicenseInvalidException(
                        "License JWT 'addOns' entry is not a string: " + item);
            }
            try {
                result.add(Entitlement.valueOf(s));
            } catch (IllegalArgumentException e) {
                // Unknown entitlement names are tolerated — a customer license
                // issued against a newer signing tool may name entitlements
                // this build doesn't know about yet. Log and skip.
                log.warn("License JWT names unknown entitlement '{}' — ignoring", s);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<LimitType, Long> parseLimits(Claims c) {
        Object raw = c.get("limits");
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) {
            throw new LicenseInvalidException(
                    "License JWT 'limits' must be an object");
        }
        Map<LimitType, Long> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String keyStr)) continue;
            LimitType type;
            try {
                type = LimitType.valueOf(keyStr);
            } catch (IllegalArgumentException e) {
                log.warn("License JWT names unknown limit '{}' — ignoring", keyStr);
                continue;
            }
            Object value = entry.getValue();
            long limit;
            if (value instanceof Number n) {
                limit = n.longValue();
            } else if (value instanceof String vs) {
                try {
                    limit = Long.parseLong(vs);
                } catch (NumberFormatException nfe) {
                    throw new LicenseInvalidException(
                            "License JWT limit '" + keyStr + "' is not a number: " + value);
                }
            } else {
                throw new LicenseInvalidException(
                        "License JWT limit '" + keyStr + "' is not a number: " + value);
            }
            result.put(type, limit);
        }
        return result;
    }
}
