// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.config.AppProperties;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        // 32-byte key base64-encoded (256 bits for HS256)
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) rawKey[i] = (byte) i;

        AppProperties props = new AppProperties();
        props.getJwt().setSecret(Base64.getEncoder().encodeToString(rawKey));
        props.getJwt().setExpiryMinutes(60);

        // Encryption key is validated at startup; provide a valid one for AppProperties
        props.getEncryption().setKey(Base64.getEncoder().encodeToString(rawKey));

        // Bootstrap props require non-blank values
        props.getBootstrap().getSuperadmin().setUsername("superadmin");
        props.getBootstrap().getSuperadmin().setPassword("secret");

        jwtTokenService = new JwtTokenService(props);
    }

    @Test
    void issueAndParse_superadmin() {
        UUID id = UUID.randomUUID();
        AuthPrincipal original = new AuthPrincipal(PrincipalType.SUPERADMIN, id, "admin");

        String token = jwtTokenService.issue(original);

        assertThat(token).isNotBlank();

        AuthPrincipal parsed = jwtTokenService.parse(token);
        assertThat(parsed.type()).isEqualTo(PrincipalType.SUPERADMIN);
        assertThat(parsed.id()).isEqualTo(id);
        assertThat(parsed.username()).isEqualTo("admin");
    }

    @Test
    void issueAndParse_admin() {
        UUID accountId = UUID.randomUUID();
        AuthPrincipal original = new AuthPrincipal(PrincipalType.ADMIN, accountId, "jdoe");

        String token = jwtTokenService.issue(original);

        AuthPrincipal parsed = jwtTokenService.parse(token);
        assertThat(parsed.type()).isEqualTo(PrincipalType.ADMIN);
        assertThat(parsed.id()).isEqualTo(accountId);
        assertThat(parsed.username()).isEqualTo("jdoe");
    }

    @Test
    void parse_invalidToken_throwsJwtException() {
        assertThatThrownBy(() -> jwtTokenService.parse("not.a.jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parse_tamperedToken_throwsJwtException() {
        UUID id = UUID.randomUUID();
        String token = jwtTokenService.issue(
                new AuthPrincipal(PrincipalType.SUPERADMIN, id, "admin"));

        // Flip one character in the signature segment
        String[] parts = token.split("\\.");
        char[] sigChars = parts[2].toCharArray();
        sigChars[0] = (sigChars[0] == 'A') ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(sigChars);

        assertThatThrownBy(() -> jwtTokenService.parse(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void isSuperadmin_returnsCorrectly() {
        AuthPrincipal sa   = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "sa");
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "a");

        assertThat(sa.isSuperadmin()).isTrue();
        assertThat(admin.isSuperadmin()).isFalse();
    }
}
