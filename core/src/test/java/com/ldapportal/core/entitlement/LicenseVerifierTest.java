// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseVerifierTest {

    private final KeyPair keypair = LicenseSigner.freshKeypair();
    private final LicenseVerifier verifier = LicenseSigner.verifierFor(keypair.getPublic());

    @Test
    void verifies_valid_enterprise_license() {
        Instant exp = Instant.now().plus(Duration.ofDays(365));
        LicenseSigner.Claims c = LicenseSigner.Claims.enterprise(exp);

        String jwt = LicenseSigner.signLicense(keypair.getPrivate(), c);
        License license = verifier.verify(jwt);

        assertThat(license.edition()).isEqualTo(Edition.ENTERPRISE);
        assertThat(license.customerId()).isEqualTo(c.customerId());
        assertThat(license.expiresAt()).isCloseTo(exp, within(1));
        assertThat(license.has(Entitlement.GOVERNANCE)).isTrue(); // from ENTERPRISE baseline
        assertThat(license.has(Entitlement.HR_SYNC)).isTrue();
    }

    @Test
    void verifies_business_license_with_addOns() {
        Instant exp = Instant.now().plus(Duration.ofDays(30));
        LicenseSigner.Claims c = LicenseSigner.Claims.business(
                exp, Set.of(Entitlement.GOVERNANCE, Entitlement.HR_SYNC));

        String jwt = LicenseSigner.signLicense(keypair.getPrivate(), c);
        License license = verifier.verify(jwt);

        assertThat(license.edition()).isEqualTo(Edition.BUSINESS);
        assertThat(license.has(Entitlement.ALERTING)).isTrue();       // baseline
        assertThat(license.has(Entitlement.GOVERNANCE)).isTrue();     // add-on
        assertThat(license.has(Entitlement.HR_SYNC)).isTrue();        // add-on
        assertThat(license.has(Entitlement.SAML_ADMIN_SSO)).isFalse();// ENTERPRISE-only
    }

    @Test
    void verifies_limits() {
        LicenseSigner.Claims c = new LicenseSigner.Claims(
                UUID.randomUUID(),
                Edition.BUSINESS,
                Set.of(),
                Map.of(LimitType.DIRECTORIES, 10L, LimitType.ADMIN_ACCOUNTS, 50L),
                Instant.now(),
                Instant.now().plus(Duration.ofDays(30)));

        String jwt = LicenseSigner.signLicense(keypair.getPrivate(), c);
        License license = verifier.verify(jwt);

        assertThat(license.limitFor(LimitType.DIRECTORIES)).isEqualTo(10L);
        assertThat(license.limitFor(LimitType.ADMIN_ACCOUNTS)).isEqualTo(50L);
    }

    @Test
    void rejects_license_signed_by_different_key() {
        KeyPair other = LicenseSigner.freshKeypair();
        String jwt = LicenseSigner.signLicense(
                other.getPrivate(),
                LicenseSigner.Claims.enterprise(Instant.now().plus(Duration.ofDays(30))));

        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseInvalidException.class)
                .hasMessageContaining("failed verification");
    }

    @Test
    void rejects_malformed_jwt() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOf(LicenseInvalidException.class);
    }

    @Test
    void rejects_missing_edition_claim() {
        // Manually build a JWT with no 'edition' claim.
        String jwt = Jwts.builder()
                .issuer("ldapadmin")
                .audience().add("ldapadmin").and()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(Duration.ofDays(30))))
                .signWith(keypair.getPrivate(), Jwts.SIG.EdDSA)
                .compact();

        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseInvalidException.class)
                .hasMessageContaining("'edition'");
    }

    @Test
    void rejects_wrong_issuer() {
        String jwt = Jwts.builder()
                .issuer("someone-else")
                .audience().add("ldapadmin").and()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(Duration.ofDays(30))))
                .claim("edition", "ENTERPRISE")
                .signWith(keypair.getPrivate(), Jwts.SIG.EdDSA)
                .compact();

        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseInvalidException.class);
    }

    @Test
    void expired_license_within_grace_reports_expired_but_not_past_grace() {
        // Expired 5 days ago, default 30-day grace still in effect.
        LicenseSigner.Claims c = LicenseSigner.Claims.enterprise(
                Instant.now().minus(Duration.ofDays(5)));
        String jwt = LicenseSigner.signLicense(keypair.getPrivate(), c);
        License license = verifier.verify(jwt);

        Instant now = Instant.now();
        assertThat(verifier.isExpired(license, now)).isTrue();
        assertThat(verifier.isPastGrace(license, now)).isFalse();
    }

    @Test
    void expired_license_past_grace_reports_both() {
        // Expired 40 days ago, beyond 30-day grace.
        LicenseSigner.Claims c = LicenseSigner.Claims.enterprise(
                Instant.now().minus(Duration.ofDays(40)));
        String jwt = LicenseSigner.signLicense(keypair.getPrivate(), c);
        License license = verifier.verify(jwt);

        Instant now = Instant.now();
        assertThat(verifier.isExpired(license, now)).isTrue();
        assertThat(verifier.isPastGrace(license, now)).isTrue();
    }

    @Test
    void grace_period_is_configurable() {
        LicenseVerifier tightVerifier = new LicenseVerifier(
                keypair.getPublic(), Duration.ofDays(1));

        // Expired 2 days ago — past the 1-day grace this verifier uses.
        LicenseSigner.Claims c = LicenseSigner.Claims.enterprise(
                Instant.now().minus(Duration.ofDays(2)));
        String jwt = LicenseSigner.signLicense(keypair.getPrivate(), c);
        License license = tightVerifier.verify(jwt);

        assertThat(tightVerifier.isPastGrace(license, Instant.now())).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static org.assertj.core.data.TemporalUnitOffset within(long seconds) {
        return org.assertj.core.api.Assertions.within(seconds, java.time.temporal.ChronoUnit.SECONDS);
    }
}
