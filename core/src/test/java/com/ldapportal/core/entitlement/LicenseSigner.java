// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Test-scope helper that signs license JWTs. Lives in test sources,
 * not main, because the code path that mints licenses should never
 * ship in the production build — only the verifier (public-key-only)
 * does.
 *
 * <p>Each test that needs a signed license creates a
 * {@link #freshKeypair() fresh ephemeral keypair}, constructs the
 * matching {@link LicenseVerifier} from the public key, and signs a
 * JWT with the private key via {@link #signLicense}. Nothing in this
 * class depends on the committed dev public key under
 * {@code core/src/main/resources/license/} — tests are
 * self-contained and don't need the private key that pairs with it.</p>
 *
 * <p>A future productisation pass can split this into a separately-
 * published CLI (see {@code docs/core-ee-refactor-plan.md} Phase 6). For now
 * the in-test helper is enough to exercise every verification path.</p>
 */
public final class LicenseSigner {

    private LicenseSigner() {}

    public static KeyPair freshKeypair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 key generation failed", e);
        }
    }

    public static String signLicense(PrivateKey signingKey, Claims claims) {
        Map<String, Object> jwtClaims = new HashMap<>();
        jwtClaims.put("edition", claims.edition().name());
        if (!claims.addOns().isEmpty()) {
            jwtClaims.put("addOns", claims.addOns().stream().map(Enum::name).toList());
        }
        if (!claims.limits().isEmpty()) {
            Map<String, Long> limits = new HashMap<>();
            claims.limits().forEach((k, v) -> limits.put(k.name(), v));
            jwtClaims.put("limits", limits);
        }
        return Jwts.builder()
                .issuer("ldapadmin")
                .audience().add("ldapadmin").and()
                .subject(claims.customerId().toString())
                .issuedAt(Date.from(claims.issuedAt()))
                .expiration(Date.from(claims.expiresAt()))
                .claims(jwtClaims)
                .signWith(signingKey, Jwts.SIG.EdDSA)
                .compact();
    }

    public static LicenseVerifier verifierFor(PublicKey publicKey) {
        return new LicenseVerifier(publicKey);
    }

    /** Builder-style convenience for common claim combinations. */
    public record Claims(
            UUID customerId,
            Edition edition,
            Set<Entitlement> addOns,
            Map<LimitType, Long> limits,
            Instant issuedAt,
            Instant expiresAt) {

        public static Claims enterprise(Instant expiresAt) {
            return new Claims(
                    UUID.randomUUID(),
                    Edition.ENTERPRISE,
                    Set.of(),
                    Map.of(),
                    Instant.now(),
                    expiresAt);
        }

        public static Claims business(Instant expiresAt, Set<Entitlement> addOns) {
            return new Claims(
                    UUID.randomUUID(),
                    Edition.BUSINESS,
                    addOns,
                    Map.of(),
                    Instant.now(),
                    expiresAt);
        }
    }
}
